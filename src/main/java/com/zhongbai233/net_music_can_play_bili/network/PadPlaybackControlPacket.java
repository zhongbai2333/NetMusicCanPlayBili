package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.PadDiagnosticsProperties;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
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
import java.util.UUID;

public record PadPlaybackControlPacket(Action action, UUID deviceId, UUID pointId, long targetMillis)
        implements CustomPacketPayload {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean VIDEO_DEBUG_LOG = PadDiagnosticsProperties.videoDebugLogEnabled();
    private static final PadResolveIntentRegistry RESOLVE_INTENTS = new PadResolveIntentRegistry();

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

    static boolean retry(ServerPlayer player, ItemStack stack, UUID deviceId, UUID pointId,
            PlaybackSessionId expectedSessionId, long targetMillis) {
        if (player == null || !PadItem.isPad(stack) || deviceId == null || pointId == null
                || expectedSessionId == null || !deviceId.equals(PadItem.readDeviceId(stack))
                || !(player.level() instanceof ServerLevel level)) {
            return false;
        }
        PadDocument document = PadDocumentStore.getOrCreate(level, deviceId, stack);
        PadTriggerPoint point = point(document, pointId);
        if (point == null || !MP4PlaybackSyncManager.matchesActiveSession(deviceId, expectedSessionId,
                point.mediaId(), rawUrl(document, point))) {
            return false;
        }
        PadMediaEntry media = document.media(point.mediaId()).orElse(null);
        ItemMusicCD.SongInfo songInfo = media != null ? ItemMusicCD.getSongInfo(media.disc()) : null;
        if (songInfo == null || !PadItem.isNetMusicDisc(media.disc())
                || songInfo.vip && !MusicPlayResolverManager.canResolve(songInfo)
                || !isPlaybackAllowed(player, songInfo.songUrl)) {
            return false;
        }
        PadResolveIntentRegistry.Intent intent = RESOLVE_INTENTS.beginRetryIfIdle(player.getUUID(), deviceId, pointId,
                point.mediaId(), songInfo.songUrl, expectedSessionId);
        if (intent == null) {
            return false;
        }
        resolve(player, deviceId, point, songInfo.clone(), targetMillis, intent);
        return true;
    }

    static void invalidateResolve(UUID deviceId) {
        RESOLVE_INTENTS.invalidate(deviceId);
    }

    static void clearResolveIntents() {
        RESOLVE_INTENTS.clear();
    }

    static void invalidatePlayer(UUID playerId) {
        RESOLVE_INTENTS.invalidateOwner(playerId);
    }

    private static void start(ServerPlayer player, ItemStack stack, UUID deviceId, UUID pointId, long targetMillis) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        RESOLVE_INTENTS.invalidate(deviceId);
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
        ItemMusicCD.SongInfo original = songInfo.clone();
        PadResolveIntentRegistry.Intent intent = RESOLVE_INTENTS.replaceCommand(player.getUUID(), deviceId,
                resolvedPoint.pointId(), resolvedPoint.mediaId(), original.songUrl);
        resolve(player, deviceId, resolvedPoint, original, targetMillis, intent);
    }

    private static void resolve(ServerPlayer player, UUID deviceId, PadTriggerPoint point,
            ItemMusicCD.SongInfo original, long targetMillis, PadResolveIntentRegistry.Intent intent) {
        MusicPlayResolverManager.resolve(original.clone()).whenCompleteAsync((resolved, error) -> {
            if (!RESOLVE_INTENTS.isCurrent(deviceId, intent)) {
                return;
            }
            if (error != null || resolved == null) {
                RESOLVE_INTENTS.complete(deviceId, intent);
                if (error != null) {
                    LOGGER.error("Pad 解析播放失败: point={} song={}", point.pointId(), original.songName, error);
                } else {
                    LOGGER.warn("Pad 解析播放返回空结果: point={} song={}", point.pointId(), original.songName);
                }
                return;
            }
            applyResolvedPlayback(player, deviceId, original, resolved, targetMillis, intent);
        }, player.level().getServer());
    }

    private static void applyResolvedPlayback(ServerPlayer player, UUID deviceId,
            ItemMusicCD.SongInfo original, ItemMusicCD.SongInfo resolved, long targetMillis,
            PadResolveIntentRegistry.Intent intent) {
        if (intent == null || !RESOLVE_INTENTS.isCurrent(deviceId, intent)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack currentStack = PadItem.findByDeviceId(player, deviceId);
        if (!PadItem.isPad(currentStack) || !deviceId.equals(PadItem.readDeviceId(currentStack))) {
            RESOLVE_INTENTS.complete(deviceId, intent);
            return;
        }
        PadDocument document = PadDocumentStore.getOrCreate(level, deviceId, currentStack);
        PadTriggerPoint point = point(document, intent.pointId());
        if (!RESOLVE_INTENTS.isCurrent(deviceId, intent) || point == null || point.mediaId() != intent.mediaId()) {
            RESOLVE_INTENTS.complete(deviceId, intent);
            return;
        }
        PadMediaEntry media = document.media(point.mediaId()).orElse(null);
        if (media == null) {
            RESOLVE_INTENTS.complete(deviceId, intent);
            return;
        }
        ItemMusicCD.SongInfo current = ItemMusicCD.getSongInfo(media.disc());
        String currentSourceUrl = current != null && current.songUrl != null ? current.songUrl : "";
        String originalSourceUrl = original.songUrl != null ? original.songUrl : "";
        if (current == null || !Objects.equals(currentSourceUrl, originalSourceUrl)
                || !Objects.equals(currentSourceUrl, intent.sourceUrl())) {
            RESOLVE_INTENTS.complete(deviceId, intent);
            return;
        }
        if (!isPlaybackAllowed(player, original.songUrl)) {
            RESOLVE_INTENTS.complete(deviceId, intent);
            stop(player, deviceId);
            return;
        }
        String rawUrl = original.songUrl != null ? original.songUrl : "";
        String playUrl = resolved.songUrl != null && !resolved.songUrl.isBlank() ? resolved.songUrl : rawUrl;
        if (BiliApiClient.isStoredVideoSelection(rawUrl)) {
            playUrl = rawUrl;
        }
        if (playUrl.isBlank()) {
            RESOLVE_INTENTS.complete(deviceId, intent);
            if (!intent.retry()) {
                stop(player, deviceId);
            }
            return;
        }
        String songName = resolved.songName != null && !resolved.songName.isBlank() ? resolved.songName
                : original.songName;
        if (songName == null || songName.isBlank()) {
            songName = point.name().isBlank() ? "Pad 点位 #" + point.mediaId() : point.name();
        }
        int durationSeconds = Math.max(1, resolved.songTime > 0 ? resolved.songTime : original.songTime);
        long elapsedMillis = Math.min(Math.max(0L, targetMillis), Math.max(0L, durationSeconds * 1000L - 50L));
        PlaybackSessionId sessionId = intent.retry()
                ? intent.expectedSessionId()
                : PadPlaybackSessionIds.create(deviceId, point.pointId(), intent.generation().value());
        String syncedPlayUrl = PlaybackSync.withSync(playUrl, sessionId, elapsedMillis, durationSeconds * 1000L);

        if (VIDEO_DEBUG_LOG) {
            LOGGER.info(
                    "Pad playback start resolved: player={} device={} point={} media={} song='{}' rawVideo={} session={} elapsed={}ms duration={}s",
                    player.getUUID(), deviceId, point.pointId(), point.mediaId(), songName,
                    BiliApiClient.isStoredVideoSelection(rawUrl), sessionId, elapsedMillis, durationSeconds);
        }

        if (intent.retry()) {
            String resolvedSongName = songName;
            String resolvedPlayUrl = playUrl;
            boolean refreshed = RESOLVE_INTENTS.commitIfCurrent(deviceId, intent,
                    () -> MP4PlaybackSyncManager.refreshActiveSession(player, deviceId, sessionId,
                            point.mediaId(), rawUrl, resolvedPlayUrl, resolvedSongName, durationSeconds,
                            elapsedMillis));
            if (VIDEO_DEBUG_LOG && !refreshed) {
                LOGGER.info("Pad playback retry rejected at final admission: device={} point={} session={}",
                        deviceId, point.pointId(), sessionId);
            }
            return;
        }
        String resolvedSongName = songName;
        RESOLVE_INTENTS.commitIfCurrent(deviceId, intent, () -> {
            ServerMediaPlayback.start(player, ServerMediaPlayback.StartRequest.player(player, deviceId,
                    point.mediaId(), syncedPlayUrl, rawUrl, resolvedSongName, durationSeconds,
                    point.volumePerMille(), sessionId.value(), elapsedMillis));
            return true;
        });
    }

    private static void stop(ServerPlayer player, UUID deviceId) {
        RESOLVE_INTENTS.invalidate(deviceId);
        ServerMediaPlayback.stopAndBroadcast(player, deviceId);
    }

    private static String rawUrl(PadDocument document, PadTriggerPoint point) {
        if (document == null || point == null) {
            return "";
        }
        PadMediaEntry media = document.media(point.mediaId()).orElse(null);
        ItemMusicCD.SongInfo songInfo = media != null ? ItemMusicCD.getSongInfo(media.disc()) : null;
        return songInfo != null && songInfo.songUrl != null ? songInfo.songUrl : "";
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
