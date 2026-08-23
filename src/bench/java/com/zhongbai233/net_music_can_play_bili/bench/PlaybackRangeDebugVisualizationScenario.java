package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioEndpointIndex;
import com.zhongbai233.net_music_can_play_bili.client.debug.PlaybackDebugMode;
import com.zhongbai233.net_music_can_play_bili.client.debug.PlaybackRangeDebugRenderer;
import com.zhongbai233.net_music_can_play_bili.client.debug.VideoPlaybackDebugRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoScreenOcclusionBenchProbe;
import com.zhongbai233.net_music_can_play_bili.network.AudioEndpointSnapshotPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;

/** Exercises actual world-geometry and HUD submission for the playback debug view. */
final class PlaybackRangeDebugVisualizationScenario implements BenchClientScenario {
    private static final BenchMetricDescriptor FRAMES = new BenchMetricDescriptor(
            "ncpb.playback_debug.frames", "frames", MetricDirection.HIGHER_IS_BETTER);
    private int ticks;

    @Override
    public void setup(BenchClientContext context) {
        BlockPos source = context.player().blockPosition().offset(4, 0, 0);
        BlockPos endpoint = source.offset(3, 0, 0);
        ClientAudioEndpointIndex.accept(new AudioEndpointSnapshotPacket(
                UUID.fromString("40000000-0000-0000-0000-000000000001"), source,
                List.of(new AudioEndpointSnapshotPacket.Endpoint(
                        UUID.fromString("50000000-0000-0000-0000-000000000001"), endpoint,
                        0, 1.0F, false, 64.0F, 1L))));
        PlaybackRangeDebugRenderer.setEnabled(true);
        VideoPlaybackDebugRenderer.setEnabled(true);
        if (PlaybackRangeDebugRenderer.describe().stream().noneMatch(line -> line.contains("端点=1"))) {
            throw new AssertionError("Playback debug snapshot did not expose the injected endpoint");
        }
        PlaybackRangeDebugRenderer.setEnabled(false);
        if (VideoPlaybackDebugRenderer.mode() != PlaybackDebugMode.BOTH
                || VideoPlaybackDebugRenderer.describe().stream().noneMatch(line -> line.contains("视频调试模式=BOTH"))) {
            throw new AssertionError("Video debug must remain enabled when audio debug is off");
        }
        PlaybackRangeDebugRenderer.setEnabled(true);
        PlaybackRangeDebugRenderer.setMode(PlaybackDebugMode.UI);
        VideoPlaybackDebugRenderer.setMode(PlaybackDebugMode.RANGE);
        if (!PlaybackRangeDebugRenderer.hudEnabled() || PlaybackRangeDebugRenderer.rangeEnabled()
                || VideoPlaybackDebugRenderer.hudEnabled() || !VideoPlaybackDebugRenderer.rangeEnabled()) {
            throw new AssertionError("Audio/video debug HUD and range modes must remain independent");
        }
        PlaybackRangeDebugRenderer.setMode(PlaybackDebugMode.BOTH);
        VideoPlaybackDebugRenderer.setMode(PlaybackDebugMode.BOTH);
        if (!VideoScreenOcclusionBenchProbe.blocksView(Blocks.STONE.defaultBlockState())
                || VideoScreenOcclusionBenchProbe.blocksView(Blocks.GLASS.defaultBlockState())
                || VideoScreenOcclusionBenchProbe.blocksView(Blocks.TINTED_GLASS.defaultBlockState())
                || VideoScreenOcclusionBenchProbe.blocksView(Blocks.OAK_SLAB.defaultBlockState())) {
            throw new AssertionError(
                    "Video occlusion must keep opaque full cubes and ignore transparent or partial blocks");
        }
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        return context.frames().sampleCount() >= 3
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        ticks++;
        context.metrics().record(FRAMES, context.frames().sampleCount());
        return ticks >= 5 ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (!PlaybackRangeDebugRenderer.enabled()
                || !VideoPlaybackDebugRenderer.enabled()
                || PlaybackRangeDebugRenderer.describe().stream().noneMatch(line -> line.contains("端点=1"))) {
            throw new AssertionError("Playback range debug visualization did not remain active");
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        PlaybackRangeDebugRenderer.setEnabled(false);
        VideoPlaybackDebugRenderer.setEnabled(false);
        ClientAudioEndpointIndex.clear();
    }
}
