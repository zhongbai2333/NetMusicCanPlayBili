package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.bench.api.neoforge.client.BenchGuiSession;
import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;

final class EditorGuiLifecycleScenario implements BenchClientScenario {
    private static final int ROUNDS = 30;
    private static final BenchMetricDescriptor WIDGETS = new BenchMetricDescriptor(
            "ncpb.gui.widgets", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor OPEN_ROUNDS = new BenchMetricDescriptor(
            "ncpb.gui.open_rounds", "count", MetricDirection.NEUTRAL);
    private BenchGuiSession gui;
    private int round;

    @Override
    public void setup(BenchClientContext context) {
        context.minecraft().setScreen(new HolographicScreenConfigTestScreen());
        gui = context.automation().beginGuiSession(HolographicScreenConfigTestScreen.class);
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        return context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        if (!(context.minecraft().screen instanceof HolographicScreenConfigTestScreen)) {
            throw new AssertionError("Editor Screen did not remain open for a rendered tick");
        }
        int widgets = gui.snapshot().flattened().size();
        if (widgets < 4) {
            throw new AssertionError("Editor interaction tree unexpectedly small: " + widgets);
        }
        context.metrics().record(WIDGETS, widgets);
        context.metrics().record(OPEN_ROUNDS, ++round);
        if (round >= ROUNDS) {
            context.minecraft().setScreen(null);
            return BenchClientStepResult.COMPLETE;
        }
        context.minecraft().setScreen(new HolographicScreenConfigTestScreen());
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (round != ROUNDS || context.minecraft().screen != null || !gui.active()) {
            throw new AssertionError("GUI lifecycle did not converge: rounds=" + round);
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        context.minecraft().setScreen(null);
        if (gui != null) gui.close();
    }
}
