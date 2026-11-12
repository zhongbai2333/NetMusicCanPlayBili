package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureFlags;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;

import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Shared real-Bilibili video/audio stages and owned-memory helpers used by the real-media bench scenarios. */
final class RealMediaStages {
    private RealMediaStages() {
    }

    static Throwable unwrapCompletion(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static long[] currentOwnedBytesByCategory() {
        MemoryResourceTracker.Category[] categories = MemoryResourceTracker.Category.values();
        long[] current = new long[categories.length];
        for (int i = 0; i < categories.length; i++) {
            current[i] = MemoryResourceTracker.usage(categories[i]).currentBytes();
        }
        return current;
    }

    static long currentOwnedBytes() {
        return Arrays.stream(currentOwnedBytesByCategory()).sum();
    }

    static final class RealVideoStage {
        private final BiliApiClient.VideoStream stream;
        private final long decoderNv12Baseline;
        private final long pboBaseline;
        private final AtomicReference<Fmp4NativeVideoDecoder> decoder = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final AtomicBoolean loaded = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean terminatedNormally = new AtomicBoolean();
        private final Thread worker;
        private volatile long frameBytes;
        private volatile long uploadNanos;
        private volatile boolean directFrame;
        private volatile boolean yuvTextureObserved;
        private volatile boolean decoderNv12Observed;
        private volatile boolean pboObserved;

        private RealVideoStage(BiliApiClient.VideoStream stream, long[] memoryBaseline, long pboBaseline) {
            this.stream = stream;
            this.decoderNv12Baseline = memoryBaseline[MemoryResourceTracker.Category.DECODER_NV12.ordinal()];
            this.pboBaseline = pboBaseline;
            worker = NetMusicThreadFactory.daemonThread("RealMediaStage-video", this::run);
            worker.start();
        }

        static RealVideoStage start(BiliApiClient.VideoStream stream, long[] memoryBaseline, long pboBaseline) {
            return new RealVideoStage(stream, memoryBaseline, pboBaseline);
        }

        private void run() {
            Fmp4NativeVideoDecoder opened = null;
            try {
                opened = openDecoder();
                decoder.set(opened);
                try (Fmp4NativeVideoDecoder.DecodedFrame frame = opened.getNextDecodedFrame()) {
                    if (frame == null) {
                        throw new IOException("real Bilibili video decoder reached EOF before its first frame");
                    }
                    frameBytes = frame.byteLength();
                    directFrame = frame.buffer() != null;
                    decoderNv12Observed = MemoryResourceTracker
                            .usage(MemoryResourceTracker.Category.DECODER_NV12).currentBytes()
                            > decoderNv12Baseline;
                    uploadNanos = VideoBillboardPreview.uploadDecodedFrameSyncForBench(
                            frame, Math.max(1, stream.width()), Math.max(1, stream.height()));
                    if (uploadNanos < 0L) {
                        throw new IOException("real Bilibili decoded frame GPU upload failed");
                    }
                    VideoBillboardPreview.BenchUploadResources resources =
                            VideoBillboardPreview.benchUploadResources();
                    yuvTextureObserved = resources.yuvTextures();
                    pboObserved = resources.gpuPboBytes() > pboBaseline;
                    loaded.set(true);
                }
                while (!stopRequested.get()) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(10L);
                    } catch (InterruptedException interrupted) {
                        if (!stopRequested.get()) {
                            throw interrupted;
                        }
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Throwable error) {
                if (!stopRequested.get()) {
                    failure.compareAndSet(null, error);
                }
            } finally {
                // stop() interrupts the stage worker only to leave its hold loop. Do not let that expected
                // cancellation interrupt short-circuit decoder.close()/terminationFuture(), otherwise the
                // benchmark would report a lifecycle failure before the physical native barrier is observed.
                if (stopRequested.get()) {
                    Thread.interrupted();
                }
                if (opened != null) {
                    try {
                        opened.requestClose();
                        opened.close();
                        awaitExpectedTermination(opened);
                        terminatedNormally.set(true);
                    } catch (Throwable error) {
                        failure.compareAndSet(null, unwrapCompletion(error));
                    }
                }
                decoder.set(null);
                finished.set(true);
            }
        }

        private void awaitExpectedTermination(Fmp4NativeVideoDecoder opened) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20L);
            while (true) {
                if (stopRequested.get()) {
                    Thread.interrupted();
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    throw new java.util.concurrent.TimeoutException(
                            "real Bilibili video decoder termination timed out");
                }
                try {
                    opened.terminationFuture().get(remaining, TimeUnit.NANOSECONDS);
                    return;
                } catch (InterruptedException interrupted) {
                    if (!stopRequested.get()) {
                        throw interrupted;
                    }
                    // stop() may race with the transition from the hold loop into this barrier.
                    // Expected cancellation must not turn physical termination into a false failure.
                }
            }
        }

