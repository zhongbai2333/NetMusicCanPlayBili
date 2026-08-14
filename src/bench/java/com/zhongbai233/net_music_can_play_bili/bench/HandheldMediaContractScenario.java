package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldMediaProfile;
import com.zhongbai233.net_music_can_play_bili.client.PadHandheldMediaProfile;
import com.zhongbai233.net_music_can_play_bili.network.PadPlaybackSessionIds;

import java.util.UUID;

final class HandheldMediaContractScenario implements BenchClientScenario {
    private static final BenchMetricDescriptor CONTRACTS = new BenchMetricDescriptor(
            "ncpb.handheld.contracts", "count", MetricDirection.NEUTRAL);

    @Override
    public void setup(BenchClientContext context) {
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        var mp4 = MP4HandheldMediaProfile.INSTANCE.screenSpec();
        var pad = PadHandheldMediaProfile.INSTANCE.screenSpec();
        if (mp4.portraitWidth() != 256 || mp4.portraitHeight() != 448
                || mp4.landscapeWidth() != 448 || mp4.targetWidth() <= 0 || mp4.targetHeight() <= 0) {
            throw new AssertionError("MP4 media surface geometry contract changed: " + mp4);
        }
        if (pad.portraitWidth() != 448 || pad.portraitHeight() != 256
                || pad.landscapeWidth() != 256 || pad.targetWidth() <= 0 || pad.targetHeight() <= 0) {
            throw new AssertionError("Pad media surface geometry contract changed: " + pad);
        }
        UUID deviceId = UUID.randomUUID();
        UUID pointId = UUID.randomUUID();
        String sessionId = PadPlaybackSessionIds.create(deviceId, pointId, 7L).value();
        if (!PadPlaybackSessionIds.isPadSession(sessionId)
                || !PadPlaybackSessionIds.matches(sessionId, deviceId, pointId)
                || !pointId.equals(PadPlaybackSessionIds.pointId(sessionId))
                || PadPlaybackSessionIds.isPadSession(deviceId + "-broken")) {
            throw new AssertionError("Pad session identity contract failed for " + sessionId);
        }
        context.metrics().record(CONTRACTS, 3);
        return BenchClientStepResult.COMPLETE;
    }
}
