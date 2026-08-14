package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requireBlockEntity;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.bench.api.neoforge.client.BenchGuiSession;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.Config;
import com.zhongbai233.net_music_can_play_bili.client.WhitelistCsvExportClient;
import com.zhongbai233.net_music_can_play_bili.gui.WhitelistReviewScreen;
import com.zhongbai233.net_music_can_play_bili.gui.WhitelistPreviewScreen;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistPreviewPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistCsvExportPacket;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class WhitelistManagementLifecycleScenario implements BenchClientScenario {
    private static final String REVIEW_ROOM = "8178490";
    private static final String DENIED_ROOM = "9000000000000001";
    private static final String EXPORT_FILE = "ncpb-whitelist-bench.csv";
    private static final BenchMetricDescriptor OPERATIONS = new BenchMetricDescriptor(
            "ncpb.whitelist.operations", "count", MetricDirection.NEUTRAL);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<WhitelistReviewPacket> reviewPayload = new AtomicReference<>();
    private final AtomicReference<String> exportedCsv = new AtomicReference<>();
    private final AtomicBoolean setupComplete = new AtomicBoolean();
    private boolean originalWhitelistEnabled;
    private boolean reviewRoomAddedByBench;
    private UUID playerId;
    private BlockPos livePos;
    private Path exportPath;
    private BenchGuiSession gui;
    private long openedAtFrame;
    private int phase;
    private int operations;

    @Override
    public void setup(BenchClientContext context) {
        originalWhitelistEnabled = Config.enableLinkWhitelist;
        Config.enableLinkWhitelist = true;
        playerId = context.player().getUUID();
        livePos = context.player().blockPosition().offset(2, 0, 2).immutable();
        exportPath = context.minecraft().gameDirectory.toPath().resolve("exports")
                .resolve("net_music_can_play_bili").resolve(EXPORT_FILE);
        try {
            Files.deleteIfExists(exportPath);
        } catch (IOException e) {
            throw new AssertionError("Could not clear the whitelist Bench export", e);
        }
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
                level.setBlockAndUpdate(livePos, ModBlocks.LIVE_STREAMER.get().defaultBlockState());
                LiveStreamerBlockEntity live = requireBlockEntity(level, livePos, LiveStreamerBlockEntity.class);
                if (!live.setRoomId(level, DENIED_ROOM, player)) {
                    throw new AssertionError("Bench denial room was not accepted as syntactically valid");
                }
                live.startLive(level, player);
                if (live.isPlaying() || live.isWaitingForLive()) {
                    throw new AssertionError("Non-whitelisted live room passed the production start gate");
                }
                String denial = BiliWhitelistManager.denialMessage(player, "live:" + DENIED_ROOM,
                        "启动直播").getString();
                if (!denial.contains("未加入白名单") || !denial.contains(DENIED_ROOM)) {
                    throw new AssertionError("Whitelist denial did not expose the rejected live room: " + denial);
                }
                operations += 2;

                BiliWhitelistManager.AddResult deniedAdd = BiliWhitelistManager.add(server,
                        "https://live.bilibili.com/" + DENIED_ROOM, player);
                if (deniedAdd.status() != BiliWhitelistManager.AddResult.Status.ADDED
                        || !BiliWhitelistManager.isAllowed(server, "live:" + DENIED_ROOM)) {
                    throw new AssertionError("Whitelist add did not allow the canonical live room: " + deniedAdd);
                }
                operations++;

                boolean reviewAlreadyAllowed = BiliWhitelistManager.isAllowed(server, "live:" + REVIEW_ROOM);
                BiliWhitelistManager.AddResult reviewAdd = BiliWhitelistManager.add(server,
                        "https://live.bilibili.com/" + REVIEW_ROOM + "?live_from=modbench", player);
                if (!reviewAlreadyAllowed
                        && reviewAdd.status() != BiliWhitelistManager.AddResult.Status.ADDED
                        || reviewAlreadyAllowed
                        && reviewAdd.status() != BiliWhitelistManager.AddResult.Status.DUPLICATE) {
                    throw new AssertionError("Review room add/duplicate result was inconsistent: " + reviewAdd);
                }
                reviewRoomAddedByBench = !reviewAlreadyAllowed;
                operations++;

                WhitelistReviewPacket packet = WhitelistReviewPacket.create(BiliWhitelistManager.entries(server));
                boolean roomVisible = packet.entries().stream()
                        .anyMatch(entry -> ("live:" + REVIEW_ROOM).equals(entry.id()));
                boolean deniedVisible = packet.entries().stream()
                        .anyMatch(entry -> ("live:" + DENIED_ROOM).equals(entry.id()));
                if (!roomVisible || !deniedVisible) {
                    throw new AssertionError("Whitelist review list omitted Bench entries: " + packet.entries());
                }
                reviewPayload.set(packet);
                operations++;

                String csv = BiliWhitelistManager.exportCsv(server);
                if (!csv.startsWith("type,id,addedAt,addedByName,addedByUuid,originalInput\r\n")
                        || !csv.contains("\"live\",\"live:" + REVIEW_ROOM + "\"")) {
                    throw new AssertionError("Whitelist CSV export omitted header or live room");
                }
                exportedCsv.set(csv);
                operations++;

                BiliWhitelistManager.RemoveResult removed = BiliWhitelistManager.remove(server,
                        "live:" + DENIED_ROOM);
                if (removed.status() != BiliWhitelistManager.RemoveResult.Status.REMOVED
                        || BiliWhitelistManager.isAllowed(server, "live:" + DENIED_ROOM)) {
                    throw new AssertionError("Whitelist remove did not restore the denial gate: " + removed);
                }
                operations++;
                setupComplete.set(true);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        throwIfFailed();
        if (!setupComplete.get() || reviewPayload.get() == null || exportedCsv.get() == null) {
            return BenchClientStepResult.CONTINUE;
        }
        context.minecraft().setScreen(new WhitelistReviewScreen(reviewPayload.get(), "live:" + REVIEW_ROOM));
        gui = context.automation().beginGuiSession(WhitelistReviewScreen.class);
        openedAtFrame = context.frames().sampleCount();
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return context.frames().sampleCount() > openedAtFrame
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        throwIfFailed();
        if (context.frames().sampleCount() <= openedAtFrame) {
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 0) {
            if (!(context.minecraft().screen instanceof WhitelistReviewScreen)
                    || gui == null || !gui.active() || gui.snapshot().flattened().size() < 4) {
                throw new AssertionError("Whitelist review list did not render its controls");
            }
            gui.close();
            WhitelistPreviewPacket preview = new WhitelistPreviewPacket(UUID.randomUUID(),
                    "Live room " + REVIEW_ROOM, "", "", "",
                    16, 9, 1, 7, 0, 0L, false);
            context.minecraft().setScreen(new WhitelistPreviewScreen(preview));
            gui = context.automation().beginGuiSession(WhitelistPreviewScreen.class);
            openedAtFrame = context.frames().sampleCount();
            phase = 1;
            return BenchClientStepResult.CONTINUE;
        }
        if (!(context.minecraft().screen instanceof WhitelistPreviewScreen)
                || gui == null || !gui.active() || gui.snapshot().flattened().isEmpty()) {
            throw new AssertionError("Whitelist preview screen did not render");
        }
        WhitelistCsvExportClient.save(new WhitelistCsvExportPacket(EXPORT_FILE, exportedCsv.get()));
        if (!Files.isRegularFile(exportPath)) {
            throw new AssertionError("Whitelist CSV was not written to the client export directory");
        }
        operations++;
        context.metrics().record(OPERATIONS, operations);
        gui.close();
        gui = null;
        context.minecraft().setScreen(null);
        phase = 2;
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        if (phase != 2 || operations != 8 || !Files.isRegularFile(exportPath)
                || context.minecraft().screen != null) {
            throw new AssertionError("Whitelist lifecycle did not complete: phase=" + phase
                    + " operations=" + operations + " export=" + exportPath);
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        context.minecraft().setScreen(null);
        if (gui != null) {
            gui.close();
            gui = null;
        }
        try {
            if (exportPath != null) {
                Files.deleteIfExists(exportPath);
            }
        } catch (IOException ignored) {
        }
        var server = context.minecraft().getSingleplayerServer();
        if (server != null) {
            boolean removeReviewRoom = reviewRoomAddedByBench;
            server.execute(() -> {
                try {
                    BiliWhitelistManager.remove(server, "live:" + DENIED_ROOM);
                    if (removeReviewRoom) {
                        BiliWhitelistManager.remove(server, "live:" + REVIEW_ROOM);
                    }
                } catch (IOException ignored) {
                }
                if (livePos != null && server.overworld().isLoaded(livePos)) {
                    server.overworld().setBlockAndUpdate(livePos, Blocks.AIR.defaultBlockState());
                }
            });
        }
        Config.enableLinkWhitelist = originalWhitelistEnabled;
    }

    private void throwIfFailed() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("Whitelist management lifecycle failed", error);
        }
    }
}