        private Fmp4NativeVideoDecoder openDecoder() throws IOException {
            IOException last = null;
            for (String hwaccel : VideoFeatureFlags.requestedHwaccelCandidates()) {
                try {
                    return new Fmp4NativeVideoDecoder(stream.baseUrl(), stream.codecId(),
                            Math.max(1, stream.width()), Math.max(1, stream.height()), 10_000, true,
                            Fmp4NativeVideoDecoder.OutputFormat.NV12, hwaccel, 0L, 0L, 30);
                } catch (IOException error) {
                    last = error;
                }
            }
            throw last != null ? last : new IOException("no native video hwaccel candidate was available");
        }

        void stop() {
            stopRequested.set(true);
            Fmp4NativeVideoDecoder opened = decoder.get();
            if (opened != null) {
                try {
                    opened.requestClose();
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            }
            worker.interrupt();
        }

        void throwIfFailed() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Real Bilibili video lifecycle stage failed: " + error, error);
            }
        }

        boolean loaded() {
            return loaded.get();
        }

        boolean finished() {
            return finished.get();
        }

        boolean terminatedNormally() {
            return terminatedNormally.get();
        }

        boolean directFrame() {
            return directFrame;
        }

        long frameBytes() {
            return frameBytes;
        }

        boolean yuvTextureObserved() {
            return yuvTextureObserved;
        }

        boolean decoderNv12Observed() {
            return decoderNv12Observed;
        }

        boolean pboObserved() {
            return pboObserved;
        }

        @Override
        public String toString() {
            return "RealVideoStage[loaded=" + loaded + ", finished=" + finished + ", terminated="
                    + terminatedNormally + ", frameBytes=" + frameBytes + ", uploadNanos=" + uploadNanos
                    + ", direct=" + directFrame + ", yuv=" + yuvTextureObserved + ", pbo=" + pboObserved
                    + ", decoderNv12=" + decoderNv12Observed + ", failure=" + failure + ']';
        }
    }

    static final class RealAudioStage {
        private static final int READ_BUFFER_BYTES = 32 * 1024;
        private final AtomicReference<AudioInputStream> stream = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<HttpAudioStreamHandler.RegisteredRequest> registered =
                new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final AtomicBoolean streamCloseCompleted = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final Thread reader;

        private RealAudioStage(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId,
                long totalMillis) {
            reader = NetMusicThreadFactory.daemonThread("RealMediaStage-audio-" + sessionId.value(),
                    () -> run(mediaUrl, ownerId, sessionId, totalMillis));
            reader.start();
        }

        static RealAudioStage start(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId,
                long totalMillis) {
            return new RealAudioStage(mediaUrl, ownerId, sessionId, totalMillis);
        }

        private void run(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId, long totalMillis) {
            try {
                PlaybackRequest request = PlaybackRequest.now(mediaUrl, null, sessionId.value(), 0L,
                        Math.max(1L, totalMillis), ownerId, null);
                HttpAudioStreamHandler.RegisteredRequest requestUrl =
                        HttpAudioStreamHandler.registerRequest(request);
                registered.set(requestUrl);
                if (cancelled.get()) {
                    requestUrl.requestToken().ifPresent(HttpAudioStreamHandler::cancelRequest);
                    return;
                }
                AudioInputStream opened = new HttpAudioStreamHandler().handle(
                        URI.create(requestUrl.url()).toURL());
                stream.set(opened);
                byte[] buffer = new byte[READ_BUFFER_BYTES];
                while (!cancelled.get() && opened.read(buffer, 0, buffer.length) >= 0) {
                    // Decoder/OpenAL backpressure intentionally remains on this daemon worker.
                }
            } catch (Throwable error) {
                if (!cancelled.get()) {
                    failure.compareAndSet(null, error);
                }
            } finally {
                closeStream();
                finished.set(true);
            }
        }

        void stop() {
            cancelled.set(true);
            if (!stopRequested.compareAndSet(false, true)) {
                return;
            }
            reader.interrupt();
            NetMusicThreadFactory.daemonThread("RealMediaStage-audio-close", () -> {
                HttpAudioStreamHandler.RegisteredRequest request = registered.get();
                if (request != null) {
                    request.requestToken().ifPresent(HttpAudioStreamHandler::cancelRequest);
                }
                closeStream();
            }).start();
        }

        void throwIfFailed() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("Real Bilibili audio lifecycle stage failed: " + error, error);
            }
        }

        boolean finished() {
            return finished.get();
        }

        boolean streamClosed() {
            return streamCloseCompleted.get();
        }

        private void closeStream() {
            AudioInputStream value = stream.get();
            if (value == null) {
                return;
            }
            try {
                value.close();
                streamCloseCompleted.set(true);
            } catch (IOException error) {
                if (!cancelled.get()) {
                    failure.compareAndSet(null, error);
                }
            }
        }

        @Override
        public String toString() {
            return "RealAudioStage[finished=" + finished + ", streamClosed=" + streamClosed()
                    + ", failure=" + failure + ']';
        }
    }
}
