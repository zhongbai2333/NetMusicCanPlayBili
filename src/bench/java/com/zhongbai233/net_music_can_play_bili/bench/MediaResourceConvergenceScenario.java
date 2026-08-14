package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;

final class MediaResourceConvergenceScenario implements BenchClientScenario {
    private static final int MEASURE_TICKS = 40;
    private static final BenchMetricDescriptor VIDEO_CLOSE_ACTIVE = new BenchMetricDescriptor(
            "ncpb.video.close_active", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor AUDIO_CLOSE_ACTIVE = new BenchMetricDescriptor(
            "ncpb.openal.close_active", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor AUDIO_PENDING_BATCHES = new BenchMetricDescriptor(
            "ncpb.openal.pending_delete_batches", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor HTTP_ACTIVE = new BenchMetricDescriptor(
        "ncpb.http.active_requests", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor HTTP_CANCELS = new BenchMetricDescriptor(
        "ncpb.http.cancel_requests", "count", MetricDirection.NEUTRAL);
    private int ticks;

    @Override public void setup(BenchClientContext context) { ModernTurntableVideoClient.clear(); }
    @Override public BenchClientStepResult stabilize(BenchClientContext context) {
        return context.frames().sampleCount() >= 2 ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }
    @Override public BenchClientStepResult warmup(BenchClientContext context) { return BenchClientStepResult.COMPLETE; }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        var video = VideoCloseDiagnostics.global().snapshot(System.nanoTime());
        var audio = AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime());
        context.metrics().record(VIDEO_CLOSE_ACTIVE, video.activeOperations());
        context.metrics().record(AUDIO_CLOSE_ACTIVE, audio.activeOperations());
        context.metrics().record(AUDIO_PENDING_BATCHES, OpenALSpatialAudio.pendingNativeDeleteBatches());
        var http = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
        context.metrics().record(HTTP_ACTIVE, http.activeRequests());
        context.metrics().record(HTTP_CANCELS, http.cancelRequests());
        return ++ticks >= MEASURE_TICKS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        ConsoleConsumerLifecycleScenario.requireClean("resource convergence");
        if (VideoCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != 0
                || AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() != 0
                || OpenALSpatialAudio.pendingNativeDeleteBatches() != 0
                || HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime()).activeRequests() != 0) {
            throw new AssertionError("Native close operations did not converge");
        }
        for (MemoryResourceTracker.Category category : MemoryResourceTracker.Category.values()) {
            if (MemoryResourceTracker.usage(category).currentBytes() != 0L) {
                throw new AssertionError("Owned memory did not converge: " + category);
            }
        }
    }
}
