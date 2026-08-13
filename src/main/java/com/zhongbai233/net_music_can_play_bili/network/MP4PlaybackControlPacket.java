package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record MP4PlaybackControlPacket(Action action, int selectedQueueIndex, int volumePerMille, long targetMillis,
        UUID deviceId)
        implements CustomPacketPayload {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<MP4PlaybackControlPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("mp4_playback_control"));

    public MP4PlaybackControlPacket(Action action, int selectedQueueIndex, int volumePerMille, long targetMillis) {
        this(action, selectedQueueIndex, volumePerMille, targetMillis, null);
    }

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

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4PlaybackControlPacket> STREAM_CODEC = StreamCodec
            .of((buffer, packet) -> {
                ACTION_CODEC.encode(buffer, packet.action());
                buffer.writeInt(packet.selectedQueueIndex());
                buffer.writeInt(packet.volumePerMille());
                buffer.writeVarLong(packet.targetMillis());
                buffer.writeBoolean(packet.deviceId() != null);
                if (packet.deviceId() != null) {
                    buffer.writeUUID(packet.deviceId());
                }
            }, buffer -> new MP4PlaybackControlPacket(ACTION_CODEC.decode(buffer), buffer.readInt(), buffer.readInt(),
                    buffer.readVarLong(), buffer.readBoolean() ? buffer.readUUID() : null));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MP4PlaybackControlPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!NetworkRateLimiter.allow(player.getUUID(), "mp4_playback_control", 8)) {
            LOGGER.debug("丢弃过频 MP4 播放控制包: player={} action={}", player.getUUID(),
                    payload.action());
            return;
        }
        ItemStack stack = nonNullStack(payload.deviceId() != null ? MP4Item.findByDeviceId(player, payload.deviceId())
                : MP4Item.findPlayableInInventory(player));
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }

        ServerLevel level = (ServerLevel) player.level();
        UUID deviceId = MP4DeviceIdentity.getOrCreateUnique(level, player, stack);
        if (deviceId == null) {
            return;
        }
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        List<ItemStack> queue = queueForPlayback(entry, stack);
        int index = queue.isEmpty() ? 0 : clamp(payload.selectedQueueIndex(), 0, queue.size() - 1);

        boolean selectedTrackChanged = index != entry.state().selectedQueueIndex();
        MP4Item.State state = withSelectedIndex(entry.state(), index);
        int volume = clamp(payload.volumePerMille(), 0, 1000);
        switch (payload.action()) {
            case STOP, PAUSE -> {
                long elapsedMillis = MP4PlaybackSyncManager.currentElapsedMillis(player, deviceId,
                        Math.max(0L, payload.targetMillis()));
                MP4Item.State newState = withPlayback(state, false, index, volume,
                        MP4PlaybackSyncManager.currentProgressPerMille(player,
                                progressPerMille(queue, index, elapsedMillis, state.progressPerMille())));
                MP4DeviceStateStore.update(level, deviceId, new MP4DeviceStateStore.DeviceEntry(newState, queue,
                        elapsedMillis, durationSeconds(queue, index), ""));
                MP4PlaybackSyncManager.recordProgress(player, deviceId, index, elapsedMillis, queue, volume, false);
                MP4PlaybackSyncManager.stop(player, deviceId);
                broadcastStop(player, deviceId, index);
            }
            case VOLUME -> {
                MP4DeviceStateStore.updateState(level, deviceId,
                        withPlayback(state, state.playing(), index, volume, state.progressPerMille()));
                MP4PlaybackSyncManager.updateVolume(deviceId, volume);
            }
            case START -> {
                long requestedMillis = selectedTrackChanged ? 0L : Math.max(0L, payload.targetMillis());
                if (!MP4PlaybackSyncManager.resumeExisting(player, deviceId, index, volume, requestedMillis)) {
                    startPlayback(player, stack, queue, index, volume, requestedMillis);
                }
            }
            case SEEK -> startPlayback(player, stack, queue, index, volume, Math.max(0L, payload.targetMillis()));
            case RESTART -> startPlayback(player, stack, queue, index, volume, 0L);
        }
    }

    static boolean retryPlayback(ServerPlayer player, ItemStack stack, UUID deviceId,
            PlaybackSessionId expectedSessionId, long targetMillis) {
        if (player == null || stack == null || deviceId == null || expectedSessionId == null
                || !(stack.getItem() instanceof MP4Item)
                || !deviceId.equals(MP4Item.readDeviceId(stack))
                || !(player.level() instanceof ServerLevel level)) {
            return false;
        }
        MP4PlaybackRetryAdmission.Attempt attempt = MP4PlaybackSyncManager.beginRetryResolve(deviceId,
                expectedSessionId);
        if (attempt == null) {
            return false;
        }
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        List<ItemStack> queue = queueForPlayback(entry, stack);
        int index = attempt.queueIndex();
        if (index < 0 || index >= queue.size()) {
            MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
            return false;
        }
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(queueStack(queue, index));
        if (songInfo == null || !Objects.equals(attempt.sourceUrl(), songInfo.songUrl)
                || songInfo.vip && !MusicPlayResolverManager.canResolve(songInfo)
                || !isPlaybackAllowed(player, songInfo.songUrl)) {
            MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
            return false;
        }
        ItemMusicCD.SongInfo original = songInfo.clone();
        MusicPlayResolverManager.resolve(original.clone()).whenCompleteAsync((resolved, error) -> {
            if (!MP4PlaybackSyncManager.isCurrentRetryResolve(deviceId, attempt)) {
                return;
            }
            if (error != null || resolved == null) {
                MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
                if (error != null) {
                    LOGGER.error("MP4 重试刷新直链失败: {}", original.songName, error);
                } else {
                    LOGGER.warn("MP4 重试刷新直链返回空结果: {}", original.songName);
                }
                return;
            }
            applyResolvedRetry(player, stack, deviceId, attempt, targetMillis, original, resolved);
        }, level.getServer());
        return true;
    }

    private static void applyResolvedRetry(ServerPlayer player, ItemStack stack, UUID deviceId,
            MP4PlaybackRetryAdmission.Attempt attempt, long targetMillis,
            ItemMusicCD.SongInfo original, ItemMusicCD.SongInfo resolved) {
        if (!MP4PlaybackSyncManager.isCurrentRetryResolve(deviceId, attempt)
                || !(stack.getItem() instanceof MP4Item)
                || !deviceId.equals(MP4Item.readDeviceId(stack))
                || !(player.level() instanceof ServerLevel level)) {
            MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
            return;
        }
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        List<ItemStack> queue = queueForPlayback(entry, stack);
        int index = attempt.queueIndex();
        if (index < 0 || index >= queue.size()) {
            MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
            return;
        }
        ItemMusicCD.SongInfo current = ItemMusicCD.getSongInfo(queueStack(queue, index));
        if (current == null || !Objects.equals(attempt.sourceUrl(), current.songUrl)
                || !Objects.equals(original.songUrl, current.songUrl)) {
            MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
            return;
        }
        if (!isPlaybackAllowed(player, original.songUrl)) {
            MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
            MP4PlaybackSyncManager.stop(player, deviceId);
            broadcastStop(player, deviceId, index);
            return;
        }
        String rawUrl = original.songUrl != null ? original.songUrl : "";
        String playUrl = resolved.songUrl != null && !resolved.songUrl.isBlank() ? resolved.songUrl : rawUrl;
        if (BiliApiClient.isStoredVideoSelection(rawUrl)) {
            playUrl = rawUrl;
        }
        if (playUrl.isBlank()) {
            MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
            return;
        }
        String songName = resolved.songName != null && !resolved.songName.isBlank() ? resolved.songName
                : original.songName;
        int durationSeconds = Math.max(1, resolved.songTime > 0 ? resolved.songTime : original.songTime);
        MP4PlaybackSyncManager.applyRetryResolved(player, deviceId, attempt, playUrl,
                songName == null ? "" : songName, durationSeconds, Math.max(0L, targetMillis));
    }

    private static void startPlayback(ServerPlayer player, ItemStack stack, List<ItemStack> queue, int index,
            int volumePerMille, long targetMillis) {
        if (queue.isEmpty() || index < 0 || index >= queue.size()) {
            UUID deviceId = MP4Item.readDeviceId(stack);
            if (player.level() instanceof ServerLevel level) {
                MP4Item.State state = MP4DeviceStateStore.getOrCreate(level, deviceId, stack).state();
                MP4DeviceStateStore.updateState(level, deviceId,
                        withPlayback(state, false, index, volumePerMille, state.progressPerMille()));
            }
            MP4PlaybackSyncManager.stop(player, deviceId);
            broadcastStop(player, deviceId, index);
            return;
        }

        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(queueStack(queue, index));
        if (songInfo == null) {
            UUID deviceId = MP4Item.readDeviceId(stack);
            MP4PlaybackSyncManager.stop(player, deviceId);
            broadcastStop(player, deviceId, index);
            return;
        }
        if (songInfo.vip && !MusicPlayResolverManager.canResolve(songInfo)) {
            UUID deviceId = MP4Item.readDeviceId(stack);
            MP4PlaybackSyncManager.stop(player, deviceId);
            broadcastStop(player, deviceId, index);
            return;
        }
        if (!isPlaybackAllowed(player, songInfo.songUrl)) {
            UUID deviceId = MP4Item.readDeviceId(stack);
            MP4PlaybackSyncManager.stop(player, deviceId);
            broadcastStop(player, deviceId, index);
            return;
        }

        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId == null) {
            return;
        }
        ItemMusicCD.SongInfo original = songInfo.clone();
        MP4ResolveIntentRegistry.Intent intent = MP4PlaybackSyncManager.beginCommandResolve(deviceId, index,
                original.songUrl);
        MusicPlayResolverManager.resolve(original.clone())
                .whenCompleteAsync((resolved, error) -> {
                    if (!MP4PlaybackSyncManager.isCurrentResolve(deviceId, intent)) {
                        return;
                    }
                    if (error != null) {
                        MP4PlaybackSyncManager.completeResolve(deviceId, intent);
                        LOGGER.error("MP4 解析播放失败: {}", original.songName, error);
                        return;
                    }
                    applyResolvedPlayback(player, stack, deviceId, index, volumePerMille, targetMillis,
                            original, resolved, intent);
                }, player.level().getServer());
    }

    public static void restartSelected(ServerPlayer player, ItemStack stack, UUID deviceId, int index,
            int volumePerMille) {
        if (player == null || deviceId == null || !(stack.getItem() instanceof MP4Item)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        List<ItemStack> queue = queueForPlayback(entry, stack);
        int safeIndex = queue.isEmpty() ? 0 : clamp(index, 0, queue.size() - 1);
        MP4PlaybackSyncManager.stop(player, deviceId);
        broadcastStop(player, deviceId, safeIndex);
        if (queue.isEmpty()) {
            return;
        }
        MP4Item.State state = MP4DeviceStateStore.getOrCreate(level, deviceId, stack).state();
        MP4DeviceStateStore.update(level, deviceId, new MP4DeviceStateStore.DeviceEntry(
                withPlayback(state, true, safeIndex, volumePerMille, 0), queue, 0L, 0, ""));
        startPlayback(player, stack, queue, safeIndex, clamp(volumePerMille, 0, 1000), 0L);
    }

    private static void applyResolvedPlayback(ServerPlayer player, ItemStack stack, UUID requestedDeviceId,
            int index, int volumePerMille,
            long targetMillis, ItemMusicCD.SongInfo original, ItemMusicCD.SongInfo resolved,
            MP4ResolveIntentRegistry.Intent intent) {
        if (!MP4PlaybackSyncManager.isCurrentResolve(requestedDeviceId, intent)) {
            return;
        }
        if (!(stack.getItem() instanceof MP4Item)) {
            MP4PlaybackSyncManager.completeResolve(requestedDeviceId, intent);
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        UUID deviceId = MP4DeviceIdentity.getOrCreateUnique(level, player, stack);
        if (!requestedDeviceId.equals(deviceId)) {
            MP4PlaybackSyncManager.completeResolve(requestedDeviceId, intent);
            return;
        }
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        List<ItemStack> queue = queueForPlayback(entry, stack);
        if (index < 0 || index >= queue.size()) {
            MP4PlaybackSyncManager.completeResolve(deviceId, intent);
            return;
        }
        ItemMusicCD.SongInfo current = ItemMusicCD.getSongInfo(queueStack(queue, index));
        if (current == null || !Objects.equals(current.songUrl, original.songUrl)) {
            MP4PlaybackSyncManager.completeResolve(deviceId, intent);
            return;
        }
        if (!isPlaybackAllowed(player, original.songUrl)) {
            MP4PlaybackSyncManager.stop(player, deviceId);
            broadcastStop(player, deviceId, index);
            return;
        }

        String rawUrl = original.songUrl != null ? original.songUrl : "";
        String playUrl = resolved.songUrl != null && !resolved.songUrl.isBlank() ? resolved.songUrl : rawUrl;
        if (BiliApiClient.isStoredVideoSelection(rawUrl)) {
            playUrl = rawUrl;
        }
        if (playUrl.isBlank()) {
            MP4PlaybackSyncManager.stop(player, deviceId);
            broadcastStop(player, deviceId, index);
            return;
        }
        String songName = resolved.songName != null && !resolved.songName.isBlank() ? resolved.songName
                : original.songName;
        int durationSeconds = Math.max(1, resolved.songTime > 0 ? resolved.songTime : original.songTime);
        long elapsedMillis = Math.min(Math.max(0L, targetMillis), Math.max(0L, durationSeconds * 1000L - 50L));
        String sessionId = deviceId + "-mp4-" + System.nanoTime();
        String syncedPlayUrl = PlaybackSync.withSync(playUrl, sessionId, elapsedMillis, durationSeconds * 1000L);

        MP4Item.State state = entry.state();
        int progress = durationSeconds <= 0 ? 0
                : (int) Math.round(elapsedMillis * 1000.0D / (durationSeconds * 1000.0D));
        MP4DeviceStateStore.update(level, deviceId, new MP4DeviceStateStore.DeviceEntry(
                withPlayback(state, true, index, volumePerMille, progress), queue, elapsedMillis, durationSeconds,
                sessionId));
        MP4PlaybackSyncManager.recordProgress(player, deviceId, index, elapsedMillis, durationSeconds,
                clamp(volumePerMille, 0, 1000), sessionId, true);

        MP4PlaybackSyncManager.start(player, new MP4PlaybackSyncPacket(
                player.getUUID(), deviceId, ClientMediaSyncPayload.SOURCE_PLAYER, player.getId(),
                player.getX(), player.getY() + 1.2D, player.getZ(), true, index, syncedPlayUrl, rawUrl,
                songName == null ? "" : songName,
                durationSeconds, clamp(volumePerMille, 0, 1000), sessionId, elapsedMillis, false));
    }

    private static void broadcastStop(ServerPlayer player, UUID deviceId, int index) {
        UUID sourceId = deviceId != null ? deviceId : player.getUUID();
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                MP4PlaybackSyncPacket.stop(player.getUUID(), sourceId, index));
    }

    private static int progressPerMille(List<ItemStack> queue, int index, long elapsedMillis, int fallback) {
        if (index < 0 || index >= queue.size()) {
            return fallback;
        }
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(queueStack(queue, index));
        int durationSeconds = songInfo != null ? Math.max(0, songInfo.songTime) : 0;
        if (durationSeconds <= 0) {
            return fallback;
        }
        long durationMillis = durationSeconds * 1000L;
        long elapsed = Math.max(0L, Math.min(durationMillis, elapsedMillis));
        return clamp((int) Math.round(elapsed * 1000.0D / durationMillis), 0, 1000);
    }

    private static MP4Item.State withPlayback(MP4Item.State state, boolean playing, int selectedIndex,
            int volumePerMille, int progressPerMille) {
        return new MP4Item.State(playing, state.shuffle(), state.videoEnabled(), state.landscape(),
                state.qualityIndex(), selectedIndex, state.queueScrollOffset(), clamp(volumePerMille, 0, 1000),
                state.repeatMode(), state.playlistOpen(), state.lyricsEnabled(), state.subtitleMode(),
                state.subtitleAiEnabled(), clamp(progressPerMille, 0, 1000), state.rotationHintShown());
    }

    private static MP4Item.State withSelectedIndex(MP4Item.State state, int selectedIndex) {
        int progressPerMille = selectedIndex == state.selectedQueueIndex() ? state.progressPerMille() : 0;
        return new MP4Item.State(state.playing(), state.shuffle(), state.videoEnabled(), state.landscape(),
                state.qualityIndex(), selectedIndex, state.queueScrollOffset(), state.volumePerMille(),
                state.repeatMode(), state.playlistOpen(), state.lyricsEnabled(), state.subtitleMode(),
                state.subtitleAiEnabled(), progressPerMille, state.rotationHintShown());
    }

    private static int durationSeconds(List<ItemStack> queue, int index) {
        if (index < 0 || index >= queue.size()) {
            return 0;
        }
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(queueStack(queue, index));
        return songInfo != null ? Math.max(0, songInfo.songTime) : 0;
    }

    private static List<ItemStack> queueForPlayback(MP4DeviceStateStore.DeviceEntry entry, ItemStack stack) {
        List<ItemStack> itemQueue = MP4Item.readQueue(stack);
        if (!itemQueue.isEmpty()) {
            return itemQueue;
        }
        return entry.queue();
    }

    @Nonnull
    private static ItemStack nonNullStack(ItemStack stack) {
        return Objects.requireNonNull(stack, "item stack");
    }

    @Nonnull
    private static ItemStack queueStack(List<ItemStack> queue, int index) {
        return Objects.requireNonNull(queue.get(index), "queue stack");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isPlaybackAllowed(ServerPlayer player, String sourceUrl) {
        if (BiliSongInfoSanitizer.isForbiddenBiliDirectUrl(sourceUrl)) {
            if (player != null) {
                player.sendSystemMessage(BiliWhitelistManager.denialMessage(player, sourceUrl, "播放"));
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
        player.sendSystemMessage(BiliWhitelistManager.denialMessage(player, sourceUrl, "播放"));
        return false;
    }

    public enum Action {
        START,
        PAUSE,
        STOP,
        RESTART,
        SEEK,
        VOLUME;

        public int id() {
            return ordinal();
        }

        public static Action byId(int id) {
            Action[] values = values();
            return id >= 0 && id < values.length ? values[id] : START;
        }
    }
}
