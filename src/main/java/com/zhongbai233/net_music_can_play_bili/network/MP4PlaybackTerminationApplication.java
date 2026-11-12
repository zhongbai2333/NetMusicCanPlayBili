package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackEndReason;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Coordinates playback termination, completion and failure recovery using the shared runtime state. */
final class MP4PlaybackTerminationApplication {
    record TerminalCommand(UUID sourceId, String sessionId, String transportToken, PlaybackEndReason reason) {}

    record QueueFailureCommand(UUID ownerId, UUID sourceId, int sourceType, int entityId,
            BlockPos pos, int slot, int failedIndex, String expectedUrl) {}

    private final MP4PlaybackSourceSessionRegistry<MP4PlaybackSyncManager.Session> sessions;
    private final UnknownPlaybackBudget unknownBudget;
    private final MP4FailureRecovery recovery;
    private final MP4ResolveIntentRegistry resolveIntents;
    private final MP4PlaybackProgressPersistence progress;
    private final MP4PlaybackAudienceBroadcaster audience;
    private final MP4PlaybackQueueController queues;
    private final MP4PlaybackQueueController.PlaybackStarter starter;

    MP4PlaybackTerminationApplication(
            MP4PlaybackSourceSessionRegistry<MP4PlaybackSyncManager.Session> sessions,
            UnknownPlaybackBudget unknownBudget, MP4FailureRecovery recovery,
            MP4ResolveIntentRegistry resolveIntents, MP4PlaybackProgressPersistence progress,
            MP4PlaybackAudienceBroadcaster audience, MP4PlaybackQueueController queues,
            MP4PlaybackQueueController.PlaybackStarter starter) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.unknownBudget = Objects.requireNonNull(unknownBudget, "unknownBudget");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.resolveIntents = Objects.requireNonNull(resolveIntents, "resolveIntents");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.queues = Objects.requireNonNull(queues, "queues");
        this.starter = Objects.requireNonNull(starter, "starter");
    }

    boolean mayCompletePlayback(UUID deviceId) {
        return !resolveIntents.hasPending(deviceId) && !PadPlaybackControlPacket.hasPending(deviceId);
    }

    void stop(ServerPlayer owner, UUID deviceId) {
        if (owner == null || deviceId == null) return;
        recovery.cancel(deviceId);
        resolveIntents.invalidate(deviceId);
        unknownBudget.remove(deviceId);
        MP4PlaybackSyncManager.Session session = sessions.remove(deviceId);
        if (session != null && owner.level() instanceof ServerLevel level) {
            ItemStack stack = MP4PlaybackSourceDiscovery.stack(level, session);
            ServerLevel sessionLevel = level.getServer().getLevel(session.levelKey());
            progress.persistStopped(sessionLevel, stack, session, MonotonicMediaClock.nowNanos());
            audience.broadcastStop(level, session);
        }
    }

    void forgetUnavailableLevel(UUID deviceId, MP4PlaybackSyncManager.Session session) {
        if (sessions.remove(deviceId, session)) {
            recovery.cancel(session.sourceId());
            resolveIntents.invalidate(session.sourceId());
        }
    }

    void stopMissingSource(UUID deviceId, ServerLevel level, ItemStack stack,
            MP4PlaybackSyncManager.Session session, long nowNanos) {
        if (!sessions.remove(deviceId, session)) return;
        progress.persistStopped(level, stack, session, nowNanos);
        recovery.cancel(session.sourceId());
        resolveIntents.invalidate(session.sourceId());
        audience.broadcastStop(level, session);
    }

    void stopUnknownTimeout(UUID deviceId, ServerLevel level, ItemStack stack,
            MP4PlaybackSyncManager.Session session, long nowNanos) {
        if (sessions.remove(deviceId, session)) {
            resolveIntents.invalidate(deviceId);
            progress.persistStopped(level, stack, session, nowNanos);
            audience.broadcastStop(level, session);
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(session.ownerId());
            if (owner != null) {
                if (PadItem.isPad(stack)) PadPlaybackControlPacket.stopFromServer(owner, deviceId);
                owner.sendSystemMessage(Component.literal("未知时长音源未确认结束，已安全停止；请重试。"));
            }
        }
        unknownBudget.remove(deviceId);
    }

    boolean completeKnownDuration(UUID deviceId, ServerLevel level, ItemStack stack,
            MP4PlaybackSyncManager.Session session, long nowNanos, long gameTime) {
        if (session.durationSeconds() <= 0
                || session.elapsedMillisAtNanos(nowNanos) < (long) session.durationSeconds() * 1000L) return false;
        if (sessions.remove(deviceId, session) && !queues.tryAdvanceQueue(level, stack, session)) {
            recovery.cancel(deviceId);
            progress.persistStopped(level, stack, session.resetToStart(gameTime), nowNanos);
            audience.broadcastStop(level, session);
        }
        return true;
    }

    void handleEnd(ServerPlayer actor, TerminalCommand command) {
        MP4PlaybackSyncManager.Session session = sessions.get(command.sourceId());
        if (session == null) return;
        ServerLevel level = actor.level().getServer().getLevel(session.levelKey());
        if (level == null) return;
        ItemStack stack = MP4PlaybackSourceDiscovery.stack(level, session);
        MP4PlaybackEndPolicy.Decision decision = MP4PlaybackEndPolicy.decide(
                session.ownerId().equals(actor.getUUID())
                        && level.getServer().getPlayerList().getPlayer(actor.getUUID()) == actor,
                session.sessionId().equals(command.sessionId()),
                !command.transportToken().isEmpty()
                        && PlaybackSync.parseTerminalToken(session.playUrl()).equals(command.transportToken()),
                MP4PlaybackSourceDiscovery.isActiveMediaStack(level, stack, session.sourceId()),
                !mayCompletePlayback(session.sourceId()),
                session.durationSeconds(), command.reason());
        if (decision == MP4PlaybackEndPolicy.Decision.IGNORE) return;
        if (decision == MP4PlaybackEndPolicy.Decision.STOP && stack.getItem() instanceof MP4Item) {
            handleQueueFailure(level, stack, new QueueFailureCommand(session.ownerId(), session.sourceId(),
                    session.sourceType(), session.sourceEntityId(), session.sourcePos(), session.containerSlot(),
                    session.queueIndex(), session.rawUrl()));
            return;
        }
        if (!sessions.remove(session.sourceId(), session)) return;
        unknownBudget.remove(session.sourceId());
        if (decision == MP4PlaybackEndPolicy.Decision.ADVANCE && stack.getItem() instanceof MP4Item
                && queues.tryAdvanceQueue(level, stack, session)) return;
        recovery.cancel(session.sourceId());
        resolveIntents.invalidate(session.sourceId());
        if (PadItem.isPad(stack)) PadPlaybackControlPacket.stopFromServer(actor, session.sourceId());
        progress.persistStopped(level, stack, session, MonotonicMediaClock.nowNanos());
        audience.broadcastStop(level, session);
        if (decision == MP4PlaybackEndPolicy.Decision.STOP) actor.sendSystemMessage(Component.literal(
                "音源播放失败，已停止；请重试或选择其他条目。"));
    }

    void handleQueueFailure(ServerLevel level, ItemStack stack, QueueFailureCommand command) {
        if (!BiliWhitelistManager.acceptsServer(level.getServer())) return;
        UUID sourceId = command.sourceId();
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, sourceId, stack);
        List<ItemStack> queue = MP4PlaybackQueueController.queueForDevice(entry, stack);
        List<String> urls = MP4PlaybackQueueController.sourceUrls(queue);
        int failedIndex = command.failedIndex();
        if (failedIndex < 0 || failedIndex >= urls.size()
                || !Objects.equals(command.expectedUrl(), urls.get(failedIndex))) {
            recovery.cancel(sourceId);
            return;
        }
        // Exact carrier + intent admission remains in each async caller, before this entry.
        MP4PlaybackSyncManager.Session previous = sessions.remove(sourceId);
        unknownBudget.remove(sourceId);
        if (previous != null) audience.broadcastStop(level, previous);
        MP4Item.State state = entry.state();
        Runnable stop = () -> {
            resolveIntents.invalidate(sourceId);
            MP4DeviceStateStore.update(level, sourceId, new MP4DeviceStateStore.DeviceEntry(
                    MP4StateUpdatePolicy.withPlayback(state, false, failedIndex, state.volumePerMille(), 0),
                    queue, 0L, MP4PlaybackQueueController.durationSeconds(queue, failedIndex), Optional.empty()));
            progress.record(sourceId, failedIndex, 0L, 0, state.volumePerMille(), Optional.empty(), false);
            progress.flush(level, sourceId);
        };
        recovery.recover(sourceId, urls, failedIndex, state.repeatMode(), state.shuffle(),
                ThreadLocalRandom.current().nextInt(), next -> {
                    MP4DeviceStateStore.update(level, sourceId, new MP4DeviceStateStore.DeviceEntry(
                            MP4StateUpdatePolicy.withPlayback(state, true, next, state.volumePerMille(), 0),
                            queue, 0L, 0, Optional.empty()));
                    progress.record(sourceId, next, 0L, 0, state.volumePerMille(), Optional.empty(), true);
                    progress.flush(level, sourceId);
                    starter.start(level, stack, command.ownerId(), sourceId, command.sourceType(),
                            command.entityId(), command.pos(), command.slot());
                }, stop, message -> {
                    ServerPlayer owner = level.getServer().getPlayerList().getPlayer(command.ownerId());
                    if (owner != null) owner.sendSystemMessage(Component.literal(message));
                });
    }

}
