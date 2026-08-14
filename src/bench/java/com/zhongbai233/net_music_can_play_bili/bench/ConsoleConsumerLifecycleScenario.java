package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import net.minecraft.core.BlockPos;

final class ConsoleConsumerLifecycleScenario implements BenchClientScenario {
    private static final int ROUNDS = 100;
    private static final BlockPos SOURCE = new BlockPos(0, 64, 0);
    private static final BenchMetricDescriptor CONSUMERS = new BenchMetricDescriptor(
            "ncpb.console.consumers", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor VIDEO_INSTANCES = new BenchMetricDescriptor(
            "ncpb.video.instances", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor PENDING_REQUESTS = new BenchMetricDescriptor(
            "ncpb.video.pending_requests", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor MEMORY_BYTES = new BenchMetricDescriptor(
            "ncpb.memory.current_bytes", "bytes", MetricDirection.LOWER_IS_BETTER);

    private int round;

    @Override
    public void setup(BenchClientContext context) {
        ModernTurntableVideoClient.clear();
        requireClean("setup");
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        return context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        exerciseRound(-1);
        requireClean("warmup");
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        exerciseRound(round);
        record(context);
        return ++round >= ROUNDS ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (round != ROUNDS) {
            throw new AssertionError("Expected " + ROUNDS + " lifecycle rounds, got " + round);
        }
        requireClean("verify");
    }

    @Override
    public void teardown(BenchClientContext context) {
        ModernTurntableVideoClient.clear();
    }

    private static void exerciseRound(int index) {
        BlockPos first = new BlockPos(index * 2 + 1, 64, 1);
        BlockPos second = new BlockPos(index * 2 + 2, 64, 1);
        ModernTurntableVideoClient.registerControlConsoleConsumer(SOURCE, first, 116);
        ModernTurntableVideoClient.registerControlConsoleConsumer(SOURCE, second, 116);
        assertConsumers(2, "both consumers attached");
        ModernTurntableVideoClient.unregisterControlConsoleConsumer(first);
        assertConsumers(1, "shared consumer remains");
        ModernTurntableVideoClient.unregisterControlConsoleConsumer(second);
        assertConsumers(0, "last consumer detached");
    }

    private static void record(BenchClientContext context) {
        ModernTurntableVideoClient.VideoLifecycleDiagnostics lifecycle =
                ModernTurntableVideoClient.videoLifecycleDiagnostics();
        context.metrics().record(CONSUMERS, lifecycle.controlConsoleConsumers());
        context.metrics().record(VIDEO_INSTANCES, lifecycle.resources().instances());
        context.metrics().record(PENDING_REQUESTS, lifecycle.pendingRequests());
        for (MemoryResourceTracker.Category category : MemoryResourceTracker.Category.values()) {
            context.metrics().record(MEMORY_BYTES, MemoryResourceTracker.usage(category).currentBytes());
        }
    }

    static void requireClean(String phase) {
        ModernTurntableVideoClient.VideoLifecycleDiagnostics lifecycle =
                ModernTurntableVideoClient.videoLifecycleDiagnostics();
        if (lifecycle.controlConsoleConsumers() != 0 || lifecycle.activeRequests() != 0
                || lifecycle.pendingRequests() != 0 || lifecycle.resources().instances() != 0
                || lifecycle.resources().pendingLoading() != 0 || lifecycle.resources().pendingFailure() != 0) {
            throw new AssertionError("Video lifecycle not clean during " + phase + ": " + lifecycle);
        }
    }

    private static void assertConsumers(int expected, String phase) {
        int actual = ModernTurntableVideoClient.videoLifecycleDiagnostics().controlConsoleConsumers();
        if (actual != expected) {
            throw new AssertionError(phase + ": expected " + expected + " consumers, got " + actual);
        }
    }
}
