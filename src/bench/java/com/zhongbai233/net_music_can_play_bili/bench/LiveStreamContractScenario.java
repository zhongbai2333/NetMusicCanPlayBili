package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveRoomInput;
import com.zhongbai233.net_music_can_play_bili.client.sync.LiveRoomMetadataRegistry;
import com.zhongbai233.net_music_can_play_bili.media.stream.LiveReconnectPolicy;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

final class LiveStreamContractScenario implements BenchClientScenario {
    private static final BenchMetricDescriptor CONTRACTS = new BenchMetricDescriptor(
            "ncpb.live.contracts", "count", MetricDirection.NEUTRAL);

    @Override
    public void setup(BenchClientContext context) {
        LiveRoomMetadataRegistry.clear();
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
        String roomId = "67373";
        String placeholder = BiliLiveRoomInput.placeholderUrl(roomId);
        if (!roomId.equals(BiliLiveRoomInput.parseRoomId("live:" + roomId))
                || !roomId.equals(BiliLiveRoomInput.parseExplicitRoomId(
                        "https://live.bilibili.com/" + roomId + "?live_from=bench"))
                || !roomId.equals(BiliLiveRoomInput.roomIdFromPlaceholder(placeholder))
                || !BiliLiveRoomInput.parseExplicitRoomId(roomId).isEmpty()) {
            throw new AssertionError("Bilibili live-room input contract failed");
        }

        LiveReconnectPolicy reconnect = new LiveReconnectPolicy(3, 100L, 400L, 1_000L);
        long[] expected = { 100L, 200L, 400L, LiveReconnectPolicy.GIVE_UP };
        for (long delay : expected) {
            long actual = reconnect.onStreamEnded(50L);
            if (actual != delay) {
                throw new AssertionError("Live reconnect backoff mismatch: expected=" + delay
                        + " actual=" + actual);
            }
        }
        if (reconnect.onStreamEnded(2_000L) != 100L || reconnect.consecutiveFailures() != 0) {
            throw new AssertionError("Healthy live stream did not reset reconnect backoff");
        }

        PlaybackSessionId session = PlaybackSessionId.of("bench-live-" + UUID.randomUUID());
        LiveRoomMetadataRegistry.SourceKey source = new LiveRoomMetadataRegistry.SourceKey(3, 70, 5);
        LiveRoomMetadataRegistry.publish(source, session, roomId, " Bench title ", "Music", "MV", 1);
        var snapshot = LiveRoomMetadataRegistry.snapshot(source, roomId).orElseThrow(
                () -> new AssertionError("Live metadata was not published"));
        if (!"Bench title".equals(snapshot.title())
                || LiveRoomMetadataRegistry.snapshot(source, "999").isPresent()
                || !LiveRoomMetadataRegistry.remove(source, session)
                || LiveRoomMetadataRegistry.size() != 0) {
            throw new AssertionError("Live metadata ownership contract failed");
        }

        com.zhongbai233.net_music_can_play_bili.client.MediaConsumerRegistry<BlockPos> consumers =
                new com.zhongbai233.net_music_can_play_bili.client.MediaConsumerRegistry<>();
        BlockPos sourceA = new BlockPos(1, 2, 3);
        BlockPos sourceB = new BlockPos(4, 5, 6);
        BlockPos consumer = new BlockPos(7, 8, 9);
        consumers.register(sourceA, consumer);
        consumers.register(sourceB, consumer);
        if (!consumers.consumersFor(sourceA).isEmpty()
                || !consumers.consumersFor(sourceB).equals(List.of(consumer))) {
            throw new AssertionError("Live consumer rebind contract failed");
        }
        consumers.clear();
        context.metrics().record(CONTRACTS, 4);
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public void teardown(BenchClientContext context) {
        LiveRoomMetadataRegistry.clear();
    }
}
