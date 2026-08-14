package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.DeterministicVideoUploadWorkload;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;

import java.util.Arrays;

final class DeterministicVideoUploadScenario implements BenchClientScenario {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;
    private static final int FRAMES_PER_FORMAT = 30;
    private static final VideoBillboardPreview.BenchUploadFormat[] FORMATS =
            VideoBillboardPreview.BenchUploadFormat.values();
    private static final BenchMetricDescriptor UPLOAD_LATENCY = new BenchMetricDescriptor(
            "ncpb.video.upload_latency", "ms", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor UPLOAD_BYTES = new BenchMetricDescriptor(
            "ncpb.video.upload_bytes", "bytes", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor UPLOAD_P95 = new BenchMetricDescriptor(
            "ncpb.video.upload_p95", "ms", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor UPLOAD_P99 = new BenchMetricDescriptor(
            "ncpb.video.upload_p99", "ms", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor STAGING_BYTES = new BenchMetricDescriptor(
            "ncpb.video.texture_staging_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor PBO_BYTES = new BenchMetricDescriptor(
            "ncpb.video.gpu_pbo_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);

    private final long[][] uploadNanos = new long[FORMATS.length][FRAMES_PER_FORMAT];
    private long baselineStaging;
    private long baselinePbo;
    private long peakStagingDelta;
    private long peakPboDelta;
    private int formatIndex;
    private int frameIndex;
    private boolean released;

    @Override
    public void setup(BenchClientContext context) {
        ModernTurntableVideoClient.clear();
        ConsoleConsumerLifecycleScenario.requireClean("deterministic upload setup");
        VideoBillboardPreview.releaseBenchUploadResources();
        var resources = VideoBillboardPreview.benchUploadResources();
        baselineStaging = resources.textureStagingBytes();
        baselinePbo = resources.gpuPboBytes();
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        return context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        upload(VideoBillboardPreview.BenchUploadFormat.RGBA, -1);
        VideoBillboardPreview.releaseBenchUploadResources();
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        VideoBillboardPreview.BenchUploadFormat format = FORMATS[formatIndex];
        byte[] frame = DeterministicVideoUploadWorkload.frame(format, WIDTH, HEIGHT, frameIndex);
        long elapsedNanos = VideoBillboardPreview.uploadFrameOnClientThreadForBench(
                format, frame, WIDTH, HEIGHT);
        if (elapsedNanos < 0L) {
            throw new AssertionError("GPU upload failed: format=" + format + ", frame=" + frameIndex);
        }
        uploadNanos[formatIndex][frameIndex] = elapsedNanos;
        context.metrics().record(UPLOAD_LATENCY, elapsedNanos / 1_000_000.0D);
        context.metrics().record(UPLOAD_BYTES, frame.length);
        var resources = VideoBillboardPreview.benchUploadResources();
        long stagingDelta = Math.max(0L, resources.textureStagingBytes() - baselineStaging);
        long pboDelta = Math.max(0L, resources.gpuPboBytes() - baselinePbo);
        peakStagingDelta = Math.max(peakStagingDelta, stagingDelta);
        peakPboDelta = Math.max(peakPboDelta, pboDelta);
        context.metrics().record(STAGING_BYTES, stagingDelta);
        context.metrics().record(PBO_BYTES, pboDelta);

        frameIndex++;
        if (frameIndex < FRAMES_PER_FORMAT) {
            return BenchClientStepResult.CONTINUE;
        }
        recordPercentiles(context, uploadNanos[formatIndex]);
        frameIndex = 0;
        formatIndex++;
        if (formatIndex < FORMATS.length) {
            return BenchClientStepResult.CONTINUE;
        }
        VideoBillboardPreview.releaseBenchUploadResources();
        released = true;
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (!released || formatIndex != FORMATS.length) {
            throw new AssertionError("Deterministic upload workload did not finish all formats");
        }
        if (peakStagingDelta <= 0L) {
            throw new AssertionError("YUV upload did not allocate tracked texture staging memory");
        }
        if (peakPboDelta <= 0L) {
            throw new AssertionError("NV12 upload did not allocate tracked PBO memory");
        }
        var resources = VideoBillboardPreview.benchUploadResources();
        if (resources.rgbaTexture() || resources.yuvTextures()
                || resources.textureStagingBytes() != baselineStaging
                || resources.gpuPboBytes() != baselinePbo) {
            throw new AssertionError("GPU upload resources did not return to baseline: " + resources);
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        VideoBillboardPreview.releaseBenchUploadResources();
    }

    private static long upload(VideoBillboardPreview.BenchUploadFormat format, int frameIndex) {
        byte[] frame = DeterministicVideoUploadWorkload.frame(format, WIDTH, HEIGHT, frameIndex);
        return VideoBillboardPreview.uploadFrameOnClientThreadForBench(format, frame, WIDTH, HEIGHT);
    }

    private static void recordPercentiles(BenchClientContext context, long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        context.metrics().record(UPLOAD_P95, percentile(sorted, 0.95D) / 1_000_000.0D);
        context.metrics().record(UPLOAD_P99, percentile(sorted, 0.99D) / 1_000_000.0D);
    }

    private static long percentile(long[] sorted, double quantile) {
        int index = Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * quantile) - 1);
        return sorted[Math.max(0, index)];
    }
}
