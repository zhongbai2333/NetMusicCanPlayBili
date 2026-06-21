package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.logging.LogUtils;
import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.PlaybackSync;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.Objects;

/** 白名单审核“查看”预览所需的音视频播放信息。 */
public record WhitelistPreviewPacket(UUID previewId, String title, String rawUrl, String audioUrl, String videoUrl,
        int videoWidth, int videoHeight, int fps, int codecId, int durationSeconds, long elapsedMillis,
        boolean playing) implements CustomPacketPayload {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_URL_LENGTH = 8192;
    private static final int MAX_TITLE_LENGTH = 256;

    public static final Type<WhitelistPreviewPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("whitelist_preview"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistPreviewPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public WhitelistPreviewPacket decode(RegistryFriendlyByteBuf buffer) {
            return new WhitelistPreviewPacket(buffer.readUUID(), buffer.readUtf(MAX_TITLE_LENGTH),
                    buffer.readUtf(MAX_URL_LENGTH), buffer.readUtf(MAX_URL_LENGTH), buffer.readUtf(MAX_URL_LENGTH),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarLong(), buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, WhitelistPreviewPacket packet) {
            buffer.writeUUID(packet.previewId());
            buffer.writeUtf(safe(packet.title()), MAX_TITLE_LENGTH);
            buffer.writeUtf(safe(packet.rawUrl()), MAX_URL_LENGTH);
            buffer.writeUtf(safe(packet.audioUrl()), MAX_URL_LENGTH);
            buffer.writeUtf(safe(packet.videoUrl()), MAX_URL_LENGTH);
            buffer.writeVarInt(Math.max(1, packet.videoWidth()));
            buffer.writeVarInt(Math.max(1, packet.videoHeight()));
            buffer.writeVarInt(Math.max(1, packet.fps()));
            buffer.writeVarInt(packet.codecId());
            buffer.writeVarInt(Math.max(0, packet.durationSeconds()));
            buffer.writeVarLong(Math.max(0L, packet.elapsedMillis()));
            buffer.writeBoolean(packet.playing());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void resolveAndSend(ServerPlayer player, String idOrLink, long elapsedMillis, boolean playing) {
        if (player == null || idOrLink == null || idOrLink.isBlank()) {
            return;
        }
        String audioOnlyUrl = audioOnlyUrl(idOrLink);
        if (audioOnlyUrl != null) {
            player.sendSystemMessage(Component.literal("正在准备音频预览...").withStyle(ChatFormatting.GRAY));
            CompletableFuture.supplyAsync(() -> audioOnlyPacket(audioOnlyUrl, elapsedMillis, playing))
                    .whenComplete((packet, error) -> player.level().getServer().execute(() -> {
                        if (error != null) {
                            LOGGER.warn("白名单审核音频预览解析失败，将使用未知时长: {}", audioOnlyUrl, error);
                            PacketDistributor.sendToPlayer(player,
                                    fallbackAudioOnlyPacket(audioOnlyUrl, elapsedMillis, playing));
                            return;
                        }
                        PacketDistributor.sendToPlayer(player, packet);
                    }));
            return;
        }
        BiliApiClient.VideoSelection selection = BiliApiClient.extractVideoSelectionLenientWithShortLink(idOrLink);
        if (selection == null) {
            player.sendSystemMessage(Component.literal("当前条目不是可预览的 B站视频。")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        BiliApiClient.VideoSelection finalSelection = selection;
        player.sendSystemMessage(Component.literal("正在准备预览...").withStyle(ChatFormatting.GRAY));
        CompletableFuture.supplyAsync(() -> {
            try {
                BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(finalSelection.videoId(),
                        finalSelection.page());
                UUID previewId = UUID.nameUUIDFromBytes(("whitelist-preview:" + finalSelection.videoId().asInputText()
                        + ":" + finalSelection.page()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                long durationMillis = Math.max(0L, info.duration()) * 1000L;
                long startMillis = clampMillis(elapsedMillis, durationMillis);
                String sessionId = previewSession(previewId, startMillis);
                String storedSelection = BiliApiClient.formatStoredVideoSelection(finalSelection.videoId(),
                        finalSelection.page());
                String syncedSelection = PlaybackSync.withSync(storedSelection, sessionId, startMillis,
                    durationMillis);
                return new WhitelistPreviewPacket(previewId, info.displayTitle(), storedSelection, syncedSelection,
                    storedSelection, 1, 1, 30, 0, Math.max(0, info.duration()), startMillis, playing);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).whenComplete((packet, error) -> player.level().getServer().execute(() -> {
            if (error != null) {
                LOGGER.warn("白名单审核预览解析失败: {}", idOrLink, error);
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                player.sendSystemMessage(Component.literal("预览解析失败：" + cause.getMessage())
                        .withStyle(ChatFormatting.RED));
                return;
            }
            PacketDistributor.sendToPlayer(player, packet);
        }));
    }

    public static MP4PlaybackSyncPacket toAudioSync(WhitelistPreviewPacket packet) {
        String sessionId = previewSession(packet.previewId(), packet.elapsedMillis());
        return new MP4PlaybackSyncPacket(packet.previewId(), packet.previewId(), MP4PlaybackSyncPacket.SOURCE_PLAYER,
                -1, 0.0D, 0.0D, 0.0D, packet.playing(), 0, packet.audioUrl(), packet.rawUrl(), packet.title(),
                packet.durationSeconds(), 850, sessionId, packet.elapsedMillis(), false);
    }

    public static String sessionId(UUID previewId, long elapsedMillis) {
        return previewSession(previewId, elapsedMillis);
    }

    public static MP4PlaybackSyncPacket stopAudio(UUID previewId) {
        return MP4PlaybackSyncPacket.stop(previewId, previewId, 0);
    }

    public static void handle(WhitelistPreviewPacket payload, IPayloadContext context) {
        context.enqueueWork(
                () -> com.zhongbai233.net_music_can_play_bili.gui.WhitelistPreviewScreen.openOrUpdate(payload));
    }

    private static String previewSession(UUID previewId, long elapsedMillis) {
        return previewId + "-whitelist-preview-" + Math.max(0L, elapsedMillis);
    }

    private static WhitelistPreviewPacket audioOnlyPacket(String rawUrl, long elapsedMillis, boolean playing) {
        String safeRawUrl = Objects.requireNonNull(rawUrl, "rawUrl");
        ItemMusicCD.SongInfo original = new ItemMusicCD.SongInfo(safeRawUrl,
                Objects.requireNonNull(audioTitle(safeRawUrl)), 0, false);
        ItemMusicCD.SongInfo resolved = MusicPlayResolverManager.resolve(original).join();
        String playUrl = resolved != null && resolved.songUrl != null && !resolved.songUrl.isBlank()
                ? resolved.songUrl
                : safeRawUrl;
        String title = resolved != null && resolved.songName != null && !resolved.songName.isBlank()
                ? resolved.songName
                : audioTitle(safeRawUrl);
        int durationSeconds = resolved != null ? Math.max(0, resolved.songTime) : 0;
        return audioOnlyPacket(safeRawUrl, playUrl, title, durationSeconds, elapsedMillis, playing);
    }

    private static WhitelistPreviewPacket fallbackAudioOnlyPacket(String rawUrl, long elapsedMillis, boolean playing) {
        return audioOnlyPacket(rawUrl, rawUrl, audioTitle(rawUrl), 0, elapsedMillis, playing);
    }

    private static WhitelistPreviewPacket audioOnlyPacket(String rawUrl, String playUrl, String title,
            int durationSeconds, long elapsedMillis, boolean playing) {
        UUID previewId = UUID.nameUUIDFromBytes(("whitelist-preview-audio:" + rawUrl)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long durationMillis = Math.max(0, durationSeconds) * 1000L;
        long startMillis = clampMillis(elapsedMillis, durationMillis);
        String sessionId = previewSession(previewId, startMillis);
        String syncedAudioUrl = PlaybackSync.withSync(playUrl, sessionId, startMillis, durationMillis);
        return new WhitelistPreviewPacket(previewId, title, rawUrl, syncedAudioUrl, "",
                1, 1, 30, 0, Math.max(0, durationSeconds), startMillis, playing);
    }

    private static String audioOnlyUrl(String idOrLink) {
        String value = idOrLink == null ? "" : idOrLink.trim();
        if (value.regionMatches(true, 0, "url:", 0, 4)) {
            value = value.substring(4).trim();
        }
        if (value.regionMatches(true, 0, "bili:", 0, 5)) {
            return null;
        }
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("ftp://")) {
            return value;
        }
        return null;
    }

    private static String audioTitle(String rawUrl) {
        try {
            java.net.URI uri = java.net.URI.create(rawUrl);
            String host = uri.getHost();
            return host == null || host.isBlank() ? "白名单音频预览" : "白名单音频预览 · " + host;
        } catch (Exception ignored) {
            return "白名单音频预览";
        }
    }

    private static long clampMillis(long value, long totalMillis) {
        long safe = Math.max(0L, value);
        return totalMillis > 0L ? Math.min(totalMillis, safe) : safe;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
