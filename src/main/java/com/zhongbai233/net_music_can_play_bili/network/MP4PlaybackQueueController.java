package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Applies MP4 queue decisions to sessions, device state, and playback progress. */
final class MP4PlaybackQueueController {
    private final MP4PlaybackSourceSessionRegistry<MP4PlaybackSyncManager.Session> sessions;
    private final Consumer<UUID> resolveInvalidator;
    private final RuntimeProgressRecorder progressRecorder;
    private final BiConsumer<ServerLevel, UUID> progressFlusher;
    private final SessionPublisher sessionPublisher;
    private final SessionStopPublisher stopPublisher;
    private final PlaybackStarter playbackStarter;

    MP4PlaybackQueueController(
            MP4PlaybackSourceSessionRegistry<MP4PlaybackSyncManager.Session> sessions,
            Consumer<UUID> resolveInvalidator,
            RuntimeProgressRecorder progressRecorder,
            BiConsumer<ServerLevel, UUID> progressFlusher,
            SessionPublisher sessionPublisher,
            SessionStopPublisher stopPublisher,
            PlaybackStarter playbackStarter) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.resolveInvalidator = Objects.requireNonNull(resolveInvalidator, "resolveInvalidator");
        this.progressRecorder = Objects.requireNonNull(progressRecorder, "progressRecorder");
        this.progressFlusher = Objects.requireNonNull(progressFlusher, "progressFlusher");
        this.sessionPublisher = Objects.requireNonNull(sessionPublisher, "sessionPublisher");
        this.stopPublisher = Objects.requireNonNull(stopPublisher, "stopPublisher");
        this.playbackStarter = Objects.requireNonNull(playbackStarter, "playbackStarter");
    }

    void reconcileQueueChange(ServerPlayer owner, UUID deviceId, List<ItemStack> newQueue) {
        if (owner == null || deviceId == null || !(owner.level() instanceof ServerLevel level)) {
            return;
        }
        MP4PlaybackSyncManager.Session session = sessions.get(deviceId);
        if (session == null) {
            return;
        }
        List<ItemStack> safeNewQueue = newQueue != null ? newQueue : List.of();
        MP4PlaybackQueuePolicy.Reconciliation reconciliation = MP4PlaybackQueuePolicy.reconcile(
                session.queueIndex(), session.rawUrl(), sourceUrls(safeNewQueue));
        long gameTime = level.getGameTime();
        long elapsedMillis = session.elapsedMillis(gameTime);
        if (reconciliation.action() == MP4PlaybackQueuePolicy.ReconcileAction.KEEP) {
            return;
        }
        if (reconciliation.action() == MP4PlaybackQueuePolicy.ReconcileAction.REMAP) {
            remapSession(level, deviceId, session, reconciliation.selectedIndex(), elapsedMillis, gameTime);
            return;
        }
        stopRemovedSession(level, deviceId, session, safeNewQueue, reconciliation.selectedIndex());
    }

    boolean tryAdvanceQueue(ServerLevel level, ItemStack stack, MP4PlaybackSyncManager.Session session) {
        MP4DeviceStateStore.DeviceEntry deviceEntry = MP4DeviceStateStore.getOrCreate(level, session.sourceId(), stack);
        List<ItemStack> queue = queueForDevice(deviceEntry, stack);
        MP4Item.State state = deviceEntry.state();
        MP4PlaybackQueuePolicy.Completion completion = MP4PlaybackQueuePolicy.completion(
                session.queueIndex(), queue.size(), state.repeatMode());
        if (!completion.shouldAdvance()) {
            return false;
        }
        int nextIndex = completion.nextIndex();
        MP4DeviceStateStore.update(level, session.sourceId(), new MP4DeviceStateStore.DeviceEntry(
                new MP4Item.State(true, state.shuffle(), state.videoEnabled(), state.landscape(),
                        state.qualityIndex(), nextIndex, state.queueScrollOffset(), state.volumePerMille(),
                        state.repeatMode(), state.playlistOpen(), state.lyricsEnabled(), state.subtitleMode(),
                        state.subtitleAiEnabled(), 0, state.rotationHintShown()),
                queue, 0L, 0, Optional.empty()));
        progressRecorder.record(session.sourceId(), nextIndex, 0L, 0, session.volumePerMille(), Optional.empty(),
                true);
        progressFlusher.accept(level, session.sourceId());
        session.markContainerChanged();
        playbackStarter.start(level, stack, session.ownerId(), session.sourceId(), session.sourceType(),
                session.sourceEntityId(), session.sourcePos(), session.containerSlot());
        return true;
    }

    static int durationSeconds(List<ItemStack> queue, int queueIndex) {
        if (queue == null || queueIndex < 0 || queueIndex >= queue.size()) {
            return 0;
        }
        @SuppressWarnings("null")
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(queue.get(queueIndex));
        return songInfo != null ? Math.max(0, songInfo.songTime) : 0;
    }

    static List<ItemStack> queueForDevice(MP4DeviceStateStore.DeviceEntry entry, ItemStack stack) {
        List<ItemStack> itemQueue = MP4Item.readQueue(stack);
        if (!itemQueue.isEmpty()) {
            return itemQueue;
        }
        return entry != null ? entry.queue() : List.of();
    }

    private void remapSession(ServerLevel level, UUID deviceId, MP4PlaybackSyncManager.Session session,
            int newIndex, long elapsedMillis, long gameTime) {
        MP4PlaybackSyncManager.Session remapped = session.withQueueIndex(newIndex, gameTime);
        if (!sessions.replace(deviceId, session, remapped)) {
            return;
        }
        progressRecorder.record(deviceId, newIndex, elapsedMillis, remapped.durationSeconds(),
                remapped.volumePerMille(), Optional.of(remapped.playbackSessionId()), true);
        MP4DeviceStateStore.recordPlayback(level, deviceId, newIndex, elapsedMillis, remapped.durationSeconds(),
                remapped.volumePerMille(), Optional.of(remapped.playbackSessionId()), true);
        sessionPublisher.publish(level, remapped, gameTime);
    }

    private void stopRemovedSession(ServerLevel level, UUID deviceId, MP4PlaybackSyncManager.Session session,
            List<ItemStack> newQueue, int selectedIndex) {
        if (!sessions.remove(deviceId, session)) {
            return;
        }
        resolveInvalidator.accept(deviceId);
        stopPublisher.publish(level, session);
        int durationSeconds = durationSeconds(newQueue, selectedIndex);
        progressRecorder.record(deviceId, selectedIndex, 0L, durationSeconds, session.volumePerMille(),
                Optional.empty(), false);
        progressFlusher.accept(level, deviceId);
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, ItemStack.EMPTY);
        MP4Item.State state = entry.state();
        MP4DeviceStateStore.update(level, deviceId, new MP4DeviceStateStore.DeviceEntry(
                new MP4Item.State(false, state.shuffle(), state.videoEnabled(), state.landscape(),
                        state.qualityIndex(), selectedIndex, state.queueScrollOffset(), session.volumePerMille(),
                        state.repeatMode(), state.playlistOpen(), state.lyricsEnabled(), state.subtitleMode(),
                        state.subtitleAiEnabled(), 0, state.rotationHintShown()),
                newQueue, 0L, durationSeconds, Optional.empty()));
    }

    private static List<String> sourceUrls(List<ItemStack> queue) {
        List<String> urls = new ArrayList<>(queue.size());
        for (ItemStack stack : queue) {
            @SuppressWarnings("null")
            ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(stack);
            urls.add(songInfo != null ? songInfo.songUrl : null);
        }
        return urls;
    }

    @FunctionalInterface
    interface RuntimeProgressRecorder {
        void record(UUID deviceId, int queueIndex, long elapsedMillis, int durationSeconds,
                int volumePerMille, Optional<PlaybackSessionId> playbackSessionId, boolean playing);
    }

    @FunctionalInterface
    interface SessionPublisher {
        void publish(ServerLevel level, MP4PlaybackSyncManager.Session session, long gameTime);
    }

    @FunctionalInterface
    interface SessionStopPublisher {
        void publish(ServerLevel level, MP4PlaybackSyncManager.Session session);
    }

    @FunctionalInterface
    interface PlaybackStarter {
        void start(ServerLevel level, ItemStack stack, UUID ownerId, UUID sourceId, int sourceType,
                int sourceEntityId, BlockPos sourcePos, int containerSlot);
    }
}
