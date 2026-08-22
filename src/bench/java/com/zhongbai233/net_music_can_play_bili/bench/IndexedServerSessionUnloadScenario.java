package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.blockentity.IndexedBlockPlaybackSessionManager;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real integrated-server proof that session routing does not ticket the source chunk. */
final class IndexedServerSessionUnloadScenario implements BenchClientScenario {
    private static final BenchMetricDescriptor UNLOADED_TICKS = new BenchMetricDescriptor(
            "ncpb.indexed_audio.unloaded_session_ticks", "ticks", MetricDirection.HIGHER_IS_BETTER);
    private final PlaybackSourceId sourceId = PlaybackSourceId.of(
            UUID.fromString("30000000-0000-0000-0000-000000000001"));
    private final AtomicBoolean published = new AtomicBoolean();
    private final AtomicBoolean stillUnloaded = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private BlockPos sourcePos;
    private UUID playerId;
    private int ticks;

    @Override
    public void setup(BenchClientContext context) {
        playerId = context.player().getUUID();
        sourcePos = context.player().blockPosition().offset(512, 0, 0).immutable();
        var server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new AssertionError("Integrated server is unavailable");
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || !(player.level() instanceof ServerLevel level)) {
                    throw new IllegalStateException("Integrated server player is unavailable");
                }
                if (level.hasChunk(Math.floorDiv(sourcePos.getX(), 16), Math.floorDiv(sourcePos.getZ(), 16))) {
                    throw new AssertionError("Far source chunk unexpectedly started loaded");
                }
                IndexedBlockPlaybackSessionManager.publishAndSync(level, level, sourceId, sourcePos,
                        "https://example.invalid/indexed-unloaded.mp3", "bench:indexed-unloaded",
                        "indexed unloaded repeat", "bench-indexed-server-unloaded", 0L, 1_000L, 1, true);
                if (!IndexedBlockPlaybackSessionManager.contains(sourceId)) {
                    throw new AssertionError("Server session index rejected an unloaded source");
                }
                published.set(true);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        throwIfFailed();
        return published.get() ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        throwIfFailed();
        ticks++;
        if (ticks == 45) {
            var server = context.minecraft().getSingleplayerServer();
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && player.level() instanceof ServerLevel level) {
                    stillUnloaded.set(!level.hasChunk(Math.floorDiv(sourcePos.getX(), 16),
                            Math.floorDiv(sourcePos.getZ(), 16))
                            && IndexedBlockPlaybackSessionManager.contains(sourceId));
                }
            });
        }
        context.metrics().record(UNLOADED_TICKS, ticks);
        return ticks >= 50 && stillUnloaded.get() ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        if (!stillUnloaded.get()) {
            throw new AssertionError("Indexed session did not survive without loading its source chunk");
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        var server = context.minecraft().getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && player.level() instanceof ServerLevel level) {
                    IndexedBlockPlaybackSessionManager.remove(level, sourceId);
                }
            });
        }
    }

    private void throwIfFailed() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("Indexed unloaded server session failed: " + error, error);
        }
    }
}
