package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requirePcmQuality;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;

import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class RealMp3SeekScenario implements BenchClientScenario {
    private static final long FIRST_OFFSET_MILLIS = 5_000L;
    private static final long SECOND_OFFSET_MILLIS = 12_000L;
    private static final long TOTAL_MILLIS = 360_000L;
    private static final BenchMetricDescriptor MEDIA_MILLIS = new BenchMetricDescriptor(
            "ncpb.real_mp3.media_millis", "milliseconds", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor FED_MILLIS = new BenchMetricDescriptor(
            "ncpb.real_mp3.fed_millis", "milliseconds", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor PCM_PEAK = new BenchMetricDescriptor(
            "ncpb.real_mp3.pcm_peak", "ratio", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor PCM_RMS = new BenchMetricDescriptor(
            "ncpb.real_mp3.pcm_rms", "ratio", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor PCM_CLIPPED = new BenchMetricDescriptor(
            "ncpb.real_mp3.pcm_clipped_ratio", "ratio", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor ACTIVE_OUTPUTS = new BenchMetricDescriptor(
            "ncpb.real_mp3.active_outputs", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor PENDING_NATIVE = new BenchMetricDescriptor(
            "ncpb.real_mp3.pending_native_deletes", "count", MetricDirection.LOWER_IS_BETTER);

    private final AudioStreamProperties.RealMp3Bench properties = AudioStreamProperties.realMp3Bench();
    private final PlaybackSessionId firstSession = PlaybackSessionId.of("bench-real-mp3-first");
    private final PlaybackSessionId secondSession = PlaybackSessionId.of("bench-real-mp3-second");
    private RealMp3Stage first;
    private RealMp3Stage second;
    private UUID ownerId;
    private StereoOpenALHandler.LifecycleSnapshot lifecycleBaseline;
    private long audioStagingBaseline;
    private StereoOpenALHandler.PcmQuality latestPcm = emptyPcm();
    private StereoOpenALHandler.PcmQuality firstPcm = emptyPcm();
    private StereoOpenALHandler.PcmQuality secondPcm = emptyPcm();
    private int phase;
    private boolean cleanupRequested;

    @Override
    public void setup(BenchClientContext context) {
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
        ownerId = context.player().getUUID();
        ClientAudioOutputRegistry.setOwnerVolume(ownerId, 1.0F);
        lifecycleBaseline = StereoOpenALHandler.lifecycleSnapshot();
        audioStagingBaseline = MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING)
                .currentBytes();
        first = RealMp3Stage.start(properties.url(), ownerId, firstSession, FIRST_OFFSET_MILLIS);
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        return context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        first.throwIfFailed();
        if (second != null) {
            second.throwIfFailed();
        }
        ClientAudioOutputRegistry.updatePositions(new float[] {
                (float) context.player().getX(), (float) context.player().getEyeY(),
                (float) context.player().getZ()
        });

        ClientAudioOutputRegistry.AudioTimeline timeline = ClientAudioOutputRegistry.getOwnerAudioTimeline(ownerId);
        StereoOpenALHandler.DiagnosticSnapshot output = ClientAudioOutputRegistry.getOwnerStereoSnapshot(ownerId)
                .orElse(null);
        if (output != null && output.firstPcm().samples() > 0L) {
            latestPcm = output.firstPcm();
        }
        record(context, timeline, latestPcm);

        if (phase == 0 && ready(timeline, output, firstSession, FIRST_OFFSET_MILLIS)) {
            requireHealthy("first seek", timeline, output, firstSession, FIRST_OFFSET_MILLIS);
            firstPcm = output.firstPcm();
            second = RealMp3Stage.start(properties.url(), ownerId, secondSession, SECOND_OFFSET_MILLIS);
            phase = 1;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 1 && ready(timeline, output, secondSession, SECOND_OFFSET_MILLIS)) {
            requireHealthy("replacement seek", timeline, output, secondSession, SECOND_OFFSET_MILLIS);
            secondPcm = output.firstPcm();
            StereoOpenALHandler.LifecycleSnapshot lifecycle = StereoOpenALHandler.lifecycleSnapshot();
            if (lifecycle.instancesCreated() < lifecycleBaseline.instancesCreated() + 2L
                    || lifecycle.cleanupsStarted() < lifecycleBaseline.cleanupsStarted() + 1L) {
                return BenchClientStepResult.CONTINUE;
            }
            first.stop();
            phase = 2;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 2 && first.finished()) {
            if (!first.streamClosed()) {
                throw new AssertionError("Replaced MP3 stream did not close");
            }
            if (!cleanupRequested) {
                cleanupRequested = true;
                second.stop();
                ClientAudioOutputRegistry.cleanup();
                HttpAudioStreamHandler.closeModernStreams();
                return BenchClientStepResult.CONTINUE;
            }
            OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());
            if (resourcesConverged()) {
                phase = 3;
                return BenchClientStepResult.COMPLETE;
            }
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (phase != 3 || !resourcesConverged()) {
            throw new AssertionError("Real MP3/OpenAL resources did not converge: lifecycle="
                    + StereoOpenALHandler.lifecycleSnapshot() + " audio="
                    + AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()) + " pendingNative="
                    + OpenALSpatialAudio.pendingNativeDeleteBatches());
        }
        StereoOpenALHandler.LifecycleSnapshot lifecycle = StereoOpenALHandler.lifecycleSnapshot();
        if (lifecycle.instancesCreated() != lifecycleBaseline.instancesCreated() + 2L
                || lifecycle.cleanupsStarted() != lifecycleBaseline.cleanupsStarted() + 2L
                || lifecycle.cleanupsCompleted() != lifecycleBaseline.cleanupsCompleted() + 2L) {
            throw new AssertionError("Each real MP3 output must be cleaned exactly once: baseline="
                    + lifecycleBaseline + " final=" + lifecycle);
        }
        if (firstPcm.samples() == 0L || secondPcm.samples() == 0L) {
            throw new AssertionError("Both real MP3 seek stages must retain scalar PCM verification results");
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        if (first != null) {
            first.stop();
        }
        if (second != null) {
            second.stop();
        }
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
    }

    private boolean resourcesConverged() {
        StereoOpenALHandler.LifecycleSnapshot lifecycle = StereoOpenALHandler.lifecycleSnapshot();
        return first.finished() && second != null && second.finished()
                && !ClientAudioOutputRegistry.isActive()
                && lifecycle.activeInstances() == lifecycleBaseline.activeInstances()
                && lifecycle.cleanupsCompleted() >= lifecycleBaseline.cleanupsCompleted() + 2L
                && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                && MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING).currentBytes()
                        == audioStagingBaseline;
    }

    private static boolean ready(ClientAudioOutputRegistry.AudioTimeline timeline,
            StereoOpenALHandler.DiagnosticSnapshot output, PlaybackSessionId sessionId, long minimumMillis) {
        return output != null && output.started() && output.firstPcm().samples() > 0L
                && timeline.playbackSessionId().filter(sessionId::equals).isPresent()
                && timeline.fedMillis() >= minimumMillis;
    }

    private static void requireHealthy(String phase, ClientAudioOutputRegistry.AudioTimeline timeline,
            StereoOpenALHandler.DiagnosticSnapshot output, PlaybackSessionId sessionId, long minimumMillis) {
        if (!ready(timeline, output, sessionId, minimumMillis)) {
            throw new AssertionError("Real MP3 output was not ready during " + phase + ": timeline=" + timeline
                    + " output=" + output);
        }
        if (timeline.fedMillis() > minimumMillis + 60_000L || timeline.mainMillis() < minimumMillis - 1_000L) {
            throw new AssertionError("Real MP3 seek started outside the target window during " + phase
                    + ": target=" + minimumMillis + " timeline=" + timeline);
        }
        requirePcmQuality(phase, output.firstPcm());
    }

    private static void record(BenchClientContext context, ClientAudioOutputRegistry.AudioTimeline timeline,
            StereoOpenALHandler.PcmQuality pcm) {
        context.metrics().record(MEDIA_MILLIS, Math.max(0L, timeline.mainMillis()));
        context.metrics().record(FED_MILLIS, Math.max(0L, timeline.fedMillis()));
        context.metrics().record(PCM_PEAK, pcm.peak());
        context.metrics().record(PCM_RMS, pcm.rms());
        context.metrics().record(PCM_CLIPPED, pcm.clippedRatio());
        context.metrics().record(ACTIVE_OUTPUTS, StereoOpenALHandler.lifecycleSnapshot().activeInstances());
        context.metrics().record(PENDING_NATIVE, OpenALSpatialAudio.pendingNativeDeleteBatches());
    }

    private static StereoOpenALHandler.PcmQuality emptyPcm() {
        return new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D);
    }

    private static final class RealMp3Stage {
        private static final int READ_BUFFER_BYTES = 32 * 1024;
        private final AtomicReference<AudioInputStream> stream = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<HttpAudioStreamHandler.RegisteredRequest> registered = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final Thread reader;

        private RealMp3Stage(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId, long offsetMillis) {
            reader = NetMusicThreadFactory.daemonThread("RealMp3SeekBench-" + sessionId.value(),
                    () -> run(mediaUrl, ownerId, sessionId, offsetMillis));
            reader.start();
        }

        static RealMp3Stage start(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId, long offsetMillis) {
            return new RealMp3Stage(mediaUrl, ownerId, sessionId, offsetMillis);
        }

        private void run(String mediaUrl, UUID ownerId, PlaybackSessionId sessionId, long offsetMillis) {
            try {
                PlaybackRequest request = PlaybackRequest.now(mediaUrl, null, sessionId.value(), offsetMillis,
                        TOTAL_MILLIS, ownerId, null);
                HttpAudioStreamHandler.RegisteredRequest requestUrl = HttpAudioStreamHandler.registerRequest(request);
                registered.set(requestUrl);
                if (cancelled.get()) {
                    requestUrl.requestToken().ifPresent(HttpAudioStreamHandler::cancelRequest);
                    return;
                }
                AudioInputStream opened = new HttpAudioStreamHandler().handle(URI.create(requestUrl.url()).toURL());
                stream.set(opened);
                if (!(opened instanceof OpenALTappedAudioInputStream)) {
                    throw new IOException("real MP3 did not enter the modern OpenAL fallback pipeline: "
                            + opened.getClass().getName());
                }
                byte[] buffer = new byte[READ_BUFFER_BYTES];
                while (!cancelled.get() && opened.read(buffer, 0, buffer.length) >= 0) {
                    // Decoding and queue backpressure intentionally stay on this daemon worker.
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
            NetMusicThreadFactory.daemonThread("RealMp3SeekBench-close", () -> {
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
                throw new AssertionError("Real MP3 stage failed", error);
            }
        }

        boolean finished() {
            return finished.get();
        }

        boolean streamClosed() {
            AudioInputStream value = stream.get();
            return value instanceof OpenALTappedAudioInputStream tapped && tapped.isClosed();
        }

        private void closeStream() {
            AudioInputStream value = stream.get();
            if (value == null) {
                return;
            }
            try {
                value.close();
            } catch (IOException error) {
                if (!cancelled.get()) {
                    failure.compareAndSet(null, error);
                }
            }
        }
    }
}
