package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 白名单审核界面的服务端操作。 */
public record WhitelistReviewActionPacket(Action action, String idOrLink, long targetMillis, String text,
        String expectedAddedAt, int entryOffset, int removalOffset, long requestId)
        implements CustomPacketPayload {
    private static final int MAX_VALUE_LENGTH = 512;
    private static final int MAX_TEXT_LENGTH = 256;
    private static final int MAX_VERSION_LENGTH = 128;
    private static final int MAX_COMPLETED_MUTATIONS = 2_048;
    private static final long COMPLETED_MUTATION_TTL_NANOS = TimeUnit.MINUTES.toNanos(5L);
    private static final AtomicLong CLIENT_REQUEST_IDS = new AtomicLong(System.nanoTime() & Long.MAX_VALUE);
    private static final ConcurrentHashMap<CompletedMutationKey, CompletedMutation> COMPLETED_MUTATIONS =
            new ConcurrentHashMap<>();

    public static final Type<WhitelistReviewActionPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("whitelist_review_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistReviewActionPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public WhitelistReviewActionPacket decode(RegistryFriendlyByteBuf buffer) {
            Action action = Action.byId(buffer.readVarInt());
            String idOrLink = buffer.readUtf(MAX_VALUE_LENGTH);
            long targetMillis = buffer.readVarLong();
            String text = buffer.readUtf(MAX_TEXT_LENGTH);
            String expectedAddedAt = buffer.readUtf(MAX_VERSION_LENGTH);
            return new WhitelistReviewActionPacket(action, idOrLink, targetMillis, text, expectedAddedAt,
                    readPageOffset(buffer, "entry"), readPageOffset(buffer, "removal"), readRequestId(buffer));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, WhitelistReviewActionPacket packet) {
            buffer.writeVarInt(packet.action().ordinal());
            buffer.writeUtf(bounded(packet.idOrLink(), MAX_VALUE_LENGTH, false), MAX_VALUE_LENGTH);
            buffer.writeVarLong(Math.max(0L, packet.targetMillis()));
            buffer.writeUtf(bounded(packet.text(), MAX_TEXT_LENGTH, true), MAX_TEXT_LENGTH);
            buffer.writeUtf(bounded(packet.expectedAddedAt(), MAX_VERSION_LENGTH, false), MAX_VERSION_LENGTH);
            buffer.writeVarInt(Math.max(0, packet.entryOffset()));
            buffer.writeVarInt(Math.max(0, packet.removalOffset()));
            buffer.writeVarLong(Math.max(0L, packet.requestId()));
        }
    };

    public WhitelistReviewActionPacket(Action action, String idOrLink) {
        this(action, idOrLink, 0L, "", "", 0, 0, 0L);
    }

    public WhitelistReviewActionPacket(Action action, String idOrLink, long targetMillis) {
        this(action, idOrLink, targetMillis, "", "", 0, 0, 0L);
    }

    public WhitelistReviewActionPacket(Action action, String idOrLink, String text) {
        this(action, idOrLink, 0L, text, "", 0, 0, 0L);
    }

    public WhitelistReviewActionPacket(Action action, String idOrLink, long targetMillis, String text,
            int entryOffset, int removalOffset) {
        this(action, idOrLink, targetMillis, text, "", entryOffset, removalOffset, 0L);
    }

    public WhitelistReviewActionPacket(Action action, String idOrLink, long targetMillis, long requestId) {
        this(action, idOrLink, targetMillis, "", "", 0, 0, requestId);
    }

    /** Generates a positive client-side correlation id for review requests. */
    public static long nextClientRequestId() {
        return CLIENT_REQUEST_IDS.updateAndGet(current -> current >= Long.MAX_VALUE
                ? 1L
                : current + 1L);
    }

    private static String bounded(String value, int maximum, boolean trim) {
        String safe = value == null ? "" : (trim ? value.trim() : value);
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    private static int readPageOffset(RegistryFriendlyByteBuf buffer, String field) {
        int value = buffer.readVarInt();
        if (value < 0) {
            throw new io.netty.handler.codec.DecoderException(
                    "Invalid whitelist review " + field + " page offset: " + value);
        }
        return value;
    }

    private static long readRequestId(RegistryFriendlyByteBuf buffer) {
        long value = buffer.readVarLong();
        if (value < 0L) {
            throw new io.netty.handler.codec.DecoderException(
                    "Invalid whitelist review request id: " + value);
        }
        return value;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WhitelistReviewActionPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        Action action = payload.action();
        if (!WhitelistReviewPacket.canOpen(player)) {
            WhitelistReviewPacket.rejectUnauthorized(player);
            sendMutationResult(player, action, WhitelistReviewMutationResultPacket.Status.DENIED,
                    payload.idOrLink(), "权限已变化，操作未执行。", payload.requestId());
            return;
        }
        if (!allowAction(player, action)) {
            sendMutationResult(player, action, WhitelistReviewMutationResultPacket.Status.RATE_LIMITED,
                    payload.idOrLink(), "操作过于频繁，请稍后重试。", payload.requestId());
            return;
        }
        if (replayCompletedMutation(player, payload)) {
            return;
        }
        switch (action) {
            case REFRESH -> WhitelistReviewPacket.sendTo(player, payload.entryOffset(), payload.removalOffset());
            case EXPORT -> {
                try {
                    int chunks = WhitelistCsvExportPacket.exportTo(player);
                    player.sendSystemMessage(Component.literal(
                            "已开始传输白名单及审核历史 CSV（" + chunks + " 个分块）。")
                            .withStyle(ChatFormatting.GREEN));
                } catch (IllegalArgumentException e) {
                    player.sendSystemMessage(Component.literal("白名单 CSV 导出失败：" + e.getMessage())
                            .withStyle(ChatFormatting.RED));
                }
            }
            case REMOVE -> removeEntry(player, payload.idOrLink(), payload.text(),
                    payload.expectedAddedAt(), payload.entryOffset(), payload.removalOffset(), payload.requestId());
            case COMMENT -> addComment(player, payload.idOrLink(), payload.text(),
                    payload.expectedAddedAt(), payload.entryOffset(), payload.removalOffset(), payload.requestId());
            case PREVIEW -> WhitelistPreviewPacket.resolveAndSend(
                    player, payload.idOrLink(), 0L, true, payload.requestId());
            case PREVIEW_SEEK -> WhitelistPreviewPacket.resolveAndSend(player, payload.idOrLink(),
                    Math.max(0L, payload.targetMillis()), true, payload.requestId());
        }
    }

    /** Replays a completed mutation so a lost acknowledgement cannot duplicate a comment or removal. */
    private static boolean replayCompletedMutation(ServerPlayer player, WhitelistReviewActionPacket payload) {
        if ((payload.action() != Action.REMOVE && payload.action() != Action.COMMENT)
                || payload.requestId() <= 0L) {
            return false;
        }
        CompletedMutationKey key = new CompletedMutationKey(player.getUUID(), payload.action(), payload.requestId());
        CompletedMutation completed = COMPLETED_MUTATIONS.get(key);
        if (completed == null) {
            return false;
        }
        long now = System.nanoTime();
        if (now - completed.completedAtNanos() >= COMPLETED_MUTATION_TTL_NANOS) {
            COMPLETED_MUTATIONS.remove(key, completed);
            return false;
        }
        if (!completed.matches(payload)) {
            String message = "请求标识与原操作不一致，操作未执行。";
            player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
            sendMutationResult(player, payload.action(), WhitelistReviewMutationResultPacket.Status.INVALID,
                    payload.idOrLink(), message, payload.requestId());
            return true;
        }
        player.sendSystemMessage(Component.literal(completed.message()).withStyle(ChatFormatting.GREEN));
        WhitelistReviewPacket.sendTo(player, payload.entryOffset(), payload.removalOffset());
        sendMutationResult(player, payload.action(), WhitelistReviewMutationResultPacket.Status.SUCCESS,
                payload.idOrLink(), completed.message(), payload.requestId());
        return true;
    }

    private static void rememberCompletedMutation(ServerPlayer player, Action action, String idOrLink, String text,
            String expectedAddedAt, long requestId, WhitelistReviewMutationResultPacket.Status status,
            String message) {
        if (requestId <= 0L || status != WhitelistReviewMutationResultPacket.Status.SUCCESS) {
            return;
        }
        long now = System.nanoTime();
        if (COMPLETED_MUTATIONS.size() >= MAX_COMPLETED_MUTATIONS) {
            COMPLETED_MUTATIONS.entrySet().removeIf(
                    entry -> now - entry.getValue().completedAtNanos() >= COMPLETED_MUTATION_TTL_NANOS);
        }
        if (COMPLETED_MUTATIONS.size() >= MAX_COMPLETED_MUTATIONS) {
            CompletedMutationKey oldestKey = null;
            long oldestCompletion = Long.MAX_VALUE;
            for (Map.Entry<CompletedMutationKey, CompletedMutation> entry : COMPLETED_MUTATIONS.entrySet()) {
                if (entry.getValue().completedAtNanos() < oldestCompletion) {
                    oldestCompletion = entry.getValue().completedAtNanos();
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey != null) {
                COMPLETED_MUTATIONS.remove(oldestKey);
            }
        }
        COMPLETED_MUTATIONS.put(new CompletedMutationKey(player.getUUID(), action, requestId),
                new CompletedMutation(safeFingerprint(idOrLink), safeFingerprint(text),
                        safeFingerprint(expectedAddedAt), message, now));
    }

    private static String safeFingerprint(String value) {
        return value == null ? "" : value;
    }

    private static boolean allowAction(ServerPlayer player, Action action) {
        String bucket;
        int limit;
        switch (action) {
            case REMOVE, COMMENT -> {
                bucket = "whitelist_review_mutation";
                limit = 3;
            }
            case PREVIEW_SEEK -> {
                bucket = "whitelist_review_seek";
                limit = 12;
            }
            case REFRESH, EXPORT, PREVIEW -> {
                bucket = "whitelist_review_navigation";
                limit = 6;
            }
            default -> throw new IllegalStateException("Unhandled whitelist review action: " + action);
        }
        boolean allowed = NetworkRateLimiter.allow(player.getUUID(), bucket, limit);
        if (!allowed) {
            player.sendSystemMessage(Component.literal("白名单审核操作过于频繁，请稍后再试。")
                    .withStyle(ChatFormatting.YELLOW));
        }
        return allowed;
    }

    private static void sendMutationResult(ServerPlayer player, Action action,
            WhitelistReviewMutationResultPacket.Status status, String targetId, String message, long requestId) {
        WhitelistReviewMutationResultPacket.Mutation mutation = switch (action) {
            case REMOVE -> WhitelistReviewMutationResultPacket.Mutation.REMOVE;
            case COMMENT -> WhitelistReviewMutationResultPacket.Mutation.COMMENT;
            case REFRESH -> WhitelistReviewMutationResultPacket.Mutation.REFRESH;
            case PREVIEW -> WhitelistReviewMutationResultPacket.Mutation.PREVIEW;
            case PREVIEW_SEEK -> WhitelistReviewMutationResultPacket.Mutation.PREVIEW_SEEK;
            default -> null;
        };
        if (mutation != null) {
            WhitelistReviewMutationResultPacket.sendTo(player, mutation, status, targetId, message, requestId);
        }
    }

    private static void removeEntry(ServerPlayer player, String idOrLink, String note,
            String expectedAddedAt, int entryOffset, int removalOffset, long requestId) {
        WhitelistReviewMutationResultPacket.Status packetStatus = WhitelistReviewMutationResultPacket.Status.INVALID;
        String message = "白名单移除未完成。";
        ChatFormatting color = ChatFormatting.RED;
        try {
            BiliWhitelistManager.RemoveResult result = BiliWhitelistManager.remove(player.level().getServer(),
                    idOrLink, player, note, expectedAddedAt);
            switch (result.status()) {
                case REMOVED -> {
                    packetStatus = WhitelistReviewMutationResultPacket.Status.SUCCESS;
                    message = "已从白名单移除：" + result.entry().id;
                    color = ChatFormatting.GREEN;
                }
                case MISSING -> {
                    packetStatus = WhitelistReviewMutationResultPacket.Status.MISSING;
                    message = "白名单中没有：" + result.requestedId();
                    color = ChatFormatting.YELLOW;
                }
                case INVALID -> {
                    packetStatus = WhitelistReviewMutationResultPacket.Status.INVALID;
                    message = "无法识别要移除的白名单条目。";
                    color = ChatFormatting.RED;
                }
                case NOTE_REQUIRED -> {
                    packetStatus = WhitelistReviewMutationResultPacket.Status.REQUIRED;
                    message = "移除白名单必须填写备注。";
                    color = ChatFormatting.RED;
                }
                case STALE -> {
                    packetStatus = WhitelistReviewMutationResultPacket.Status.STALE;
                    message = "该条目已被其他管理员重建，请重新选择后操作。";
                    color = ChatFormatting.YELLOW;
                }
            }
        } catch (IOException e) {
            packetStatus = WhitelistReviewMutationResultPacket.Status.SAVE_FAILED;
            message = "保存白名单失败：" + e.getMessage();
            color = ChatFormatting.RED;
        }
        rememberCompletedMutation(player, Action.REMOVE, idOrLink, note, expectedAddedAt, requestId,
                packetStatus, message);
        player.sendSystemMessage(Component.literal(message).withStyle(color));
        WhitelistReviewPacket.sendTo(player, entryOffset, removalOffset);
        sendMutationResult(player, Action.REMOVE, packetStatus, idOrLink, message, requestId);
    }

    private static void addComment(ServerPlayer player, String idOrLink, String text,
            String expectedAddedAt, int entryOffset, int removalOffset, long requestId) {
        WhitelistReviewMutationResultPacket.Status packetStatus = WhitelistReviewMutationResultPacket.Status.INVALID;
        String message = "审核评论未完成。";
        ChatFormatting color = ChatFormatting.RED;
        try {
            BiliWhitelistManager.CommentResult result = BiliWhitelistManager.addComment(
                    player.level().getServer(), idOrLink, player, text, expectedAddedAt);
            switch (result.status()) {
                case ADDED -> {
                    packetStatus = WhitelistReviewMutationResultPacket.Status.SUCCESS;
                    message = "已追加审核评论。";
                    color = ChatFormatting.GREEN;
                }
                case MISSING -> {
                    packetStatus = WhitelistReviewMutationResultPacket.Status.MISSING;
                    message = "白名单中没有：" + result.requestedId();
                    color = ChatFormatting.YELLOW;
                }
                case INVALID -> {
                    packetStatus = WhitelistReviewMutationResultPacket.Status.INVALID;
                    message = "无法识别要评论的白名单条目。";
                    color = ChatFormatting.RED;
                }
                case COMMENT_REQUIRED -> {
                    packetStatus = WhitelistReviewMutationResultPacket.Status.REQUIRED;
                    message = "审核评论不能为空。";
                    color = ChatFormatting.RED;
                }
                case STALE -> {
                    packetStatus = WhitelistReviewMutationResultPacket.Status.STALE;
                    message = "该条目已被其他管理员重建，请重新选择后评论。";
                    color = ChatFormatting.YELLOW;
                }
            }
        } catch (IOException e) {
            packetStatus = WhitelistReviewMutationResultPacket.Status.SAVE_FAILED;
            message = "保存白名单失败：" + e.getMessage();
            color = ChatFormatting.RED;
        }
        rememberCompletedMutation(player, Action.COMMENT, idOrLink, text, expectedAddedAt, requestId,
                packetStatus, message);
        player.sendSystemMessage(Component.literal(message).withStyle(color));
        WhitelistReviewPacket.sendTo(player, entryOffset, removalOffset);
        sendMutationResult(player, Action.COMMENT, packetStatus, idOrLink, message, requestId);
    }

    private record CompletedMutationKey(UUID playerId, Action action, long requestId) {
    }

    private record CompletedMutation(String idOrLink, String text, String expectedAddedAt, String message,
            long completedAtNanos) {
        private boolean matches(WhitelistReviewActionPacket payload) {
            return Objects.equals(idOrLink, safeFingerprint(payload.idOrLink()))
                    && Objects.equals(text, safeFingerprint(payload.text()))
                    && Objects.equals(expectedAddedAt, safeFingerprint(payload.expectedAddedAt()));
        }
    }

    public enum Action {
        REFRESH,
        REMOVE,
        EXPORT,
        PREVIEW,
        PREVIEW_SEEK,
        COMMENT;

        public static Action byId(int id) {
            Action[] values = values();
            return id >= 0 && id < values.length ? values[id] : REFRESH;
        }
    }
}
