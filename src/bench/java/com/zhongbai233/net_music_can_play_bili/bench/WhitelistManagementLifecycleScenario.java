package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requireBlockEntity;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.bench.api.neoforge.client.BenchGuiSession;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.Config;
import com.zhongbai233.net_music_can_play_bili.client.WhitelistCsvExportClient;
import com.zhongbai233.net_music_can_play_bili.gui.WhitelistReviewScreen;
import com.zhongbai233.net_music_can_play_bili.gui.WhitelistPreviewScreen;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistPreviewPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistCsvExportPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewActionPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewMutationResultPacket;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class WhitelistManagementLifecycleScenario implements BenchClientScenario {
    private static final String REVIEW_ROOM = "8178490";
    private static final String EXPORT_FILE = "ncpb-whitelist-bench.csv";
    private static final String FORMULA_COMMENT = "=HYPERLINK(\"https://bench.invalid\",\"Bench\")";
    private static final BenchMetricDescriptor OPERATIONS = new BenchMetricDescriptor(
            "ncpb.whitelist.operations", "count", MetricDirection.NEUTRAL);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<WhitelistReviewPacket> reviewPayload = new AtomicReference<>();
    private final AtomicReference<String> exportedCsv = new AtomicReference<>();
    private final AtomicBoolean setupComplete = new AtomicBoolean();
    private final AtomicBoolean whitelistSnapshotReady = new AtomicBoolean();
    private boolean originalWhitelistEnabled;
    private boolean reviewRoomAddedByBench;
    private String deniedRoom;
    private UUID playerId;
    private BlockPos livePos;
    private Path exportPath;
    private Path chunkedExportPath;
    private Path chunkedPartPath;
    private Path whitelistPath;
    private Path whitelistTemporaryPath;
    private FileSnapshot whitelistSnapshot;
    private FileSnapshot whitelistTemporarySnapshot;
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
                snapshotWhitelistFiles(server);
                deniedRoom = unusedBenchRoom(server);
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || !(player.level() instanceof ServerLevel level)) {
                    throw new IllegalStateException("Integrated server player is unavailable");
                }
                level.setBlockAndUpdate(livePos, ModBlocks.LIVE_STREAMER.get().defaultBlockState());
                LiveStreamerBlockEntity live = requireBlockEntity(level, livePos, LiveStreamerBlockEntity.class);
                if (!live.setRoomId(level, deniedRoom, player)) {
                    throw new AssertionError("Bench denial room was not accepted as syntactically valid");
                }
                live.startLive(level, player);
                if (live.isPlaying() || live.isWaitingForLive()) {
                    throw new AssertionError("Non-whitelisted live room passed the production start gate");
                }
                String denial = BiliWhitelistManager.denialMessage(player, "live:" + deniedRoom,
                        "启动直播").getString();
                if (!denial.contains("未加入白名单") || !denial.contains(deniedRoom)) {
                    throw new AssertionError("Whitelist denial did not expose the rejected live room: " + denial);
                }
                operations += 2;

                BiliWhitelistManager.AddResult deniedAdd = BiliWhitelistManager.add(server,
                        "https://live.bilibili.com/" + deniedRoom, player);
                if (deniedAdd.status() != BiliWhitelistManager.AddResult.Status.ADDED
                        || !BiliWhitelistManager.isAllowed(server, "live:" + deniedRoom)) {
                    throw new AssertionError("Whitelist add did not allow the canonical live room: " + deniedAdd);
                }
                operations++;
                BiliWhitelistManager.RemoveResult blankNote = BiliWhitelistManager.remove(server,
                        "live:" + deniedRoom, player, "   ");
                if (blankNote.status() != BiliWhitelistManager.RemoveResult.Status.NOTE_REQUIRED
                        || !BiliWhitelistManager.isAllowed(server, "live:" + deniedRoom)) {
                    throw new AssertionError("Blank removal note changed the whitelist: " + blankNote);
                }
                operations++;
                int commentsBeforeBlank = findEntry(server, "live:" + deniedRoom).comments.size();
                BiliWhitelistManager.CommentResult blankComment = BiliWhitelistManager.addComment(server,
                        "live:" + deniedRoom, player, " \n ");
                if (blankComment.status() != BiliWhitelistManager.CommentResult.Status.COMMENT_REQUIRED
                        || findEntry(server, "live:" + deniedRoom).comments.size() != commentsBeforeBlank) {
                    throw new AssertionError("Blank review comment changed the whitelist: " + blankComment);
                }
                operations++;
                BiliWhitelistManager.Entry staleBaseline = findEntry(server, "live:" + deniedRoom);
                String staleAddedAt = staleBaseline.addedAt + "-stale";
                BiliWhitelistManager.CommentResult staleComment = BiliWhitelistManager.addComment(server,
                        "live:" + deniedRoom, player, "不应写入的过期审核", staleAddedAt);
                if (staleComment.status() != BiliWhitelistManager.CommentResult.Status.STALE
                        || findEntry(server, "live:" + deniedRoom).comments.size() != commentsBeforeBlank) {
                    throw new AssertionError("Stale review comment changed the current whitelist entry: "
                            + staleComment);
                }
                BiliWhitelistManager.RemoveResult staleRemove = BiliWhitelistManager.remove(server,
                        "live:" + deniedRoom, player, "不应执行的过期删除", staleAddedAt);
                if (staleRemove.status() != BiliWhitelistManager.RemoveResult.Status.STALE
                        || !BiliWhitelistManager.isAllowed(server, "live:" + deniedRoom)
                        || findEntry(server, "live:" + deniedRoom).comments.size() != commentsBeforeBlank) {
                    throw new AssertionError("Stale removal changed the current whitelist entry: " + staleRemove);
                }
                operations += 2;

                boolean reviewAlreadyAllowed = BiliWhitelistManager.isAllowed(server, "live:" + REVIEW_ROOM);
                BiliWhitelistManager.AddResult reviewAdd = BiliWhitelistManager.add(server,
                        "https://live.bilibili.com/" + REVIEW_ROOM + "?live_from=modbench", player);
                if ((!reviewAlreadyAllowed
                        && reviewAdd.status() != BiliWhitelistManager.AddResult.Status.ADDED)
                        || (reviewAlreadyAllowed
                        && reviewAdd.status() != BiliWhitelistManager.AddResult.Status.DUPLICATE)) {
                    throw new AssertionError("Review room add/duplicate result was inconsistent: " + reviewAdd);
                }
                reviewRoomAddedByBench = !reviewAlreadyAllowed;
                operations++;

                WhitelistReviewPacket packet = currentReviewPacket(server);
                boolean roomVisible = packet.entries().stream()
                        .anyMatch(entry -> ("live:" + REVIEW_ROOM).equals(entry.id()));
                boolean deniedVisible = packet.entries().stream()
                        .anyMatch(entry -> ("live:" + deniedRoom).equals(entry.id()));
                if ((reviewRoomAddedByBench && !roomVisible) || !deniedVisible) {
                    throw new AssertionError("Whitelist review list omitted Bench entries: " + packet.entries());
                }
                reviewPayload.set(packet);
                operations++;

                BiliWhitelistManager.CommentResult comment = BiliWhitelistManager.addComment(server,
                        "live:" + deniedRoom, player, "  " + FORMULA_COMMENT + "\n  ");
                if (comment.status() != BiliWhitelistManager.CommentResult.Status.ADDED
                        || !FORMULA_COMMENT.equals(comment.comment().text)) {
                    throw new AssertionError("Whitelist comment was not retained: " + comment);
                }
                WhitelistReviewPacket commentedPacket = currentReviewPacket(server);
                if (commentedPacket.entries().stream().noneMatch(entry -> ("live:" + deniedRoom).equals(entry.id())
                        && entry.comments().stream().anyMatch(item -> FORMULA_COMMENT.equals(item.text())))) {
                    throw new AssertionError("Whitelist review packet omitted active entry comment");
                }
                reviewPayload.set(commentedPacket);
                operations += 2;

                BiliWhitelistManager.CommentResult removalComment = BiliWhitelistManager.addComment(server,
                        "live:" + deniedRoom, player, "Bench 删除前审核：确认清理原因");
                if (removalComment.status() != BiliWhitelistManager.CommentResult.Status.ADDED) {
                    throw new AssertionError("Removal audit comment was not retained: " + removalComment);
                }
                operations++;

                BiliWhitelistManager.RemoveResult removed = BiliWhitelistManager.remove(server,
                        "live:" + deniedRoom, player, "  Bench denial gate cleanup\nconfirmed  ");
                if (removed.status() != BiliWhitelistManager.RemoveResult.Status.REMOVED
                        || BiliWhitelistManager.isAllowed(server, "live:" + deniedRoom)
                        || removed.removalRecord() == null
                        || !"Bench denial gate cleanup confirmed".equals(removed.removalRecord().note)
                        || !player.getUUID().toString().equals(removed.removalRecord().removedByUuid)
                        || removed.removalRecord().entry.comments.stream().noneMatch(item ->
                                "Bench 删除前审核：确认清理原因".equals(item.text))) {
                    throw new AssertionError("Whitelist remove did not restore the denial gate: " + removed);
                }
                operations++;
                WhitelistReviewPacket historyPacket = currentReviewPacket(server);
                if (historyPacket.removalRecords().stream().noneMatch(record ->
                        record.entry() != null && ("live:" + deniedRoom).equals(record.entry().id())
                                && "Bench denial gate cleanup confirmed".equals(record.note())
                                && record.entry().comments().stream().anyMatch(item ->
                                        "Bench 删除前审核：确认清理原因".equals(item.text())))) {
                    throw new AssertionError("Whitelist removal record was not retained in review history");
                }
                reviewPayload.set(historyPacket);
                operations++;
                verifyReviewPageBoundaries();
                verifyReviewPacketCodec();
                verifyReviewActionPacketCodec();
                verifyPreviewPacketCodec();
                verifyMutationResultCodec();
                String csv = BiliWhitelistManager.exportCsv(server);
                if (!csv.startsWith("type,id,addedAt,addedByName,addedByUuid,originalInput,status,comments,")
                        || !csv.contains("\"live\",\"live:" + REVIEW_ROOM + "\"")
                        || !csv.contains("'" + FORMULA_COMMENT.replace("\"", "\"\""))
                        || !csv.contains("\"REMOVED\"")
                        || !csv.contains("Bench denial gate cleanup")) {
                    throw new AssertionError("Whitelist CSV export omitted comments or removal history");
                }
                if (!csv.contains("Bench 删除前审核：确认清理原因")) {
                    throw new AssertionError("Whitelist CSV omitted the deleted entry's retained review comment");
                }
                exportedCsv.set(csv);
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
        try {
            verifyChunkedCsvExport(context);
        } catch (IOException error) {
            throw new AssertionError("Could not verify the chunked whitelist CSV export", error);
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
        if (phase != 2 || operations != 17 || !Files.isRegularFile(exportPath)
                || !Files.isRegularFile(chunkedExportPath) || Files.exists(chunkedPartPath)
                || context.minecraft().screen != null) {
            throw new AssertionError("Whitelist lifecycle did not complete: phase=" + phase
                    + " operations=" + operations + " export=" + exportPath
                    + " chunkedExport=" + chunkedExportPath);
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
            if (chunkedExportPath != null) {
                Files.deleteIfExists(chunkedExportPath);
            }
            if (chunkedPartPath != null) {
                Files.deleteIfExists(chunkedPartPath);
            }
        } catch (IOException ignored) {
        }
        var server = context.minecraft().getSingleplayerServer();
        Throwable cleanupFailure = null;
        if (server != null) {
            CompletableFuture<Void> cleanupComplete = new CompletableFuture<>();
            Runnable cleanup = () -> {
                try {
                    if (livePos != null && server.overworld().isLoaded(livePos)) {
                        server.overworld().setBlockAndUpdate(livePos, Blocks.AIR.defaultBlockState());
                    }
                    restoreWhitelistFiles(server);
                    cleanupComplete.complete(null);
                } catch (Throwable error) {
                    cleanupComplete.completeExceptionally(error);
                }
            };
            if (server.isSameThread()) {
                cleanup.run();
            } else {
                server.execute(cleanup);
            }
            try {
                cleanupComplete.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                cleanupFailure = error;
            } catch (ExecutionException error) {
                cleanupFailure = error.getCause() == null ? error : error.getCause();
            } catch (TimeoutException error) {
                cleanupFailure = error;
            }
        } else if (whitelistSnapshotReady.get()) {
            try {
                restoreWhitelistFiles(null);
            } catch (IOException | ReflectiveOperationException error) {
                cleanupFailure = error;
            }
        }
        Config.enableLinkWhitelist = originalWhitelistEnabled;
        if (cleanupFailure != null) {
            throw new AssertionError("Could not restore the whitelist Bench snapshot", cleanupFailure);
        }
    }

    private static String unusedBenchRoom(net.minecraft.server.MinecraftServer server) {
        for (int attempt = 0; attempt < 32; attempt++) {
            long suffix = Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000_000_000_000L);
            String candidate = Long.toString(8_000_000_000_000_000L + suffix);
            if (!BiliWhitelistManager.isAllowed(server, "live:" + candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Could not allocate an isolated whitelist Bench room id");
    }

    private void snapshotWhitelistFiles(net.minecraft.server.MinecraftServer server) throws IOException {
        Path storageDirectory = server.getWorldPath(LevelResource.ROOT).resolve(NetMusicCanPlayBili.MODID);
        whitelistPath = storageDirectory.resolve(NetMusicCanPlayBili.MODID + "_link_whitelist.json");
        whitelistTemporaryPath = whitelistPath.resolveSibling(whitelistPath.getFileName() + ".tmp");
        whitelistSnapshot = FileSnapshot.capture(whitelistPath);
        whitelistTemporarySnapshot = FileSnapshot.capture(whitelistTemporaryPath);
        whitelistSnapshotReady.set(true);
    }

    private void restoreWhitelistFiles(net.minecraft.server.MinecraftServer server)
            throws IOException, ReflectiveOperationException {
        if (!whitelistSnapshotReady.get()) {
            return;
        }
        whitelistSnapshot.restore(whitelistPath);
        whitelistTemporarySnapshot.restore(whitelistTemporaryPath);

        Field loadedPath = BiliWhitelistManager.class.getDeclaredField("loadedPath");
        loadedPath.setAccessible(true);
        loadedPath.set(null, null);
        if (server != null) {
            BiliWhitelistManager.entries(server);
        }
    }

    private record FileSnapshot(boolean existed, byte[] bytes) {
        private static FileSnapshot capture(Path path) throws IOException {
            boolean existed = Files.exists(path);
            return new FileSnapshot(existed, existed ? Files.readAllBytes(path) : new byte[0]);
        }

        private void restore(Path path) throws IOException {
            if (!existed) {
                Files.deleteIfExists(path);
                return;
            }
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        }
    }

    private static BiliWhitelistManager.Entry findEntry(net.minecraft.server.MinecraftServer server, String id) {
        return BiliWhitelistManager.entries(server).stream()
                .filter(entry -> id.equals(entry.id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Whitelist entry is missing: " + id));
    }

    private static WhitelistReviewPacket currentReviewPacket(net.minecraft.server.MinecraftServer server) {
        BiliWhitelistManager.ReviewSnapshot snapshot = BiliWhitelistManager.reviewSnapshot(
                server, 0, WhitelistReviewPacket.ENTRY_PAGE_SIZE, 0, WhitelistReviewPacket.REMOVAL_PAGE_SIZE);
        WhitelistReviewPacket projected = WhitelistReviewPacket.create(
                snapshot.entries(), snapshot.removalRecords());
        return new WhitelistReviewPacket(projected.entries(), projected.removalRecords(),
                snapshot.entryOffset(), snapshot.totalEntries(), snapshot.removalOffset(),
                snapshot.totalRemovalRecords());
    }

    private static void verifyReviewPageBoundaries() {
        List<BiliWhitelistManager.Entry> entries = new ArrayList<>();
        for (int index = 0; index <= WhitelistReviewPacket.ENTRY_PAGE_SIZE; index++) {
            BiliWhitelistManager.Entry entry = syntheticEntry(index);
            if (index == 0) {
                for (int commentIndex = 0; commentIndex < 3; commentIndex++) {
                    BiliWhitelistManager.ReviewComment comment = new BiliWhitelistManager.ReviewComment();
                    comment.text = "comment-" + commentIndex;
                    comment.authorName = "reviewer-" + commentIndex;
                    entry.comments.add(comment);
                }
            }
            entries.add(entry);
        }
        List<BiliWhitelistManager.RemovalRecord> removals = new ArrayList<>();
        for (int index = 0; index <= WhitelistReviewPacket.REMOVAL_PAGE_SIZE; index++) {
            BiliWhitelistManager.RemovalRecord record = new BiliWhitelistManager.RemovalRecord();
            record.recordId = "record-" + index;
            record.entry = syntheticEntry(1_000 + index);
            record.removedAt = "2026-09-08T01:00:" + String.format("%02d", index % 60) + "Z";
            record.removedByName = "remover-" + index;
            record.removedByUuid = UUID.nameUUIDFromBytes(("remover-" + index)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            record.note = "note-" + index;
            removals.add(record);
        }

        WhitelistReviewPacket page = WhitelistReviewPacket.create(entries, removals);
        if (page.entryOffset() != 0 || page.totalEntries() != WhitelistReviewPacket.ENTRY_PAGE_SIZE + 1
                || page.entries().size() != WhitelistReviewPacket.ENTRY_PAGE_SIZE
                || !syntheticId(WhitelistReviewPacket.ENTRY_PAGE_SIZE - 1)
                        .equals(page.entries().getLast().id())
                || page.removalOffset() != 0
                || page.totalRemovalRecords() != WhitelistReviewPacket.REMOVAL_PAGE_SIZE + 1
                || page.removalRecords().size() != WhitelistReviewPacket.REMOVAL_PAGE_SIZE
                || !"record-31".equals(page.removalRecords().getLast().recordId())
                || page.entries().getFirst().comments().size() != 2
                || !"comment-1".equals(page.entries().getFirst().comments().getFirst().text())
                || !"comment-2".equals(page.entries().getFirst().comments().getLast().text())) {
            throw new AssertionError("Whitelist review 64/32 page boundary projection failed: " + page);
        }
    }

    private static void verifyReviewPacketCodec() {
        List<BiliWhitelistManager.Entry> entries = List.of(syntheticEntry(2_000));
        BiliWhitelistManager.RemovalRecord removal = new BiliWhitelistManager.RemovalRecord();
        removal.recordId = "record-codec";
        removal.entry = syntheticEntry(2_001);
        removal.removedAt = "2026-09-08T02:00:00Z";
        removal.removedByName = "Codec Bench";
        removal.removedByUuid = UUID.nameUUIDFromBytes("codec-bench".getBytes(StandardCharsets.UTF_8)).toString();
        removal.note = "codec removal note";
        WhitelistReviewPacket projected = WhitelistReviewPacket.create(entries, List.of(removal));

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            for (boolean openScreen : new boolean[] {true, false}) {
                WhitelistReviewPacket expected = new WhitelistReviewPacket(
                        projected.entries(), projected.removalRecords(), 0, 1, 0, 1, openScreen);
                WhitelistReviewPacket.STREAM_CODEC.encode(buffer, expected);
                WhitelistReviewPacket decoded = WhitelistReviewPacket.STREAM_CODEC.decode(buffer);
                if (!expected.equals(decoded) || decoded.openScreen() != openScreen) {
                    throw new AssertionError("Whitelist review codec lost openScreen="
                            + openScreen + ": " + decoded);
                }
                buffer.clear();
            }
        } finally {
            buffer.release();
        }
    }

    private static BiliWhitelistManager.Entry syntheticEntry(int index) {
        BiliWhitelistManager.Entry entry = new BiliWhitelistManager.Entry();
        entry.type = "url";
        entry.id = syntheticId(index);
        entry.originalInput = "https://bench.invalid/" + index;
        entry.addedByName = "Bench";
        entry.addedAt = "2026-09-08T00:00:" + String.format("%02d", index % 60) + "Z";
        return entry;
    }

    private static String syntheticId(int index) {
        return "url:https://bench.invalid/" + index;
    }

    private static void verifyReviewActionPacketCodec() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            for (WhitelistReviewActionPacket.Action action : WhitelistReviewActionPacket.Action.values()) {
                long targetMillis = action == WhitelistReviewActionPacket.Action.PREVIEW_SEEK ? 12_345L : 0L;
                long requestId = 20_000L + action.ordinal();
                WhitelistReviewActionPacket expected = new WhitelistReviewActionPacket(
                        action, "bili:BV1Bench|p=2", targetMillis, "审核备注", "2026-09-08T00:00:00Z",
                        64, 32, requestId);
                WhitelistReviewActionPacket.STREAM_CODEC.encode(buffer, expected);
                WhitelistReviewActionPacket decoded = WhitelistReviewActionPacket.STREAM_CODEC.decode(buffer);
                if (!expected.equals(decoded) || decoded.requestId() != requestId
                        || decoded.targetMillis() != targetMillis) {
                    throw new AssertionError("Whitelist review action codec lost request correlation: " + decoded);
                }
                buffer.clear();
            }
        } finally {
            buffer.release();
        }
    }

    private static void verifyPreviewPacketCodec() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            WhitelistPreviewPacket expected = new WhitelistPreviewPacket(UUID.randomUUID(), "Bench preview",
                    "BV1Bench|p=2", "https://bench.invalid/audio", "BV1Bench|p=2",
                    1920, 1080, 60, 7, 300, 299_999L, true, 30_001L);
            WhitelistPreviewPacket.STREAM_CODEC.encode(buffer, expected);
            WhitelistPreviewPacket decoded = WhitelistPreviewPacket.STREAM_CODEC.decode(buffer);
            if (!expected.equals(decoded) || decoded.requestId() != 30_001L
                    || decoded.elapsedMillis() != 299_999L) {
                throw new AssertionError("Whitelist preview codec lost request correlation: " + decoded);
            }
            WhitelistPreviewPacket legacy = new WhitelistPreviewPacket(UUID.randomUUID(), "Legacy preview",
                    "https://bench.invalid/legacy", "", "", 1, 1, 1, 0, 0, 0L, false);
            if (legacy.requestId() != 0L) {
                throw new AssertionError("Legacy whitelist preview constructor did not default requestId to zero");
            }
        } finally {
            buffer.release();
        }
    }

    private static void verifyMutationResultCodec() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            for (WhitelistReviewMutationResultPacket.Mutation mutation
                    : WhitelistReviewMutationResultPacket.Mutation.values()) {
                for (WhitelistReviewMutationResultPacket.Status status
                        : WhitelistReviewMutationResultPacket.Status.values()) {
                    long requestId = 10_000L + mutation.ordinal() * 100L + status.ordinal();
                    WhitelistReviewMutationResultPacket expected = new WhitelistReviewMutationResultPacket(
                            mutation, status, "bili:BV1Bench|p=2", "审核结果：" + status.name(), requestId);
                    WhitelistReviewMutationResultPacket.STREAM_CODEC.encode(buffer, expected);
                    WhitelistReviewMutationResultPacket decoded =
                            WhitelistReviewMutationResultPacket.STREAM_CODEC.decode(buffer);
                    if (!expected.equals(decoded)
                            || decoded.requestId() != requestId
                            || decoded.successful()
                            != (status == WhitelistReviewMutationResultPacket.Status.SUCCESS)) {
                        throw new AssertionError("Whitelist mutation result codec/state mismatch: " + decoded);
                    }
                    buffer.clear();
                }
            }

            WhitelistReviewMutationResultPacket bounded = new WhitelistReviewMutationResultPacket(
                    WhitelistReviewMutationResultPacket.Mutation.COMMENT,
                    WhitelistReviewMutationResultPacket.Status.SAVE_FAILED,
                    "t".repeat(520), "m".repeat(260), 99_999L);
            WhitelistReviewMutationResultPacket.STREAM_CODEC.encode(buffer, bounded);
            WhitelistReviewMutationResultPacket decoded =
                    WhitelistReviewMutationResultPacket.STREAM_CODEC.decode(buffer);
            if (decoded.targetId().length() != 512 || decoded.message().length() != 256
                    || decoded.requestId() != 99_999L) {
                throw new AssertionError("Whitelist mutation result codec ignored field bounds: " + decoded);
            }
            buffer.clear();

            WhitelistReviewMutationResultPacket legacy = new WhitelistReviewMutationResultPacket(
                    WhitelistReviewMutationResultPacket.Mutation.REMOVE,
                    WhitelistReviewMutationResultPacket.Status.SUCCESS, "legacy", "legacy");
            if (legacy.requestId() != 0L) {
                throw new AssertionError("Legacy mutation result constructor did not default requestId to zero");
            }

            expectMutationResultDecodeFailure(buffer,
                    WhitelistReviewMutationResultPacket.Mutation.values().length, 0);
            expectMutationResultDecodeFailure(buffer,
                    0, WhitelistReviewMutationResultPacket.Status.values().length);
        } finally {
            buffer.release();
        }
    }

    private static void expectMutationResultDecodeFailure(RegistryFriendlyByteBuf buffer,
            int mutationId, int statusId) {
        buffer.clear();
        buffer.writeVarInt(mutationId);
        buffer.writeVarInt(statusId);
        buffer.writeUtf("");
        buffer.writeUtf("");
        try {
            WhitelistReviewMutationResultPacket.STREAM_CODEC.decode(buffer);
            throw new AssertionError("Whitelist mutation result codec accepted invalid enum ids: "
                    + mutationId + "/" + statusId);
        } catch (DecoderException expected) {
            // Expected: wire enum ids are strict rather than silently falling back to another action.
        }
    }

    private void verifyChunkedCsvExport(BenchClientContext context) throws IOException {
        String seed = "type,id,comment\r\nurl,https://bench.invalid/,审核\r\n";
        byte[] base = seed.getBytes(StandardCharsets.UTF_8);
        int padding = WhitelistCsvExportPacket.CHUNK_BYTES - 1 - base.length;
        String csv = seed + "x".repeat(padding) + "界🙂,\"审核\"\r\n";
        byte[] expected = csv.getBytes(StandardCharsets.UTF_8);
        List<WhitelistCsvExportPacket> packets = WhitelistCsvExportPacket.createChunks(csv);
        if (packets.size() < 2
                || packets.getFirst().chunk().length != WhitelistCsvExportPacket.CHUNK_BYTES
                || packets.getFirst().chunk()[WhitelistCsvExportPacket.CHUNK_BYTES - 1] != (byte) 0xE7
                || packets.get(1).chunk().length == 0 || packets.get(1).chunk()[0] != (byte) 0x95) {
            throw new AssertionError("Whitelist CSV fixture did not split a UTF-8 character across chunks");
        }

        ByteArrayOutputStream joined = new ByteArrayOutputStream(expected.length);
        WhitelistCsvExportPacket first = packets.getFirst();
        for (int index = 0; index < packets.size(); index++) {
            WhitelistCsvExportPacket packet = packets.get(index);
            if (!first.transferId().equals(packet.transferId())
                    || !first.fileName().equals(packet.fileName())
                    || packet.chunkIndex() != index || packet.chunkCount() != packets.size()
                    || packet.totalBytes() != expected.length) {
                throw new AssertionError("Whitelist CSV chunk metadata mismatch at index " + index);
            }
            joined.writeBytes(packet.chunk());
        }
        if (!java.util.Arrays.equals(expected, joined.toByteArray())) {
            throw new AssertionError("Whitelist CSV raw chunk recomposition changed UTF-8 bytes");
        }

        Path exportDirectory = context.minecraft().gameDirectory.toPath()
                .resolve("exports").resolve("net_music_can_play_bili");
        chunkedExportPath = exportDirectory.resolve(first.fileName());
        chunkedPartPath = exportDirectory.resolve(".ncpb-whitelist-" + first.transferId() + ".part");
        Files.deleteIfExists(chunkedExportPath);
        Files.deleteIfExists(chunkedPartPath);
        WhitelistCsvExportClient.save(first);
        if (!Files.isRegularFile(chunkedPartPath) || Files.exists(chunkedExportPath)) {
            throw new AssertionError("Whitelist CSV client did not stage the first chunk in its isolated part file");
        }
        packets.subList(1, packets.size()).forEach(WhitelistCsvExportClient::save);
        if (!Files.isRegularFile(chunkedExportPath)
                || !java.util.Arrays.equals(expected, Files.readAllBytes(chunkedExportPath))
                || Files.exists(chunkedPartPath)) {
            throw new AssertionError("Whitelist CSV client did not reassemble the multibyte chunk stream exactly");
        }
    }

    private void throwIfFailed() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("Whitelist management lifecycle failed", error);
        }
    }
}
