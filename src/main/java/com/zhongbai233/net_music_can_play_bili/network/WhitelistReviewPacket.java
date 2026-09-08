package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.NetMusicBiliServerCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** 客户端白名单审核界面的列表快照。 */
public record WhitelistReviewPacket(List<Entry> entries, List<RemovalRecord> removalRecords,
        int entryOffset, int totalEntries, int removalOffset, int totalRemovalRecords,
        boolean openScreen) implements CustomPacketPayload {
    public static final Type<WhitelistReviewPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("whitelist_review"));
    public static final int ENTRY_PAGE_SIZE = 64;
    public static final int REMOVAL_PAGE_SIZE = 32;
    private static final int MAX_FIELD_LENGTH = 512;
    private static final int MAX_COMMENTS_PER_ENTRY = 2;
    private static final int MAX_COMMENT_LENGTH = 256;

    public WhitelistReviewPacket(List<Entry> entries) {
        this(entries, List.of(), 0, entries == null ? 0 : entries.size(), 0, 0, true);
    }

    public WhitelistReviewPacket(List<Entry> entries, List<RemovalRecord> removalRecords) {
        this(entries, removalRecords, 0, entries == null ? 0 : entries.size(), 0,
                removalRecords == null ? 0 : removalRecords.size(), true);
    }

    public WhitelistReviewPacket(List<Entry> entries, List<RemovalRecord> removalRecords,
            int entryOffset, int totalEntries, int removalOffset, int totalRemovalRecords) {
        this(entries, removalRecords, entryOffset, totalEntries, removalOffset, totalRemovalRecords, false);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistReviewPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public WhitelistReviewPacket decode(RegistryFriendlyByteBuf buffer) {
            int entryOffset = readNonNegative(buffer, "entry offset");
            int totalEntries = readNonNegative(buffer, "entry total");
            int removalOffset = readNonNegative(buffer, "removal offset");
            int totalRemovals = readNonNegative(buffer, "removal total");
            int count = readCount(buffer, ENTRY_PAGE_SIZE, "entries");
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String type = buffer.readUtf(32);
                String id = buffer.readUtf(MAX_FIELD_LENGTH);
                String addedAt = buffer.readUtf(128);
                String addedByName = buffer.readUtf(128);
                String addedByUuid = buffer.readUtf(64);
                String originalInput = buffer.readUtf(MAX_FIELD_LENGTH);
                int commentCount = readCount(buffer, MAX_COMMENTS_PER_ENTRY, "entry comments");
                List<Comment> comments = new ArrayList<>(commentCount);
                for (int j = 0; j < commentCount; j++) {
                    comments.add(new Comment(buffer.readUtf(MAX_COMMENT_LENGTH), buffer.readUtf(128),
                            buffer.readUtf(64), buffer.readUtf(64)));
                }
                entries.add(new Entry(type, id, addedAt, addedByName, addedByUuid, originalInput,
                        List.copyOf(comments)));
            }
            int removalCount = readCount(buffer, REMOVAL_PAGE_SIZE, "removal records");
            List<RemovalRecord> removals = new ArrayList<>(removalCount);
            for (int i = 0; i < removalCount; i++) {
                String recordId = buffer.readUtf(64);
                Entry entry = readEntry(buffer);
                removals.add(new RemovalRecord(recordId, entry, buffer.readUtf(128), buffer.readUtf(128),
                        buffer.readUtf(64), buffer.readUtf(MAX_COMMENT_LENGTH)));
            }
            if (entryOffset > totalEntries || removalOffset > totalRemovals) {
                throw new io.netty.handler.codec.DecoderException("Invalid whitelist review page metadata");
            }
            return new WhitelistReviewPacket(List.copyOf(entries), List.copyOf(removals),
                    entryOffset, totalEntries, removalOffset, totalRemovals, buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, WhitelistReviewPacket packet) {
            int totalEntries = Math.max(0, packet.totalEntries());
            int totalRemovals = Math.max(0, packet.totalRemovalRecords());
            buffer.writeVarInt(Math.min(Math.max(0, packet.entryOffset()), totalEntries));
            buffer.writeVarInt(totalEntries);
            buffer.writeVarInt(Math.min(Math.max(0, packet.removalOffset()), totalRemovals));
            buffer.writeVarInt(totalRemovals);
            List<Entry> safeEntries = packet.entries() == null ? List.of() : packet.entries();
            if (safeEntries.size() > ENTRY_PAGE_SIZE) {
                throw new io.netty.handler.codec.EncoderException("Whitelist review entry page exceeds limit");
            }
            int count = safeEntries.size();
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                Entry entry = safeEntries.get(i);
                buffer.writeUtf(bounded(entry.type(), 32), 32);
                buffer.writeUtf(safe(entry.id()), MAX_FIELD_LENGTH);
                buffer.writeUtf(bounded(entry.addedAt(), 128), 128);
                buffer.writeUtf(bounded(entry.addedByName(), 128), 128);
                buffer.writeUtf(bounded(entry.addedByUuid(), 64), 64);
                buffer.writeUtf(bounded(entry.originalInput(), MAX_FIELD_LENGTH), MAX_FIELD_LENGTH);
                List<Comment> comments = entry.comments() == null ? List.of() : entry.comments();
                int commentCount = Math.min(MAX_COMMENTS_PER_ENTRY, comments.size());
                buffer.writeVarInt(commentCount);
                int commentStart = Math.max(0, comments.size() - commentCount);
                for (int j = commentStart; j < comments.size(); j++) {
                    Comment comment = comments.get(j);
                    buffer.writeUtf(bounded(comment.text(), MAX_COMMENT_LENGTH), MAX_COMMENT_LENGTH);
                    buffer.writeUtf(bounded(comment.authorName(), 128), 128);
                    buffer.writeUtf(bounded(comment.authorUuid(), 64), 64);
                    buffer.writeUtf(bounded(comment.createdAt(), 64), 64);
                }
            }
            List<RemovalRecord> safeRemovals = packet.removalRecords() == null ? List.of() : packet.removalRecords();
            if (safeRemovals.size() > REMOVAL_PAGE_SIZE) {
                throw new io.netty.handler.codec.EncoderException("Whitelist review history page exceeds limit");
            }
            int removalCount = safeRemovals.size();
            buffer.writeVarInt(removalCount);
            for (int i = 0; i < removalCount; i++) {
                RemovalRecord record = safeRemovals.get(i);
                buffer.writeUtf(bounded(record.recordId(), 64), 64);
                writeEntry(buffer, record.entry());
                buffer.writeUtf(bounded(record.removedAt(), 128), 128);
                buffer.writeUtf(bounded(record.removedByName(), 128), 128);
                buffer.writeUtf(bounded(record.removedByUuid(), 64), 64);
                buffer.writeUtf(bounded(record.note(), MAX_COMMENT_LENGTH), MAX_COMMENT_LENGTH);
            }
            buffer.writeBoolean(packet.openScreen());
        }
    };

    private static Entry readEntry(RegistryFriendlyByteBuf buffer) {
        String type = buffer.readUtf(32);
        String id = buffer.readUtf(MAX_FIELD_LENGTH);
        String addedAt = buffer.readUtf(128);
        String addedByName = buffer.readUtf(128);
        String addedByUuid = buffer.readUtf(64);
        String originalInput = buffer.readUtf(MAX_FIELD_LENGTH);
        int count = readCount(buffer, MAX_COMMENTS_PER_ENTRY, "removal entry comments");
        List<Comment> comments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            comments.add(new Comment(buffer.readUtf(MAX_COMMENT_LENGTH), buffer.readUtf(128),
                    buffer.readUtf(64), buffer.readUtf(64)));
        }
        return new Entry(type, id, addedAt, addedByName, addedByUuid, originalInput, List.copyOf(comments));
    }

    private static void writeEntry(RegistryFriendlyByteBuf buffer, Entry entry) {
        Entry safeEntry = entry == null ? new Entry("", "", "", "", "", "", List.of()) : entry;
        buffer.writeUtf(bounded(safeEntry.type(), 32), 32);
        buffer.writeUtf(bounded(safeEntry.id(), MAX_FIELD_LENGTH), MAX_FIELD_LENGTH);
        buffer.writeUtf(bounded(safeEntry.addedAt(), 128), 128);
        buffer.writeUtf(bounded(safeEntry.addedByName(), 128), 128);
        buffer.writeUtf(bounded(safeEntry.addedByUuid(), 64), 64);
        buffer.writeUtf(bounded(safeEntry.originalInput(), MAX_FIELD_LENGTH), MAX_FIELD_LENGTH);
        List<Comment> comments = safeEntry.comments() == null ? List.of() : safeEntry.comments();
        int commentCount = Math.min(MAX_COMMENTS_PER_ENTRY, comments.size());
        buffer.writeVarInt(commentCount);
        int start = Math.max(0, comments.size() - commentCount);
        for (int i = start; i < comments.size(); i++) {
            Comment comment = comments.get(i);
            buffer.writeUtf(bounded(comment.text(), MAX_COMMENT_LENGTH), MAX_COMMENT_LENGTH);
            buffer.writeUtf(bounded(comment.authorName(), 128), 128);
            buffer.writeUtf(bounded(comment.authorUuid(), 64), 64);
            buffer.writeUtf(bounded(comment.createdAt(), 64), 64);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static WhitelistReviewPacket create(List<BiliWhitelistManager.Entry> entries) {
        return create(entries, List.of());
    }

    public static WhitelistReviewPacket create(List<BiliWhitelistManager.Entry> entries,
            List<BiliWhitelistManager.RemovalRecord> removalRecords) {
        return create(entries, removalRecords, 0, entries == null ? 0 : entries.size(), 0,
                removalRecords == null ? 0 : removalRecords.size(), false);
    }

    private static WhitelistReviewPacket create(List<BiliWhitelistManager.Entry> entries,
            List<BiliWhitelistManager.RemovalRecord> removalRecords, int entryOffset, int totalEntries,
            int removalOffset, int totalRemovals, boolean openScreen) {
        List<Entry> result = new ArrayList<>();
        if (entries != null) {
            for (BiliWhitelistManager.Entry entry : entries) {
                List<Comment> comments = new ArrayList<>();
                if (entry.comments != null) {
                    int start = Math.max(0, entry.comments.size() - MAX_COMMENTS_PER_ENTRY);
                    for (int i = start; i < entry.comments.size(); i++) {
                        comments.add(packetComment(entry.comments.get(i)));
                    }
                }
                result.add(new Entry(bounded(entry.type, 32), safe(entry.id),
                        bounded(entry.addedAt, 128), bounded(entry.addedByName, 128),
                        bounded(entry.addedByUuid, 64), bounded(entry.originalInput, MAX_FIELD_LENGTH),
                        List.copyOf(comments)));
                if (result.size() >= ENTRY_PAGE_SIZE) {
                    break;
                }
            }
        }
        List<RemovalRecord> records = new ArrayList<>();
        if (removalRecords != null) {
            for (BiliWhitelistManager.RemovalRecord record : removalRecords) {
                records.add(new RemovalRecord(record.recordId, packetEntry(record.entry), record.removedAt,
                        record.removedByName, record.removedByUuid, record.note));
                if (records.size() >= REMOVAL_PAGE_SIZE) {
                    break;
                }
            }
        }
        return new WhitelistReviewPacket(List.copyOf(result), List.copyOf(records), entryOffset,
                Math.max(0, totalEntries), removalOffset, Math.max(0, totalRemovals), openScreen);
    }

    private static Entry packetEntry(BiliWhitelistManager.Entry entry) {
        if (entry == null) {
            return new Entry("", "", "", "", "", "", List.of());
        }
        List<Comment> comments = new ArrayList<>();
        if (entry.comments != null) {
            int start = Math.max(0, entry.comments.size() - MAX_COMMENTS_PER_ENTRY);
            for (int i = start; i < entry.comments.size(); i++) {
                comments.add(packetComment(entry.comments.get(i)));
            }
        }
        return packetEntry(entry, comments);
    }

    private static Entry packetEntry(BiliWhitelistManager.Entry entry, List<Comment> comments) {
        return new Entry(bounded(entry.type, 32), bounded(entry.id, MAX_FIELD_LENGTH),
                bounded(entry.addedAt, 128), bounded(entry.addedByName, 128), bounded(entry.addedByUuid, 64),
                bounded(entry.originalInput, MAX_FIELD_LENGTH), List.copyOf(comments));
    }

    private static Comment packetComment(BiliWhitelistManager.ReviewComment comment) {
        if (comment == null) {
            return new Comment("", "", "", "");
        }
        return new Comment(bounded(comment.text, MAX_COMMENT_LENGTH), bounded(comment.authorName, 128),
                bounded(comment.authorUuid, 64), bounded(comment.createdAt, 64));
    }

    public static void sendTo(ServerPlayer player) {
        sendTo(player, 0, 0, true);
    }

    public static void sendTo(ServerPlayer player, int requestedEntryOffset, int requestedRemovalOffset) {
        sendTo(player, requestedEntryOffset, requestedRemovalOffset, false);
    }

    private static void sendTo(ServerPlayer player, int requestedEntryOffset, int requestedRemovalOffset,
            boolean openScreen) {
        if (player == null) {
            return;
        }
        var server = player.level().getServer();
        BiliWhitelistManager.ReviewSnapshot snapshot = BiliWhitelistManager.reviewSnapshot(
                server, requestedEntryOffset, ENTRY_PAGE_SIZE, requestedRemovalOffset, REMOVAL_PAGE_SIZE);
        PacketDistributor.sendToPlayer(player, create(
                snapshot.entries(), snapshot.removalRecords(), snapshot.entryOffset(), snapshot.totalEntries(),
                snapshot.removalOffset(), snapshot.totalRemovalRecords(), openScreen));
    }

    public static boolean canOpen(ServerPlayer player) {
        return player != null && NetMusicBiliServerCommands.canManageWhitelist(player.createCommandSourceStack());
    }

    public static void handle(WhitelistReviewPacket payload, IPayloadContext context) {
        context.enqueueWork(
                () -> com.zhongbai233.net_music_can_play_bili.gui.WhitelistReviewScreen.openOrUpdate(payload));
    }

    public static void rejectUnauthorized(ServerPlayer player) {
        if (player != null) {
            player.sendSystemMessage(Component.literal("需要白名单管理权限（默认 OP4）才能进行审核。")
                    .withStyle(ChatFormatting.RED));
        }
    }

    private static int readCount(RegistryFriendlyByteBuf buffer, int maximum, String field) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) {
            throw new io.netty.handler.codec.DecoderException(
                    "Invalid whitelist review " + field + " count: " + count + " (max " + maximum + ")");
        }
        return count;
    }

    private static int readNonNegative(RegistryFriendlyByteBuf buffer, String field) {
        int value = buffer.readVarInt();
        if (value < 0) {
            throw new io.netty.handler.codec.DecoderException(
                    "Invalid whitelist review " + field + ": " + value);
        }
        return value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String bounded(String value, int maximum) {
        String safe = safe(value);
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    public record Entry(String type, String id, String addedAt, String addedByName, String addedByUuid,
            String originalInput, List<Comment> comments) {
        public Entry(String type, String id, String addedAt, String addedByName, String addedByUuid,
                String originalInput) {
            this(type, id, addedAt, addedByName, addedByUuid, originalInput, List.of());
        }
    }

    public record Comment(String text, String authorName, String authorUuid, String createdAt) {
    }

    public record RemovalRecord(String recordId, Entry entry, String removedAt, String removedByName,
            String removedByUuid, String note) {
    }
}
