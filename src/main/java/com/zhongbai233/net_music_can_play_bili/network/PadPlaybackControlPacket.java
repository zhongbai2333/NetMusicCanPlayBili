package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public record PadPlaybackControlPacket(Action action, UUID deviceId, UUID pointId, long targetMillis)
        implements CustomPacketPayload {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean VIDEO_DEBUG_LOG = Boolean.getBoolean("ncpb.pad.video.debug_log");
    private static final Set<UUID> PENDING_STARTS = ConcurrentHashMap.newKeySet();

    public static final Type<PadPlaybackControlPacket> TYPE = new Type<>(NetworkPayloadIds.id("pad_playback_control"));

    private static final StreamCodec<RegistryFriendlyByteBuf, Action> ACTION_CODEC = new StreamCodec<>() {
        @Override
        public Action decode(RegistryFriendlyByteBuf buffer) {
            return Action.byId(buffer.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, Action action) {
            buffer.writeVarInt(action.id());
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, PadPlaybackControlPacket> STREAM_CODEC = StreamCodec
            .of((buffer, packet) -> {
                ACTION_CODEC.encode(buffer, packet.action());
                buffer.writeUUID(packet.deviceId());
                buffer.writeBoolean(packet.pointId() != null);
                if (packet.pointId() != null) {
                    buffer.writeUUID(packet.pointId());
                }
                buffer.writeVarLong(Math.max(0L, packet.targetMillis()));
            }, buffer -> new PadPlaybackControlPacket(ACTION_CODEC.decode(buffer), buffer.readUUID(),
                    buffer.readBoolean() ? buffer.readUUID() : null, buffer.readVarLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PadPlaybackControlPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || payload.deviceId() == null) {
            return;
        }
        if (VIDEO_DEBUG_LOG) {
            LOGGER.info("Pad playback control received: player={} action={} device={} point={} target={}ms",
                    player.getUUID(), payload.action(), payload.deviceId(), payload.pointId(), payload.targetMillis());
        }
        if (!NetworkRateLimiter.allow(player.getUUID(), "pad_playback_control", 8)) {
            LOGGER.debug("丢弃过频 Pad 播放控制包: player={} action={}", player.getUUID(), payload.action());
            return;
        }
        ItemStack stack = PadItem.findByDeviceId(player, payload.deviceId());
        if (!PadItem.isPad(stack)) {
            return;
        }
        switch (payload.action()) {
            case STOP, PAUSE -> stop(player, payload.deviceId());
            case START, RESTART, SEEK -> start(player, stack, payload.deviceId(), payload.pointId(),
                    payload.action() == Action.RESTART ? 0L : Math.max(0L, payload.targetMillis()));
        }
    }

    static void startFromServer(ServerPlayer player, ItemStack stack, UUID deviceId, UUID pointId, long targetMillis) {
        start(player, stack, deviceId, pointId, targetMillis);
    }

    static void stopFromServer(ServerPlayer player, UUID deviceId) {
        stop(player, deviceId);
    }

    private static void start(ServerPlayer player, ItemStack stack, UUID deviceId, UUID pointId, long targetMillis) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        PadDocument document = PadDocumentStore.getOrCreate(level, deviceId, stack);
        PadTriggerPoint point = point(document, pointId);
        if (point == null) {
            point = activePoint(document, deviceId);
        }
        if (point == null) {
            return;
        }
        PadTriggerPoint resolvedPoint = point;
        PadMediaEntry media = document.media(resolvedPoint.mediaId()).orElse(null);
        if (media == null || !PadItem.isNetMusicDisc(media.disc())) {
            return;
        }
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(media.disc());
        if (songInfo == null) {
            stop(player, deviceId);
            return;
        }
        if (songInfo.vip && !MusicPlayResolverManager.canResolve(songInfo)) {
            stop(player, deviceId);
            return;
        }
        if (!isPlaybackAllowed(player, songInfo.songUrl)) {
            stop(player, deviceId);
            return;
        }
        if (!PENDING_STARTS.add(deviceId)) {
            return;
        }
        ItemMusicCD.SongInfo original = songInfo.clone();
        MusicPlayResolverManager.resolve(original.clone())
                .thenAcceptAsync(resolved -> applyResolvedPlayback(player, stack, deviceId, resolvedPoint, original, resolved,
                        targetMillis), player.level().getServer())
                .exceptionally(error -> {
                    PENDING_STARTS.remove(deviceId);
                    LOGGER.error("Pad 解析播放失败: point={} song={}", resolvedPoint.pointId(), original.songName, error);
                    return null;
                });
    }

    private static void applyResolvedPlayback(ServerPlayer player, ItemStack stack, UUID deviceId,
            PadTriggerPoint point,
            ItemMusicCD.SongInfo original, ItemMusicCD.SongInfo resolved, long targetMillis) {
        PENDING_STARTS.remove(deviceId);
        if (!PadItem.isPad(stack) || !(player.level() instanceof ServerLevel)) {
            return;
        }
        PadDocument document = PadDocumentStore.getOrCreate((ServerLevel) player.level(), deviceId, stack);
        PadMediaEntry media = document.media(point.mediaId()).orElse(null);
        if (media == null) {
            return;
        }
        ItemMusicCD.SongInfo current = ItemMusicCD.getSongInfo(media.disc());
        if (current == null || !Objects.equals(current.songUrl, original.songUrl)) {
            return;
        }
        if (!isPlaybackAllowed(player, original.songUrl)) {
            stop(player, deviceId);
            return;
        }
        String rawUrl = original.songUrl != null ? original.songUrl : "";
        String playUrl = resolved.songUrl != null && !resolved.songUrl.isBlank() ? resolved.songUrl : rawUrl;
        if (BiliApiClient.isStoredVideoSelection(rawUrl)) {
            playUrl = rawUrl;
        }
        if (playUrl.isBlank()) {
            stop(player, deviceId);
            return;
        }
        String songName = resolved.songName != null && !resolved.songName.isBlank() ? resolved.songName
                : original.songName;
        if (songName == null || songName.isBlank()) {
            songName = point.name().isBlank() ? "Pad 点位 #" + point.mediaId() : point.name();
        }
        int durationSeconds = Math.max(1, resolved.songTime > 0 ? resolved.songTime : original.songTime);
        long elapsedMillis = Math.min(Math.max(0L, targetMillis), Math.max(0L, durationSeconds * 1000L - 50L));
        String sessionId = deviceId + "-pad-" + point.pointId() + "-" + System.nanoTime();
        String syncedPlayUrl = PlaybackSync.withSync(playUrl, sessionId, elapsedMillis, durationSeconds * 1000L);

        if (VIDEO_DEBUG_LOG) {
            LOGGER.info(
                    "Pad playback start resolved: player={} device={} point={} media={} song='{}' rawVideo={} session={} elapsed={}ms duration={}s",
                    player.getUUID(), deviceId, point.pointId(), point.mediaId(), songName,
                    BiliApiClient.isStoredVideoSelection(rawUrl), sessionId, elapsedMillis, durationSeconds);
        }

        ServerMediaPlayback.start(player, ServerMediaPlayback.StartRequest.player(player, deviceId, point.mediaId(),
                syncedPlayUrl, rawUrl, songName, durationSeconds, point.volumePerMille(), sessionId, elapsedMillis));
    }

    private static void stop(ServerPlayer player, UUID deviceId) {
        ServerMediaPlayback.stopAndBroadcast(player, deviceId);
    }

    private static PadTriggerPoint point(PadDocument document, UUID pointId) {
        if (document == null || pointId == null) {
            return null;
        }
        return document.triggerPoints().stream().filter(point -> pointId.equals(point.pointId())).findFirst()
                .orElse(null);
    }

    private static PadTriggerPoint activePoint(PadDocument document, UUID deviceId) {
        if (document == null || deviceId == null) {
            return null;
        }
        int activeMediaId = MP4PlaybackSyncManager.activeQueueIndex(deviceId);
        if (activeMediaId < 0) {
            return null;
        }
        return document.triggerPoints().stream()
                .filter(point -> point.mediaId() == activeMediaId)
                .findFirst()
                .orElse(null);
    }

    private static boolean isPlaybackAllowed(ServerPlayer player, String sourceUrl) {
        if (BiliSongInfoSanitizer.isForbiddenBiliDirectUrl(sourceUrl)) {
            if (player != null) {
                player.sendSystemMessage(BiliWhitelistManager.denialMessage(player, sourceUrl, "Pad播放"));
            }
            return false;
        }
        if (player == null || !BiliWhitelistManager.enabled()
                || BiliWhitelistManager.canonicalResource(sourceUrl).isEmpty()) {
            return true;
        }
        if (BiliWhitelistManager.isAllowed(player.level().getServer(), sourceUrl)) {
            return true;
        }
        player.sendSystemMessage(BiliWhitelistManager.denialMessage(player, sourceUrl, "Pad播放"));
        return false;
    }

    public enum Action {
        START,
        PAUSE,
        STOP,
        RESTART,
        SEEK;

        public int id() {
            return ordinal();
        }

        public static Action byId(int id) {
            Action[] values = values();
            return id >= 0 && id < values.length ? values[id] : START;
        }
    }
}