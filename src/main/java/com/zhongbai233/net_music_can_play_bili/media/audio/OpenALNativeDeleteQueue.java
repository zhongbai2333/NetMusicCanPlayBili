package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseProperties;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Serializes deferred OpenAL source and buffer deletion onto a context-owning daemon. */
final class OpenALNativeDeleteQueue {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ConcurrentLinkedQueue<NativeResources> PENDING = new ConcurrentLinkedQueue<>();
    private static final Object DRAIN_LOCK = new Object();
    private static final AtomicBoolean DRAIN_SCHEDULED = new AtomicBoolean();
    private static final AtomicLong NEXT_RETRY_NANOS = new AtomicLong();
    private static final long RETRY_NANOS = MediaCloseProperties.openAlRetryNanos();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
            NetMusicThreadFactory.daemon("OpenALSpatialCleanup"));

    private OpenALNativeDeleteQueue() {
    }

    static void enqueue(int[] bedSources, int[] objectSources, int[][] bedBuffers, int[][] objectBuffers) {
        int sourceCount = countSources(bedSources) + countSources(objectSources);
        int bufferCount = countBuffers(bedBuffers) + countBuffers(objectBuffers);
        if (sourceCount == 0 && bufferCount == 0) {
            return;
        }
        long operationId = AudioNativeCloseDiagnostics.global().begin(sourceCount, bufferCount, System.nanoTime());
        PENDING.offer(new NativeResources(operationId, bedSources, objectSources, bedBuffers, objectBuffers));
        scheduleDrain();
    }

    static void drainNow() {
        synchronized (DRAIN_LOCK) {
            drainPending();
        }
    }

    static int pendingBatches() {
        return PENDING.size();
    }

    static void tick(long nowNanos) {
        if (PENDING.isEmpty()) {
            return;
        }
        long next = NEXT_RETRY_NANOS.get();
        if (nowNanos >= next && NEXT_RETRY_NANOS.compareAndSet(next, nowNanos + Math.max(1L, RETRY_NANOS))) {
            scheduleDrain();
        }
    }

    private static void scheduleDrain() {
        if (!PENDING.isEmpty() && DRAIN_SCHEDULED.compareAndSet(false, true)) {
            EXECUTOR.execute(() -> {
                boolean contextAvailable = false;
                try {
                    synchronized (DRAIN_LOCK) {
                        contextAvailable = MinecraftOpenAlContext.ensure("pending cleanup");
                        if (contextAvailable) {
                            drainPending();
                        } else {
                            for (NativeResources pending : PENDING) {
                                AudioNativeCloseDiagnostics.global().deferred(pending.operationId());
                            }
                        }
                    }
                } finally {
                    DRAIN_SCHEDULED.set(false);
                }
                if (contextAvailable && !PENDING.isEmpty()) {
                    scheduleDrain();
                }
            });
        }
    }

    private static void drainPending() {
        NativeResources pending;
        while ((pending = PENDING.poll()) != null) {
            delete(pending);
        }
    }

    private static void delete(NativeResources resources) {
        int sourceFailures = 0;
        int bufferFailures = 0;
        try {
            sourceFailures += deleteSources(resources.bedSources());
            sourceFailures += deleteSources(resources.objectSources());
            bufferFailures += deleteBuffers(resources.bedBuffers());
            bufferFailures += deleteBuffers(resources.objectBuffers());
        } finally {
            AudioNativeCloseDiagnostics.global().complete(resources.operationId(), System.nanoTime(),
                    sourceFailures, bufferFailures);
            if (sourceFailures > 0 || bufferFailures > 0) {
                LOGGER.warn("OpenAL native delete batch completed with item failures: op={} sources={} buffers={}",
                        resources.operationId(), sourceFailures, bufferFailures);
            }
        }
    }

    private static int deleteSources(int[] sources) {
        int failures = 0;
        if (sources != null) {
            for (int source : sources) {
                if (!deleteSource(source)) {
                    failures++;
                }
            }
        }
        return failures;
    }

    private static int deleteBuffers(int[][] buffers) {
        int failures = 0;
        if (buffers != null) {
            for (int[] group : buffers) {
                for (int buffer : group) {
                    if (!deleteBuffer(buffer)) {
                        failures++;
                    }
                }
            }
        }
        return failures;
    }

    private static boolean deleteSource(int source) {
        if (source == 0) {
            return true;
        }
        try {
            OpenALSpatialAudio.clearAlErrors();
            OpenALSpatialAudio.stopAndDelete(source);
            return AL10.alGetError() == AL10.AL_NO_ERROR;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static boolean deleteBuffer(int buffer) {
        if (buffer == 0) {
            return true;
        }
        try {
            OpenALSpatialAudio.clearAlErrors();
            AL10.alDeleteBuffers(buffer);
            return AL10.alGetError() == AL10.AL_NO_ERROR;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static int countSources(int[] sources) {
        return sources == null ? 0 : sources.length;
    }

    private static int countBuffers(int[][] buffers) {
        int count = 0;
        if (buffers != null) {
            for (int[] group : buffers) {
                count += group == null ? 0 : group.length;
            }
        }
        return count;
    }

    private record NativeResources(long operationId, int[] bedSources, int[] objectSources,
            int[][] bedBuffers, int[][] objectBuffers) {
    }
}
