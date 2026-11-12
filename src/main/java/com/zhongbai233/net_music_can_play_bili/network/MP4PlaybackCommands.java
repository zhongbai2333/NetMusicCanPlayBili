package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket.Action;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackQueueController.durationSeconds;
import static com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackQueueController.queueForDevice;
import static com.zhongbai233.net_music_can_play_bili.network.MP4StateUpdatePolicy.withSelectedIndex;

/** Applies manual MP4 commands and owns their asynchronous resolution callbacks. */
final class MP4PlaybackCommands {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MP4PlaybackCommands() { }

    /** Uses the authoritative carrier, device ID and level captured by packet admission. */
    static void execute(ServerPlayer player, ServerLevel level, ItemStack stack, UUID deviceId,
            Action action, int selectedQueueIndex, int volumePerMille, long targetMillis) {
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        List<ItemStack> queue = queueForDevice(entry, stack);
        int requestedIndex = action == Action.VOLUME ? entry.state().selectedQueueIndex()
                : selectedQueueIndex;
        if (action == Action.STOP || action == Action.PAUSE) {
            requestedIndex = MP4PlaybackSyncManager.requestedQueueIndex(deviceId, entry.state().selectedQueueIndex());
        }
        if (action == Action.NEXT || action == Action.PREVIOUS) {
            int base = MP4PlaybackSyncManager.requestedQueueIndex(deviceId, entry.state().selectedQueueIndex());
            requestedIndex = MP4PlaybackQueuePolicy.manualMove(base, queue.size(),
                    action == Action.NEXT ? 1 : -1, entry.state().shuffle(),
                    java.util.concurrent.ThreadLocalRandom.current().nextInt());
        }
        int index = queue.isEmpty() ? 0 : clamp(requestedIndex, 0, queue.size() - 1);

        boolean selectedTrackChanged = index != entry.state().selectedQueueIndex();
        MP4Item.State state = withSelectedIndex(entry.state(), index);
        int volume = clamp(volumePerMille, 0, 1000);
        if (action != Action.VOLUME) MP4PlaybackSyncManager.beginManualControl(deviceId);
        switch (action) {
            case STOP, PAUSE -> {
                int activeIndex = MP4PlaybackSyncManager.activeQueueIndex(deviceId);
                boolean changedTrack = index != entry.state().selectedQueueIndex()
                        || activeIndex >= 0 && activeIndex != index;
                long elapsedMillis = changedTrack ? 0L : MP4PlaybackSyncManager.currentElapsedMillis(player, deviceId,
                        Math.max(0L, targetMillis));
                int progress = changedTrack ? 0 : MP4PlaybackSyncManager.currentProgressPerMille(player,
                        progressPerMille(queue, index, elapsedMillis, state.progressPerMille()));
                MP4Item.State newState = withPlayback(state, false, index, volume, progress);
                MP4PlaybackSyncManager.stop(player, deviceId);
                MP4DeviceStateStore.update(level, deviceId, new MP4DeviceStateStore.DeviceEntry(newState, queue,
                        elapsedMillis, durationSeconds(queue, index), ""));
                MP4PlaybackSyncManager.recordProgress(player, deviceId, index, elapsedMillis, queue, volume, false);
                broadcastStop(player, deviceId, index);
            }
            case VOLUME -> {
                MP4DeviceStateStore.updateState(level, deviceId,
                        withPlayback(state, state.playing(), index, volume, state.progressPerMille()));
                MP4PlaybackSyncManager.updateVolume(deviceId, volume);
            }
            case START -> {
                long requestedMillis = selectedTrackChanged ? 0L : Math.max(0L, targetMillis);
                if (!MP4PlaybackSyncManager.resumeExisting(player, deviceId, index, volume, requestedMillis)) {
                    startPlayback(player, stack, queue, index, volume, requestedMillis);
                }
            }
            case SEEK_PAUSED -> {
                if (MP4PlaybackSyncManager.activeQueueIndex(deviceId) >= 0
                        || !MP4PlaybackSyncManager.mayCompletePlayback(deviceId)) {
                    return;
                }
                int duration = durationSeconds(queue, index);
                long elapsedMillis = MP4PlaybackProgressPolicy.clampTarget(duration, targetMillis);
                MP4Item.State paused = MP4StateUpdatePolicy.seekPaused(entry.state(), index, elapsedMillis, duration);
                if (paused != null) {
                    MP4DeviceStateStore.update(level, deviceId, new MP4DeviceStateStore.DeviceEntry(
                            paused, queue, elapsedMillis, duration, ""));
                    MP4PlaybackSyncManager.recordProgress(player, deviceId, index, elapsedMillis, queue,
                            paused.volumePerMille(), false);
                }
            }
            case SEEK -> startPlayback(player, stack, queue, index, volume, Math.max(0L, targetMillis));
            case RESTART -> startPlayback(player, stack, queue, index, volume, 0L);
            case SELECT, NEXT, PREVIOUS -> {
                if (action == Action.SELECT && index == MP4PlaybackSyncManager.requestedQueueIndex(
                        deviceId, entry.state().selectedQueueIndex())) {
                    return;
                }
                if (entry.state().playing() || MP4PlaybackSyncManager.activeQueueIndex(deviceId) >= 0
                        || !MP4PlaybackSyncManager.mayCompletePlayback(deviceId)) {
                    startPlayback(player, stack, queue, index, volume, 0L);
                } else {
                    MP4DeviceStateStore.update(level, deviceId, new MP4DeviceStateStore.DeviceEntry(
                            withPlayback(state, false, index, volume, 0), queue, 0L,
                            durationSeconds(queue, index), ""));
                }
            }
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
        List<ItemStack> queue = queueForDevice(entry, stack);
        int index = attempt.queueIndex();
        if (index < 0 || index >= queue.size()) {
            MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
            return false;
        }
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(queueStack(queue, index));
        if (songInfo == null || !Objects.equals(attempt.sourceUrl(), songInfo.songUrl)
                || songInfo.vip && !MusicPlayResolverManager.canResolve(songInfo)
                || (!BiliWhitelistManager.isShortLink(songInfo.songUrl) && !isPlaybackAllowed(player, songInfo.songUrl))) {
            MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
            return false;
        }
        ItemMusicCD.SongInfo original = songInfo.clone();
        BiliWhitelistManager.resolveSong(original).whenCompleteAsync((resolved, error) -> {
            if (!MP4PlaybackSyncManager.isCurrentRetryResolve(deviceId, attempt)) {
                return;
            }
            if (error != null || resolved == null) {
                MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
                LOGGER.error("MP4 重试刷新直链失败: {}", original.songName, error);
                failPlayback(player, stack, deviceId, attempt.queueIndex(), original.songUrl);
                return;
            }
            applyResolvedRetry(player, stack, deviceId, attempt, targetMillis, original, resolved);
        }, level.getServer());
        return true;
    }

    private static void applyResolvedRetry(ServerPlayer player, ItemStack stack, UUID deviceId,
            MP4PlaybackRetryAdmission.Attempt attempt, long targetMillis,
            ItemMusicCD.SongInfo original, ItemMusicCD.SongInfo resolved) {
        if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player
                || MP4Item.findByDeviceId(player, deviceId) != stack
                || !MP4PlaybackSyncManager.isCurrentRetryResolve(deviceId, attempt)
                || !(stack.getItem() instanceof MP4Item)
                || !deviceId.equals(MP4Item.readDeviceId(stack))
                || !(player.level() instanceof ServerLevel level)) {
            MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
            return;
        }
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        List<ItemStack> queue = queueForDevice(entry, stack);
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
        if (!isPlaybackAllowed(player, BiliWhitelistManager.playbackIdentity(original.songUrl, resolved.songUrl))) {
            MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
            MP4PlaybackSyncManager.stop(player, deviceId);
            broadcastStop(player, deviceId, index);
            return;
        }
        var track = ServerMediaPlayback.resolvedTrack(original, resolved);
        String playUrl = track.playUrl();
        if (playUrl.isBlank()) {
            MP4PlaybackSyncManager.completeRetryResolve(deviceId, attempt);
            return;
        }
        String songName = track.songName();
        int durationSeconds = track.durationSeconds();
        MP4PlaybackSyncManager.applyRetryResolved(player, deviceId, attempt, playUrl,
                songName, durationSeconds, Math.max(0L, targetMillis));
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
            failPlayback(player, stack, deviceId, index, songInfo == null ? null : songInfo.songUrl);
            return;
        }
        if (songInfo.vip && !MusicPlayResolverManager.canResolve(songInfo)) {
            UUID deviceId = MP4Item.readDeviceId(stack);
            failPlayback(player, stack, deviceId, index, songInfo == null ? null : songInfo.songUrl);
            return;
        }
        if ((!BiliWhitelistManager.isShortLink(songInfo.songUrl) && !isPlaybackAllowed(player, songInfo.songUrl))) {
            UUID deviceId = MP4Item.readDeviceId(stack);
            failPlayback(player, stack, deviceId, index, songInfo == null ? null : songInfo.songUrl);
            return;
        }

        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId == null) {
            return;
        }
        ItemMusicCD.SongInfo original = songInfo.clone();
        MP4ResolveIntentRegistry.Intent intent = MP4PlaybackSyncManager.beginCommandResolve(deviceId, index,
                original.songUrl);
        BiliWhitelistManager.resolveSong(original)
                .whenCompleteAsync((resolved, error) -> {
                    if (!BiliWhitelistManager.acceptsServer(player.level().getServer())
                            || player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player
                            || MP4Item.findByDeviceId(player, deviceId) != stack
                            || !MP4PlaybackSyncManager.isCurrentResolve(deviceId, intent)) {
                        MP4PlaybackSyncManager.completeResolve(deviceId, intent);
                        return;
                    }
                    if (error != null || resolved == null) {
                        MP4PlaybackSyncManager.completeResolve(deviceId, intent);
                        LOGGER.error("MP4 解析播放失败: {}", original.songName, error);
                        failPlayback(player, stack, deviceId, index, original.songUrl);
                        return;
                    }
                    applyResolvedPlayback(player, stack, deviceId, index, volumePerMille, targetMillis,
                            original, resolved, intent);
                }, player.level().getServer());
    }

    static void restartSelected(ServerPlayer player, ItemStack stack, UUID deviceId, int index,
            int volumePerMille) {
        if (player == null || deviceId == null || !(stack.getItem() instanceof MP4Item)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        List<ItemStack> queue = queueForDevice(entry, stack);
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
        if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player
                || MP4Item.findByDeviceId(player, requestedDeviceId) != stack) {
            MP4PlaybackSyncManager.completeResolve(requestedDeviceId, intent);
            return;
        }
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
        List<ItemStack> queue = queueForDevice(entry, stack);
        if (index < 0 || index >= queue.size()) {
            MP4PlaybackSyncManager.completeResolve(deviceId, intent);
            return;
        }
        ItemMusicCD.SongInfo current = ItemMusicCD.getSongInfo(queueStack(queue, index));
        if (current == null || !Objects.equals(current.songUrl, original.songUrl)) {
            MP4PlaybackSyncManager.completeResolve(deviceId, intent);
            return;
        }
        if (!isPlaybackAllowed(player, BiliWhitelistManager.playbackIdentity(original.songUrl, resolved.songUrl))) {
            MP4PlaybackSyncManager.completeResolve(deviceId, intent);
            failPlayback(player, stack, deviceId, index, original.songUrl);
            return;
        }

        var track = ServerMediaPlayback.resolvedTrack(original, resolved);
        String rawUrl = track.rawUrl();
        String playUrl = track.playUrl();
        if (playUrl.isBlank()) {
            MP4PlaybackSyncManager.completeResolve(deviceId, intent);
            failPlayback(player, stack, deviceId, index, original.songUrl);
            return;
        }
        String songName = track.songName();
        int durationSeconds = track.durationSeconds();
        long elapsedMillis = MP4PlaybackProgressPolicy.clampTarget(durationSeconds, targetMillis);
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
                songName,
                durationSeconds, clamp(volumePerMille, 0, 1000), sessionId, elapsedMillis, false));
    }

    private static void failPlayback(ServerPlayer player, ItemStack stack, UUID deviceId, int index, String sourceUrl) {
        if (!(player.level() instanceof ServerLevel level) || deviceId == null
                || MP4Item.findByDeviceId(player, deviceId) != stack
                || level.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
        MP4PlaybackSyncManager.handleQueueFailure(level, stack, player.getUUID(), deviceId,
                ClientMediaSyncPayload.SOURCE_PLAYER, player.getId(), player.blockPosition(), -1, index, sourceUrl);
        broadcastStop(player, deviceId, index);
    }

    private static void broadcastStop(ServerPlayer player, UUID deviceId, int index) {
        UUID sourceId = deviceId != null ? deviceId : player.getUUID();
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                MP4PlaybackSyncPacket.stop(player.getUUID(), sourceId, index));
    }

    private static int progressPerMille(List<ItemStack> queue, int index, long elapsedMillis, int fallback) {
        int duration = durationSeconds(queue, index);
        return duration > 0 ? MP4PlaybackProgressPolicy.progressPerMille(elapsedMillis, duration) : fallback;
    }

    private static MP4Item.State withPlayback(MP4Item.State state, boolean playing, int selectedIndex,
            int volumePerMille, int progressPerMille) {
        return MP4StateUpdatePolicy.withPlayback(state, playing, selectedIndex,
                clamp(volumePerMille, 0, 1000), clamp(progressPerMille, 0, 1000));
    }

    @Nonnull
    private static ItemStack queueStack(List<ItemStack> queue, int index) {
        return Objects.requireNonNull(queue.get(index), "queue stack");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isPlaybackAllowed(ServerPlayer player, String sourceUrl) {
        return BiliWhitelistManager.isPlaybackAllowed(player, sourceUrl, "播放");
    }

}
