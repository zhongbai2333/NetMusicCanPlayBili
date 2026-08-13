package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Owns runtime MP4 progress and synchronizes it with SavedData and device state. */
final class MP4PlaybackProgressPersistence {
    private final ConcurrentMap<PlaybackSourceId, MP4PlaybackRuntimeProgress> runtimeProgress =
            new ConcurrentHashMap<>();

    long currentElapsed(ServerLevel level, UUID deviceId, long fallback) {
        if (level == null || deviceId == null) {
            return Math.max(0L, fallback);
        }
        MP4PlaybackRuntimeProgress runtime = runtimeProgress.get(PlaybackSourceId.of(deviceId));
        if (runtime != null) {
            return MP4PlaybackProgressPolicy.currentElapsed(snapshot(runtime), null, fallback);
        }
        Long persisted = MP4PlaybackSavedData.get(level).get(deviceId)
                .map(entry -> entry.elapsedMillis())
                .orElse(null);
        return MP4PlaybackProgressPolicy.currentElapsed(null, persisted, fallback);
    }

    long savedElapsed(ServerLevel level, UUID deviceId, int queueIndex, long fallback) {
        if (level == null || deviceId == null) {
            return Math.max(0L, fallback);
        }
        MP4PlaybackProgressPolicy.RuntimeProgress runtime = snapshot(
                runtimeProgress.get(PlaybackSourceId.of(deviceId)));
        if (runtime != null && runtime.queueIndex() == queueIndex) {
            return MP4PlaybackProgressPolicy.queueElapsed(queueIndex, runtime, fallback);
        }
        long persisted = MP4PlaybackSavedData.get(level).elapsedMillis(deviceId, queueIndex, Math.max(0L, fallback));
        return MP4PlaybackProgressPolicy.queueElapsed(queueIndex, null, persisted);
    }

    void record(UUID deviceId, int queueIndex, long elapsedMillis, int durationSeconds,
            int volumePerMille, String sessionId, boolean playing) {
        record(deviceId, queueIndex, elapsedMillis, durationSeconds, volumePerMille,
                PlaybackSessionId.parse(sessionId), playing);
    }

    void record(UUID deviceId, int queueIndex, long elapsedMillis, int durationSeconds,
            int volumePerMille, Optional<PlaybackSessionId> playbackSessionId, boolean playing) {
        if (deviceId == null) {
            return;
        }
        runtimeProgress.put(PlaybackSourceId.of(deviceId),
                new MP4PlaybackRuntimeProgress(queueIndex, elapsedMillis, durationSeconds,
                        volumePerMille, playbackSessionId, playing));
    }

    void recordAndFlush(ServerLevel level, UUID deviceId, int queueIndex, long elapsedMillis, int durationSeconds,
            int volumePerMille, String sessionId, boolean playing) {
        recordAndFlush(level, deviceId, queueIndex, elapsedMillis, durationSeconds, volumePerMille,
                PlaybackSessionId.parse(sessionId), playing);
    }

    void recordAndFlush(ServerLevel level, UUID deviceId, int queueIndex, long elapsedMillis, int durationSeconds,
            int volumePerMille, Optional<PlaybackSessionId> playbackSessionId, boolean playing) {
        record(deviceId, queueIndex, elapsedMillis, durationSeconds, volumePerMille, playbackSessionId, playing);
        flush(level, deviceId);
    }

    void persist(ServerPlayer owner, MP4PlaybackSyncManager.Session session, long gameTime, boolean playing) {
        if (owner == null || session == null || !(owner.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack stack = MP4Item.findPlayableInInventory(owner);
        persist(level, stack, session, gameTime, playing, false);
    }

    void persist(ItemStack stack, MP4PlaybackSyncManager.Session session, long gameTime, boolean playing) {
        if (session == null) {
            return;
        }
        persist(session.currentLevel(), stack, session, gameTime, playing, true);
    }

    long targetMillis(ServerLevel level, UUID deviceId, ItemStack stack, MP4Item.State state, int queueIndex) {
        List<ItemStack> queue = MP4PlaybackQueueController.queueForDevice(
                MP4DeviceStateStore.getOrCreate(level, deviceId, stack), stack);
        if (queueIndex < 0 || queueIndex >= queue.size()) {
            return 0L;
        }
        @SuppressWarnings("null")
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(queue.get(queueIndex));
        int durationSeconds = songInfo != null ? Math.max(0, songInfo.songTime) : 0;
        if (durationSeconds <= 0) {
            return 0L;
        }
        long fallbackMillis = MP4PlaybackProgressPolicy.elapsedFromProgress(durationSeconds,
                state.progressPerMille());
        MP4PlaybackProgressPolicy.RuntimeProgress runtime = snapshot(
                runtimeProgress.get(PlaybackSourceId.of(deviceId)));
        long selected;
        if (runtime != null && runtime.queueIndex() == queueIndex) {
            selected = MP4PlaybackProgressPolicy.queueElapsed(queueIndex, runtime, fallbackMillis);
        } else {
            long persisted = MP4PlaybackSavedData.get(level).elapsedMillis(deviceId, queueIndex, fallbackMillis);
            selected = MP4PlaybackProgressPolicy.queueElapsed(queueIndex, null, persisted);
        }
        return MP4PlaybackProgressPolicy.clampTarget(durationSeconds, selected);
    }

    void flush(ServerLevel level) {
        if (level == null || runtimeProgress.isEmpty()) {
            return;
        }
        MP4PlaybackSavedData data = MP4PlaybackSavedData.get(level);
        runtimeProgress.forEach((sourceId, entry) -> data.put(sourceId.value(), toSavedDataEntry(entry)));
    }

    void flush(ServerLevel level, UUID deviceId) {
        if (level == null || deviceId == null) {
            return;
        }
        MP4PlaybackRuntimeProgress entry = runtimeProgress.get(PlaybackSourceId.of(deviceId));
        if (entry != null) {
            MP4PlaybackSavedData.get(level).put(deviceId, toSavedDataEntry(entry));
        }
    }

    void clearRuntime() {
        runtimeProgress.clear();
    }

    private void persist(ServerLevel level, ItemStack stack, MP4PlaybackSyncManager.Session session,
            long gameTime, boolean playing, boolean markContainerChanged) {
        long elapsedMillis = session.elapsedMillis(gameTime);
        record(session.sourceId(), session.queueIndex(), elapsedMillis, session.durationSeconds(),
                session.volumePerMille(), Optional.of(session.playbackSessionId()), playing);
        if (level == null) {
            return;
        }
        MP4DeviceStateStore.recordPlayback(level, session.sourceId(), session.queueIndex(), elapsedMillis,
                session.durationSeconds(), session.volumePerMille(), Optional.of(session.playbackSessionId()), playing);
        if (!playing) {
            flush(level, session.sourceId());
        } else {
            return;
        }
        if (stack == null || !(stack.getItem() instanceof MP4Item)) {
            return;
        }
        MP4Item.State state = MP4DeviceStateStore.getOrCreate(level, session.sourceId(), stack).state();
        int progress = MP4PlaybackProgressPolicy.progressPerMille(elapsedMillis, session.durationSeconds());
        MP4DeviceStateStore.updateState(level, session.sourceId(), new MP4Item.State(
                false, state.shuffle(), state.videoEnabled(), state.landscape(), state.qualityIndex(),
                session.queueIndex(), state.queueScrollOffset(), session.volumePerMille(), state.repeatMode(),
                state.playlistOpen(), state.lyricsEnabled(), state.subtitleMode(), state.subtitleAiEnabled(),
                progress, state.rotationHintShown()));
        if (markContainerChanged) {
            session.markContainerChanged();
        }
    }

    private static MP4PlaybackProgressPolicy.RuntimeProgress snapshot(MP4PlaybackRuntimeProgress entry) {
        return entry == null ? null
                : new MP4PlaybackProgressPolicy.RuntimeProgress(entry.queueIndex(), entry.elapsedMillis());
    }

    private static MP4PlaybackSavedData.Entry toSavedDataEntry(MP4PlaybackRuntimeProgress entry) {
        return new MP4PlaybackSavedData.Entry(entry.queueIndex(), entry.elapsedMillis(), entry.durationSeconds(),
                entry.volumePerMille(), entry.sessionId(), entry.playing());
    }
}
