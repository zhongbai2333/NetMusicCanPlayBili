package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;

/** 手持 MP4 设备的服务端权威播放时间线。 */
@EventBusSubscriber
public final class MP4PlaybackSyncManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int FULL_SYNC_INTERVAL_TICKS = 300;
    private static final int DISCOVERY_INTERVAL_TICKS = 40;
    private static final int SOURCE_MISSING_GRACE_TICKS = 20;
    private static final double SYNC_RANGE = AudioPlaybackRange.SYNC_DISTANCE_BLOCKS;
    private static final MP4PlaybackSourceSessionRegistry<Session> SESSION_REGISTRY =
            new MP4PlaybackSourceSessionRegistry<>();
    private static final UnknownPlaybackBudget UNKNOWN_BUDGET = new UnknownPlaybackBudget(
            Math.max(60L, Math.min(604800L, Long.getLong("ncpb.unknownPlaybackMaxActiveSeconds", 86400L))) * 20L);
    private static final MP4ResolveIntentRegistry RESOLVE_INTENTS = new MP4ResolveIntentRegistry();
    private static final MP4PlaybackRetryAdmission<Session> RETRY_ADMISSION =
            new MP4PlaybackRetryAdmission<>(SESSION_REGISTRY, RESOLVE_INTENTS,
                    session -> session.playbackSessionId(), session -> session.queueIndex(),
                    session -> session.rawUrl());
    private static final MP4PlaybackProgressPersistence PROGRESS_PERSISTENCE =
            new MP4PlaybackProgressPersistence();
    private static final MP4PlaybackAudienceBroadcaster AUDIENCE_BROADCASTER =
            new MP4PlaybackAudienceBroadcaster(SESSION_REGISTRY, SYNC_RANGE);
    private static final MP4PlaybackSourceDiscovery SOURCE_DISCOVERY = new MP4PlaybackSourceDiscovery(
            SESSION_REGISTRY, SYNC_RANGE, MP4PlaybackSyncManager::startDiscovered,
            (level, session, tick) -> broadcastSession(level, session, MonotonicMediaClock.nowNanos()));
    private static final MP4FailureRecovery FAILURE_RECOVERY = new MP4FailureRecovery();
    private static final MP4PlaybackQueueController QUEUE_CONTROLLER = new MP4PlaybackQueueController(
            SESSION_REGISTRY, FAILURE_RECOVERY, RESOLVE_INTENTS::invalidate, PROGRESS_PERSISTENCE::record,
            PROGRESS_PERSISTENCE::flush,
            (level, session, tick) -> broadcastSession(level, session, MonotonicMediaClock.nowNanos()),
            AUDIENCE_BROADCASTER::broadcastStop, MP4PlaybackSyncManager::startDiscovered);

    private static final MP4PlaybackTerminationApplication TERMINATION_APPLICATION =
            new MP4PlaybackTerminationApplication(SESSION_REGISTRY, UNKNOWN_BUDGET, FAILURE_RECOVERY,
                    RESOLVE_INTENTS, PROGRESS_PERSISTENCE, AUDIENCE_BROADCASTER, QUEUE_CONTROLLER,
                    MP4PlaybackSyncManager::startDiscovered);

    private MP4PlaybackSyncManager() {
    }

    static void beginManualControl(UUID deviceId) { FAILURE_RECOVERY.cancel(deviceId); }

    static MP4ResolveIntentRegistry.Intent beginCommandResolve(UUID deviceId, int queueIndex, String sourceUrl) {
        FAILURE_RECOVERY.cancel(deviceId);
        return RESOLVE_INTENTS.replace(deviceId, queueIndex, sourceUrl);
    }

    static MP4ResolveIntentRegistry.Intent beginDiscoveredResolve(UUID deviceId, int queueIndex, String sourceUrl) {
        return RESOLVE_INTENTS.beginIfIdle(deviceId, queueIndex, sourceUrl);
    }

    static boolean mayCompletePlayback(UUID deviceId) {
        return TERMINATION_APPLICATION.mayCompletePlayback(deviceId);
    }

    static int requestedQueueIndex(UUID deviceId, int fallback) {
        return RESOLVE_INTENTS.pendingQueueIndex(deviceId, fallback);
    }

    static boolean isCurrentResolve(UUID deviceId, MP4ResolveIntentRegistry.Intent intent) {
        return RESOLVE_INTENTS.isCurrent(deviceId, intent);
    }

    static void completeResolve(UUID deviceId, MP4ResolveIntentRegistry.Intent intent) {
        RESOLVE_INTENTS.complete(deviceId, intent);
    }

    static MP4PlaybackRetryAdmission.Attempt beginRetryResolve(UUID deviceId,
            PlaybackSessionId expectedSessionId) {
        return RETRY_ADMISSION.begin(deviceId, expectedSessionId);
    }

    static boolean isCurrentRetryResolve(UUID deviceId, MP4PlaybackRetryAdmission.Attempt attempt) {
        return RETRY_ADMISSION.isCurrent(deviceId, attempt);
    }

    static void completeRetryResolve(UUID deviceId, MP4PlaybackRetryAdmission.Attempt attempt) {
        RETRY_ADMISSION.complete(deviceId, attempt);
    }

    static boolean applyRetryResolved(ServerPlayer requester, UUID deviceId,
            MP4PlaybackRetryAdmission.Attempt attempt, String resolvedPlayUrl, String songName,
            int durationSeconds, long targetMillis) {
        if (requester == null || deviceId == null || attempt == null || resolvedPlayUrl == null
                || resolvedPlayUrl.isBlank() || !(requester.level() instanceof ServerLevel level)
                || !isPlaybackAllowed(level, BiliWhitelistManager.playbackIdentity(attempt.sourceUrl(), resolvedPlayUrl), requester)) {
            completeRetryResolve(deviceId, attempt);
            return false;
        }
        int safeDurationSeconds = Math.max(0, durationSeconds);
        long elapsedMillis = clampElapsed(targetMillis, safeDurationSeconds);
        String syncedPlayUrl = PlaybackSync.withSync(resolvedPlayUrl, attempt.expectedSessionId(), elapsedMillis,
                safeDurationSeconds * 1000L);
        long nowNanos = MonotonicMediaClock.nowNanos();
        long gameTime = MonotonicMediaClock.nowTick();
        Session refreshed = RETRY_ADMISSION.replaceIfCurrent(deviceId, attempt,
                current -> current.withRefreshedPlayback(syncedPlayUrl, songName, safeDurationSeconds,
                        elapsedMillis, nowNanos, gameTime));
        if (refreshed == null) {
            return false;
        }
        recordAudit(level, refreshed, nowNanos);
        PROGRESS_PERSISTENCE.recordPlaying(level, refreshed, nowNanos);
        broadcastSession(level, refreshed, nowNanos);
        return true;
    }

    public static void start(ServerPlayer owner, MP4PlaybackSyncPacket packet) {
        if (owner == null || packet == null || !packet.playing()) {
            return;
        }
        PlaybackSessionId playbackSessionId = PlaybackSessionId.parse(packet.sessionId()).orElse(null);
        if (playbackSessionId == null) {
            return;
        }
        if (!(owner.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!isPlaybackAllowed(serverLevel, BiliWhitelistManager.playbackIdentity(packet.rawUrl(), packet.playUrl()), owner)) {
            PacketDistributor.sendToPlayer(owner,
                    MP4PlaybackSyncPacket.stop(packet.ownerId(), packet.sourceId(), packet.queueIndex()));
            return;
        }
        long nowNanos = MonotonicMediaClock.nowNanos();
        long gameTime = MonotonicMediaClock.nowTick();
        long elapsedMillis = clampElapsed(packet.elapsedMillis(), packet.durationSeconds());
        Session session = new Session(
                serverLevel.dimension(),
                owner.getUUID(),
                packet.sourceId(),
                packet.sourceType(),
                packet.sourceEntityId(),
                owner.blockPosition(),
                -1,
                packet.queueIndex(),
                packet.playUrl(),
                packet.rawUrl(),
                packet.songName(),
                Math.max(0, packet.durationSeconds()),
                packet.volumePerMille(),
                playbackSessionId,
                gameTime - Math.round(elapsedMillis / 50.0D),
                gameTime,
                gameTime, MonotonicMediaClock.running(elapsedMillis, nowNanos));
        FAILURE_RECOVERY.cancel(session.sourceId());
        RESOLVE_INTENTS.invalidate(session.sourceId());
        SESSION_REGISTRY.replace(session.sourceId(), session);
        recordAudit(serverLevel, session, nowNanos);
        broadcastSession(serverLevel, session, nowNanos);
        MP4DeviceStateStore.getOrCreate(serverLevel, session.sourceId(),
                MP4Item.findByDeviceId(owner, session.sourceId()));
        PROGRESS_PERSISTENCE.recordPlaying(serverLevel, session, nowNanos);
    }

    public static void handleEnd(ServerPlayer actor, MP4PlaybackEndPacket packet) {
        TERMINATION_APPLICATION.handleEnd(actor, new MP4PlaybackTerminationApplication.TerminalCommand(
                packet.sourceId(), packet.sessionId(), packet.transportToken(), packet.reason()));
    }

    static MP4PlaybackTerminationApplication terminationApplication() { return TERMINATION_APPLICATION; }

    public static void stop(ServerPlayer owner) {
        if (owner == null) {
            return;
        }
        ItemStack stack = MP4Item.findPlayableInInventory(owner);
        stop(owner, MP4Item.readDeviceId(stack));
    }

    public static void stop(ServerPlayer owner, UUID deviceId) {
        TERMINATION_APPLICATION.stop(owner, deviceId);
    }

    public static int currentProgressPerMille(ServerPlayer owner, int fallback) {
        if (owner == null || !(owner.level() instanceof ServerLevel)) {
            return fallback;
        }
        ItemStack stack = MP4Item.findPlayableInInventory(owner);
        UUID deviceId = MP4Item.readDeviceId(stack);
        Session session = SESSION_REGISTRY.get(deviceId);
        return session != null ? MP4PlaybackProgressPolicy.progressPerMille(
                session.elapsedMillisAtNanos(MonotonicMediaClock.nowNanos()),
                session.durationSeconds()) : fallback;
    }

    public static long currentElapsedMillis(ServerPlayer owner, UUID deviceId, long fallback) {
        if (owner == null || !(owner.level() instanceof ServerLevel serverLevel) || deviceId == null) {
            return Math.max(0L, fallback);
        }
        Session session = SESSION_REGISTRY.get(deviceId);
        if (session != null) {
            return session.elapsedMillisAtNanos(MonotonicMediaClock.nowNanos());
        }
        return PROGRESS_PERSISTENCE.currentElapsed(serverLevel, deviceId, fallback);
    }

    public static long savedElapsedMillis(ServerPlayer owner, UUID deviceId, int queueIndex, long fallback) {
        if (owner == null || !(owner.level() instanceof ServerLevel serverLevel) || deviceId == null) {
            return Math.max(0L, fallback);
        }
        return PROGRESS_PERSISTENCE.savedElapsed(serverLevel, deviceId, queueIndex, fallback);
    }

    public static void recordProgress(ServerPlayer owner, UUID deviceId, int queueIndex, long elapsedMillis,
            List<ItemStack> queue, int volumePerMille, boolean playing) {
        int durationSeconds = MP4PlaybackQueueController.durationSeconds(queue, queueIndex);
        recordProgress(owner, deviceId, queueIndex, elapsedMillis, durationSeconds, volumePerMille, "", playing);
    }

    public static void recordProgress(ServerPlayer owner, UUID deviceId, int queueIndex, long elapsedMillis,
            int durationSeconds, int volumePerMille, String sessionId, boolean playing) {
        if (owner == null || !(owner.level() instanceof ServerLevel serverLevel) || deviceId == null) {
            return;
        }
        PROGRESS_PERSISTENCE.recordAndFlush(serverLevel, deviceId, queueIndex, elapsedMillis, durationSeconds,
                volumePerMille, sessionId, playing);
    }

    public static void updateVolume(ServerPlayer owner, int volumePerMille) {
        if (owner == null) {
            return;
        }
        ItemStack stack = MP4Item.findPlayableInInventory(owner);
        updateVolume(MP4Item.readDeviceId(stack), volumePerMille);
    }

    public static void updateVolume(UUID deviceId, int volumePerMille) {
        if (deviceId == null) {
            return;
        }
        SESSION_REGISTRY.updateIfPresent(deviceId, session -> {
            Session updated = session.withVolume(volumePerMille);
            MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                ServerLevel level = server.getLevel(updated.levelKey());
                if (level != null) {
                    AUDIENCE_BROADCASTER.broadcastTimeline(level, updated, MonotonicMediaClock.nowNanos());
                }
            }
            return updated;
        });
    }

    public static int activeQueueIndex(UUID deviceId) {
        Session session = SESSION_REGISTRY.get(deviceId);
        return session != null ? session.queueIndex() : -1;
    }

    static boolean matchesActiveSession(UUID deviceId, PlaybackSessionId expectedSessionId, int queueIndex,
            String rawUrl) {
        Session session = SESSION_REGISTRY.get(deviceId);
        return session != null && expectedSessionId != null
                && expectedSessionId.equals(session.playbackSessionId())
                && session.queueIndex() == queueIndex
                && Objects.equals(session.rawUrl(), rawUrl != null ? rawUrl : "");
    }

    static boolean refreshActiveSession(ServerPlayer requester, UUID deviceId, PlaybackSessionId expectedSessionId,
            int queueIndex, String rawUrl, String resolvedPlayUrl, String songName, int durationSeconds,
            long targetMillis) {
        if (requester == null || deviceId == null || expectedSessionId == null || resolvedPlayUrl == null
                || resolvedPlayUrl.isBlank() || !(requester.level() instanceof ServerLevel level)) {
            return false;
        }
        Session current = SESSION_REGISTRY.get(deviceId);
        if (current == null || !matchesActiveSession(deviceId, expectedSessionId, queueIndex, rawUrl)) {
            return false;
        }
        int safeDurationSeconds = Math.max(0, durationSeconds);
        long elapsedMillis = clampElapsed(targetMillis, safeDurationSeconds);
        String syncedPlayUrl = PlaybackSync.withSync(resolvedPlayUrl, expectedSessionId, elapsedMillis,
                safeDurationSeconds * 1000L);
        long nowNanos = MonotonicMediaClock.nowNanos();
        long gameTime = MonotonicMediaClock.nowTick();
        Session refreshed = current.withRefreshedPlayback(syncedPlayUrl, songName, safeDurationSeconds,
                elapsedMillis, nowNanos, gameTime);
        if (!SESSION_REGISTRY.replace(deviceId, current, refreshed)) {
            return false;
        }
        recordAudit(level, refreshed, nowNanos);
        PROGRESS_PERSISTENCE.recordPlaying(level, refreshed, nowNanos);
        broadcastSession(level, refreshed, nowNanos);
        return true;
    }

    public static void reconcileQueueChange(ServerPlayer owner, UUID deviceId, List<ItemStack> newQueue) {
        reconcileQueueChange(owner, deviceId, newQueue, -1);
    }

    public static void reconcileQueueChange(ServerPlayer owner, UUID deviceId, List<ItemStack> newQueue,
            int removedIndex) {
        FAILURE_RECOVERY.cancel(deviceId);
        RESOLVE_INTENTS.onQueueRemoval(deviceId, removedIndex);
        QUEUE_CONTROLLER.reconcileQueueChange(owner, deviceId, newQueue, removedIndex);
    }

    public static int unlinkAllHeadphones(ServerPlayer actor, UUID deviceId) {
        if (actor == null || deviceId == null) {
            return 0;
        }
        int changed = 0;
        if (!(actor.level() instanceof ServerLevel actorLevel)) {
            return 0;
        }
        var server = actorLevel.getServer();
        MP4PlaybackSyncPacket stop = AUDIENCE_BROADCASTER.stopPacketFor(deviceId);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (clearMp4LinksFromPlayer(player, deviceId)) {
                changed++;
                PacketDistributor.sendToPlayer(player, stop);
            }
            AudioLinkIndex.updatePlayerHeadphones(player);
        }
        for (UUID playerId : AudioLinkIndex.removeHeadphonePlayersForMp4(deviceId)) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                PacketDistributor.sendToPlayer(player, stop);
            }
        }
        return changed;
    }

    public static int unlinkAllHolographicGlasses(ServerPlayer actor, UUID deviceId) {
        if (actor == null || deviceId == null || !(actor.level() instanceof ServerLevel actorLevel)) {
            return 0;
        }
        int changed = 0;
        var server = actorLevel.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (clearHolographicGlassesLinksFromPlayer(player, deviceId)) {
                changed++;
            }
        }
        return changed;
    }

    public static boolean resumeExisting(ServerPlayer owner, UUID deviceId, int queueIndex, int volumePerMille) {
        return resumeExisting(owner, deviceId, queueIndex, volumePerMille, -1L);
    }

    public static boolean resumeExisting(ServerPlayer owner, UUID deviceId, int queueIndex, int volumePerMille,
            long targetMillis) {
        if (owner == null || deviceId == null || !(owner.level() instanceof ServerLevel level)) {
            return false;
        }
        Session session = SESSION_REGISTRY.get(deviceId);
        if (session == null || session.queueIndex() != queueIndex) {
            return false;
        }
        long nowNanos = MonotonicMediaClock.nowNanos();
        long gameTime = MonotonicMediaClock.nowTick();
        Session base = session.withVolume(volumePerMille);
        if (targetMillis >= 0L) {
            long elapsedMillis = clampElapsed(targetMillis, session.durationSeconds());
            base = base.withElapsedAtNanos(elapsedMillis, nowNanos, gameTime);
            PROGRESS_PERSISTENCE.recordAndFlush(level, deviceId, queueIndex, elapsedMillis,
                    session.durationSeconds(), Math.max(0, Math.min(1000, volumePerMille)),
                    java.util.Optional.of(session.playbackSessionId()), true);
        }
        Session resumed = base.asPlayerSource(owner, gameTime);
        if (!SESSION_REGISTRY.replace(deviceId, session, resumed)) {
            return false;
        }
        RESOLVE_INTENTS.invalidate(deviceId);
        broadcastSession(level, resumed, nowNanos);
        return true;
    }

    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        Player player = event.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer) || !(serverPlayer.level() instanceof ServerLevel level)) {
            return;
        }
        ItemEntity itemEntity = event.getEntity();
        UUID deviceId = MP4Item.readDeviceId(itemEntity.getItem());
        if (deviceId == null) {
            return;
        }
        Session session = SESSION_REGISTRY.remove(deviceId);
        if (session == null) {
            return;
        }
        long nowNanos = MonotonicMediaClock.nowNanos();
        long gameTime = MonotonicMediaClock.nowTick();
        Session migrated = session.asItemSource(level, itemEntity.getId(), itemEntity.blockPosition(), gameTime);
        MP4DeviceLocationIndex.recordItemEntity(level, itemEntity, deviceId);
        PROGRESS_PERSISTENCE.recordPlaying(level, migrated, nowNanos);
        SESSION_REGISTRY.replace(migrated.sourceId(), migrated);
        broadcastSession(level, migrated, nowNanos);
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        SOURCE_DISCOVERY.scanContainerEventMenu(event.getEntity(), event.getContainer());
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        SOURCE_DISCOVERY.scanContainerEventMenu(event.getEntity(), event.getContainer());
    }

    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PROGRESS_PERSISTENCE.flush(level);
            MP4DeviceStateStore.flush(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        BiliWhitelistManager.serverClosing(event.getServer());
        WhitelistReviewActionPacket.clearPendingShortMutations();
        WhitelistPreviewPacket.clearPendingPreviews();
        com.zhongbai233.net_music_can_play_bili.server.DeferredDiscRequests.clear();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            PROGRESS_PERSISTENCE.flush(level);
            MP4DeviceStateStore.flush(level);
        }
        PROGRESS_PERSISTENCE.clearRuntime();
        MP4DeviceStateStore.clearRuntime();
        MP4StatePacket.clearClientSyncs();
        PadStatePacket.clearClientSyncs();
        AudioLinkIndex.clear();
        MP4DeviceLocationIndex.clear();
        MP4DeviceHolderTracker.clear();
        PadDeviceHolderTracker.clear();
        PadDocumentStore.clearRuntime();
        com.zhongbai233.net_music_can_play_bili.server.ControlConsoleEditLeaseRegistry.clear();
        com.zhongbai233.net_music_can_play_bili.server.ControlConsoleConsumerLeaseRegistry.clear();
        SESSION_REGISTRY.clear();
        RESOLVE_INTENTS.clear();
        FAILURE_RECOVERY.clear();
        BiliWhitelistManager.clearCanonicalCache();
        UNKNOWN_BUDGET.clear();
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID playerId = player.getUUID();
            MP4StatePacket.removeClientSyncs(playerId);
            PadStatePacket.removeClientSyncs(playerId);
            PadPlaybackControlPacket.invalidatePlayer(playerId);
            NetworkRateLimiter.removePlayer(playerId);
            com.zhongbai233.net_music_can_play_bili.server.ControlConsoleEditLeaseRegistry.releasePlayer(playerId);
            com.zhongbai233.net_music_can_play_bili.server.ControlConsoleConsumerLeaseRegistry.releasePlayer(playerId);
            AudioLinkIndex.removeHeadphonePlayer(playerId);
            AudioLinkIndex.removeHeadphoneOwner(playerId);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server == null) {
            return;
        }
        MP4DeviceHolderTracker.tick(server);
        PadDeviceHolderTracker.tick(server);
        if (server.getTickCount() % 20 == 0) {
            com.zhongbai233.net_music_can_play_bili.server.ControlConsoleEditLeaseRegistry
                .cleanupExpired(System.currentTimeMillis());
                com.zhongbai233.net_music_can_play_bili.server.ControlConsoleConsumerLeaseRegistry
                    .cleanupExpired(System.currentTimeMillis());
        }
        if (server.getTickCount() % DISCOVERY_INTERVAL_TICKS == 0) {
            for (ServerLevel level : server.getAllLevels()) {
                for (ServerPlayer player : level.players()) {
                    AudioLinkIndex.updatePlayerHeadphones(player);
                }
                SOURCE_DISCOVERY.discoverPlayingSources(level);
            }
        }
        List<Map.Entry<UUID, Session>> activeSessions = SESSION_REGISTRY.entries();
        UNKNOWN_BUDGET.retain(activeSessions.stream().map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()));
        for (Map.Entry<UUID, Session> entry : activeSessions) {
            UUID deviceId = entry.getKey();
            Session session = entry.getValue();
            ServerLevel serverLevel = server.getLevel(session.levelKey());
            if (serverLevel == null) {
                TERMINATION_APPLICATION.forgetUnavailableLevel(deviceId, session);
                continue;
            }
            long nowNanos = MonotonicMediaClock.nowNanos();
            long gameTime = MonotonicMediaClock.nowTick();
            Session refreshed = SOURCE_DISCOVERY.refreshActiveSource(server, session, gameTime);
            if (refreshed != null && !MP4PlaybackSourceDiscovery.sameSource(session, refreshed)) {
                if (!SESSION_REGISTRY.replace(deviceId, session, refreshed)) {
                    continue;
                }
                session = refreshed;
                ServerLevel refreshedLevel = server.getLevel(session.levelKey());
                if (refreshedLevel != null) {
                    serverLevel = refreshedLevel;
                    broadcastSession(serverLevel, session, nowNanos);
                }
            }
            ItemStack stack = MP4PlaybackSourceDiscovery.stack(serverLevel, session);
            if (!MP4PlaybackSourceDiscovery.isActiveMediaStack(serverLevel, stack, session.sourceId())) {
                Session relocated = SOURCE_DISCOVERY.relocateSession(server, session, gameTime);
                if (relocated != null) {
                    if (!SESSION_REGISTRY.replace(deviceId, session, relocated)) {
                        continue;
                    }
                    ServerLevel relocatedLevel = server.getLevel(relocated.levelKey());
                    if (relocatedLevel != null) {
                        broadcastSession(relocatedLevel, relocated, nowNanos);
                    }
                    continue;
                }
                Long missingSince = SESSION_REGISTRY.markMissingIfCurrent(deviceId, session, gameTime);
                if (missingSince == null) {
                    continue;
                }
                if (gameTime - missingSince < SOURCE_MISSING_GRACE_TICKS) {
                    continue;
                }
                TERMINATION_APPLICATION.stopMissingSource(deviceId, serverLevel, stack, session, nowNanos);
                continue;
            }
            if (!SESSION_REGISTRY.clearMissingIfCurrent(deviceId, session)) {
                continue;
            }
            boolean unknownExpired = session.durationSeconds() <= 0 && UNKNOWN_BUDGET.tick(deviceId, session.sessionId());
            // Commands/retries have their own resolver timeout and always own the next transition.
            if (!mayCompletePlayback(deviceId)) continue;
            if (unknownExpired) {
                TERMINATION_APPLICATION.stopUnknownTimeout(deviceId, serverLevel, stack, session, nowNanos);
                continue;
            }
            if (TERMINATION_APPLICATION.completeKnownDuration(deviceId, serverLevel, stack, session,
                    nowNanos, gameTime)) {
                continue;
            }
            PROGRESS_PERSISTENCE.recordPlaying(serverLevel, session, nowNanos);
            recordAudit(serverLevel, session, nowNanos);
            if (gameTime - session.lastSyncGameTime() >= SYNC_INTERVAL_TICKS) {
                Session synced = session.withLastSyncGameTime(gameTime);
                if (!SESSION_REGISTRY.replace(deviceId, session, synced)) {
                    continue;
                }
                if (gameTime - session.lastFullSyncGameTime() >= FULL_SYNC_INTERVAL_TICKS) {
                    Session fullSynced = synced.withLastFullSyncGameTime(gameTime);
                    if (SESSION_REGISTRY.replace(deviceId, synced, fullSynced)) {
                        broadcastSession(serverLevel, fullSynced, nowNanos);
                    }
                } else {
                    AUDIENCE_BROADCASTER.broadcastTimeline(serverLevel, synced, nowNanos);
                }
            }
        }
    }

    private static void startDiscovered(ServerLevel level, ItemStack stack, UUID ownerId, UUID sourceId, int sourceType,
            int sourceEntityId, BlockPos sourcePos, int containerSlot) {
        if (SESSION_REGISTRY.contains(sourceId) || !mayCompletePlayback(sourceId)) {
            return;
        }
        MP4DeviceStateStore.DeviceEntry deviceEntry = MP4DeviceStateStore.getOrCreate(level, sourceId, stack);
        List<ItemStack> queue = MP4PlaybackQueueController.queueForDevice(deviceEntry, stack);
        MP4Item.State state = deviceEntry.state();
        if (queue.isEmpty()) {
            return;
        }
        int index = Math.max(0, Math.min(queue.size() - 1, state.selectedQueueIndex()));
        @SuppressWarnings("null")
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(queue.get(index));
        FAILURE_RECOVERY.beginDiscovery(sourceId, MP4PlaybackQueueController.sourceUrls(queue));
        if (songInfo == null || songInfo.vip && !MusicPlayResolverManager.canResolve(songInfo)) {
            handleQueueFailure(level, stack, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    index, songInfo == null ? null : songInfo.songUrl);
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
        if (!BiliWhitelistManager.isShortLink(songInfo.songUrl)
                && !isPlaybackAllowed(level, songInfo.songUrl, owner)) {
            handleQueueFailure(level, stack, ownerId, sourceId, sourceType, sourceEntityId, sourcePos,
                    containerSlot, index, songInfo.songUrl);
            return;
        }
        ItemMusicCD.SongInfo original = songInfo.clone();
        long targetMillis = PROGRESS_PERSISTENCE.targetMillis(level, sourceId, stack, state, index);
        MP4ResolveIntentRegistry.Intent intent = beginDiscoveredResolve(sourceId, index, original.songUrl);
        if (intent == null) {
            return;
        }
        if (SESSION_REGISTRY.contains(sourceId)) {
            RESOLVE_INTENTS.complete(sourceId, intent);
            return;
        }
        BiliWhitelistManager.resolveSong(original).whenCompleteAsync((resolved, error) -> {
            if (!BiliWhitelistManager.acceptsServer(level.getServer())
                    || !RESOLVE_INTENTS.isCurrent(sourceId, intent)) {
                return;
            }
            Session carrier = new Session(level.dimension(), ownerId, sourceId, sourceType, sourceEntityId,
                    sourcePos, containerSlot, index, "", original.songUrl, "", 0, state.volumePerMille(),
                    PlaybackSessionId.of(sourceId + "-guard"), 0, 0, 0, MonotonicMediaClock.paused(0L));
            if (MP4PlaybackSourceDiscovery.stack(level, carrier) != stack || !deviceState(level, stack, sourceId).playing()) {
                RESOLVE_INTENTS.complete(sourceId, intent);
                return;
            }
            if (error != null || resolved == null) {
                RESOLVE_INTENTS.complete(sourceId, intent);
                LOGGER.error("MP4 自动接管播放源解析失败: {}", original.songName, error);
                handleQueueFailure(level, stack, ownerId, sourceId, sourceType, sourceEntityId, sourcePos,
                        containerSlot, index, original.songUrl);
                return;
            }
            if (!(stack.getItem() instanceof MP4Item) || !deviceState(level, stack, sourceId).playing()) {
                RESOLVE_INTENTS.complete(sourceId, intent);
                return;
            }
            List<ItemStack> currentQueue = MP4PlaybackQueueController.queueForDevice(
                    MP4DeviceStateStore.getOrCreate(level, sourceId, stack), stack);
            if (index < 0 || index >= currentQueue.size()) {
                RESOLVE_INTENTS.complete(sourceId, intent);
                return;
            }
            @SuppressWarnings("null")
            ItemMusicCD.SongInfo current = ItemMusicCD.getSongInfo(currentQueue.get(index));
            if (current == null || !Objects.equals(current.songUrl, original.songUrl)) {
                RESOLVE_INTENTS.complete(sourceId, intent);
                return;
            }
            ServerPlayer currentOwner = level.getServer().getPlayerList().getPlayer(ownerId);
            if (!isPlaybackAllowed(level,
                    BiliWhitelistManager.playbackIdentity(original.songUrl, resolved.songUrl), currentOwner)) {
                RESOLVE_INTENTS.complete(sourceId, intent);
                handleQueueFailure(level, stack, ownerId, sourceId, sourceType, sourceEntityId, sourcePos,
                        containerSlot, index, original.songUrl);
                return;
            }
            var track = ServerMediaPlayback.resolvedTrack(original, resolved);
            String rawUrl = track.rawUrl();
            String playUrl = track.playUrl();
            if (playUrl.isBlank()) {
                RESOLVE_INTENTS.complete(sourceId, intent);
                handleQueueFailure(level, stack, ownerId, sourceId, sourceType, sourceEntityId, sourcePos,
                        containerSlot, index, original.songUrl);
                return;
            }
            String songName = track.songName();
            int durationSeconds = track.durationSeconds();
            long elapsedMillis = clampElapsed(targetMillis, durationSeconds);
            UUID deviceId = MP4Item.readDeviceId(stack);
            if (deviceId == null || !deviceId.equals(sourceId)
                    || !RESOLVE_INTENTS.isCurrent(sourceId, intent)) {
                RESOLVE_INTENTS.complete(sourceId, intent);
                return;
            }
            PlaybackSessionId playbackSessionId = PlaybackSessionId.of(
                    deviceId + "-mp4-" + System.nanoTime());
            String syncedPlayUrl = PlaybackSync.withSync(playUrl, playbackSessionId, elapsedMillis,
                    durationSeconds * 1000L);
            long nowNanos = MonotonicMediaClock.nowNanos();
            long gameTime = MonotonicMediaClock.nowTick();
            Session session = new Session(level.dimension(), ownerId, deviceId, sourceType, sourceEntityId, sourcePos,
                    containerSlot, index, syncedPlayUrl, rawUrl, songName == null ? "" : songName, durationSeconds,
                    state.volumePerMille(), playbackSessionId, gameTime - Math.round(elapsedMillis / 50.0D), gameTime,
                    gameTime, MonotonicMediaClock.running(elapsedMillis, nowNanos));
            if (MP4PlaybackSourceDiscovery.stack(level, session) != stack
                    || !BiliWhitelistManager.acceptsServer(level.getServer())) {
                RESOLVE_INTENTS.complete(deviceId, intent);
                return;
            }
            SESSION_REGISTRY.replace(deviceId, session);
            RESOLVE_INTENTS.complete(deviceId, intent);
            recordAudit(level, session, nowNanos);
            PROGRESS_PERSISTENCE.recordPlaying(level, session, nowNanos);
            broadcastSession(level, session, nowNanos);
        }, level.getServer());
    }

    static void beginAutomaticQueue(UUID sourceId, List<ItemStack> queue) {
        FAILURE_RECOVERY.beginAutomatic(sourceId, MP4PlaybackQueueController.sourceUrls(queue));
    }

    static void handleQueueFailure(ServerLevel level, ItemStack stack, UUID ownerId, UUID sourceId,
            int sourceType, int entityId, BlockPos pos, int slot, int failedIndex, String expectedUrl) {
        TERMINATION_APPLICATION.handleQueueFailure(level, stack,
                new MP4PlaybackTerminationApplication.QueueFailureCommand(ownerId, sourceId, sourceType,
                        entityId, pos, slot, failedIndex, expectedUrl));
    }

    public static void stopExternalPlaybackForLinkedHeadphones(ServerPlayer actor, UUID deviceId) {
        AUDIENCE_BROADCASTER.stopExternalPlaybackForLinkedHeadphones(actor, deviceId);
    }

    private static void broadcastSession(ServerLevel level, Session session, long nowNanos) {
        if (level == null || session == null) {
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(session.ownerId());
        if (!isPlaybackAllowed(level, session.admittedSourceUrl(), owner)) {
            if (SESSION_REGISTRY.remove(session.sourceId(), session)) {
                RESOLVE_INTENTS.invalidate(session.sourceId());
                AUDIENCE_BROADCASTER.broadcastStop(level, session);
            }
            return;
        }
        AUDIENCE_BROADCASTER.broadcastAuthorized(level, session, owner, nowNanos);
    }

    private static boolean isPlaybackAllowed(ServerLevel level, String sourceUrl, ServerPlayer actor) {
        return BiliWhitelistManager.isPlaybackAllowed(level, sourceUrl, actor, "播放");
    }

    private static boolean clearMp4LinksFromPlayer(ServerPlayer player, UUID deviceId) {
        boolean[] changedEquipped = { false };
        EquippedMediaItems.forEachEquipped(player, stack -> changedEquipped[0] |= clearMp4Link(stack, deviceId));
        boolean changed = changedEquipped[0];
        ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
        changed |= clearMp4Link(carried, deviceId);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            changed |= clearMp4Link(inventory.getItem(slot), deviceId);
        }
        if (changed) {
            inventory.setChanged();
            AudioLinkIndex.updatePlayerHeadphones(player);
        }
        return changed;
    }

    private static boolean clearMp4Link(ItemStack stack, UUID deviceId) {
        if (!HeadphoneAbility.has(stack)
                || !AudioLinkData.headphoneLinkedToMp4(stack, deviceId)) {
            return false;
        }
        AudioLinkData.clearHeadphoneMp4(stack);
        return true;
    }

    private static boolean clearHolographicGlassesLinksFromPlayer(ServerPlayer player, UUID deviceId) {
        boolean[] changedEquipped = { false };
        EquippedMediaItems.forEachEquipped(player,
                stack -> changedEquipped[0] |= clearHolographicGlassesLink(stack, deviceId));
        boolean changed = changedEquipped[0];
        ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
        changed |= clearHolographicGlassesLink(carried, deviceId);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            changed |= clearHolographicGlassesLink(inventory.getItem(slot), deviceId);
        }
        if (changed) {
            inventory.setChanged();
            if (player.containerMenu != null) {
                player.containerMenu.broadcastChanges();
            }
        }
        return changed;
    }

    private static boolean clearHolographicGlassesLink(ItemStack stack, UUID deviceId) {
        if (!HolographicGlassesItem.boundToMediaDevice(stack, deviceId)) {
            return false;
        }
        HolographicGlassesItem.clearBoundMediaDevice(stack, deviceId);
        return true;
    }

    private static void recordAudit(ServerLevel level, Session session, long nowNanos) {
        var position = MP4PlaybackSourceDiscovery.sourcePosition(level, session);
        PlaybackAuditManager.recordMp4(level, session.sourceId(),
                BlockPos.containing(position.x(), position.y(), position.z()),
                position.x(), position.y() + 0.65D, position.z(), session.songName(), session.rawUrl(),
                session.durationSeconds(), session.elapsedMillisAtNanos(nowNanos), session.ownerId());
    }

    private static long clampElapsed(long elapsedMillis, int durationSeconds) {
        return MP4PlaybackProgressPolicy.clampTarget(durationSeconds, elapsedMillis);
    }

    private static MP4Item.State deviceState(ServerLevel level, ItemStack stack, UUID deviceId) {
        if (deviceId == null) {
            return MP4Item.State.DEFAULT;
        }
        return MP4DeviceStateStore.getOrCreate(level, deviceId, stack).state();
    }

    record Session(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> levelKey,
            UUID ownerId, UUID sourceId, int sourceType, int sourceEntityId, BlockPos sourcePos, int containerSlot,
            int queueIndex, String playUrl, String rawUrl, String songName, int durationSeconds, int volumePerMille,
            PlaybackSessionId playbackSessionId, long startedGameTime,
            long lastSyncGameTime, long lastFullSyncGameTime, MonotonicMediaClock.Anchor playbackClock) {
        Session {
            playbackSessionId = Objects.requireNonNull(playbackSessionId, "playbackSessionId");
            playbackClock = Objects.requireNonNull(playbackClock, "playbackClock");
            if (PlaybackSync.parseTerminalToken(playUrl).isEmpty()) {
                playUrl = PlaybackSync.withTerminalToken(playUrl,
                        com.zhongbai233.net_music_can_play_bili.media.sync.MediaRequestToken.random());
            }
        }

        Session(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> levelKey,
                UUID ownerId, UUID sourceId, int sourceType, int sourceEntityId, BlockPos sourcePos,
                int containerSlot, int queueIndex, String playUrl, String rawUrl, String songName,
                int durationSeconds, int volumePerMille, String sessionId, long startedGameTime,
                long lastSyncGameTime, long lastFullSyncGameTime, MonotonicMediaClock.Anchor playbackClock) {
            this(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot, queueIndex,
                    playUrl, rawUrl, songName, durationSeconds, volumePerMille, PlaybackSessionId.of(sessionId),
                    startedGameTime, lastSyncGameTime, lastFullSyncGameTime, playbackClock);
        }

        String admittedSourceUrl() {
            return BiliWhitelistManager.playbackIdentity(rawUrl, playUrl);
        }

        String sessionId() {
            return playbackSessionId.value();
        }

        /** Playback time uses the exact admission anchor, independently of tick cadence/grace clocks. */
        long elapsedMillisAtNanos(long nowNanos) {
            return playbackClock.elapsedMillis(nowNanos, Math.max(0L, durationSeconds * 1000L));
        }

        Session withVolume(int newVolumePerMille) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds,
                    Math.max(0, Math.min(1000, newVolumePerMille)), playbackSessionId, startedGameTime,
                    lastSyncGameTime,
                    lastFullSyncGameTime, playbackClock);
        }

        Session withLastSyncGameTime(long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, playbackSessionId,
                    startedGameTime, gameTime, lastFullSyncGameTime, playbackClock);
        }

        Session withLastFullSyncGameTime(long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, playbackSessionId,
                    startedGameTime, lastSyncGameTime, gameTime, playbackClock);
        }

        Session withElapsedAtNanos(long elapsedMillis, long nowNanos, long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, playbackSessionId,
                    gameTime - Math.round(Math.max(0L, elapsedMillis) / 50.0D), gameTime, gameTime,
                    MonotonicMediaClock.running(Math.max(0L, elapsedMillis), nowNanos));
        }

        Session withRefreshedPlayback(String refreshedPlayUrl, String refreshedSongName,
                int refreshedDurationSeconds, long elapsedMillis, long nowNanos, long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, PlaybackSync.withTerminalToken(refreshedPlayUrl,
                            com.zhongbai233.net_music_can_play_bili.media.sync.MediaRequestToken.random()),
                    rawUrl, refreshedSongName != null ? refreshedSongName : songName,
                    Math.max(0, refreshedDurationSeconds), volumePerMille, playbackSessionId,
                    gameTime - Math.round(Math.max(0L, elapsedMillis) / 50.0D), gameTime, gameTime,
                    MonotonicMediaClock.running(Math.max(0L, elapsedMillis), nowNanos));
        }

        Session withQueueIndex(int newQueueIndex, long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    Math.max(0, newQueueIndex), playUrl, rawUrl, songName, durationSeconds, volumePerMille,
                    playbackSessionId, startedGameTime, gameTime, gameTime, playbackClock);
        }

        Session resetToStart(long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, playbackSessionId,
                    gameTime, gameTime, gameTime, MonotonicMediaClock.paused(0L));
        }

        Session asItemSource(ServerLevel level, int itemEntityId, BlockPos itemPos, long gameTime) {
            return withSource(level.dimension(), ownerId, ClientMediaSyncPayload.SOURCE_ITEM,
                    itemEntityId, itemPos.immutable(), -1, gameTime);
        }

        Session asPlayerSource(ServerPlayer player, long gameTime) {
            return withSource(player.level().dimension(), player.getUUID(), ClientMediaSyncPayload.SOURCE_PLAYER,
                    player.getId(), player.blockPosition(), -1, gameTime);
        }

        Session asBlockSource(ServerLevel level, BlockPos pos, int slot, long gameTime) {
            return withSource(level.dimension(), ownerId, ClientMediaSyncPayload.SOURCE_BLOCK,
                    -1, pos.immutable(), slot, gameTime);
        }

        Session asContainerEntitySource(ServerLevel level, Entity entity, int slot, long gameTime) {
            return withSource(level.dimension(), ownerId, ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY,
                    entity.getId(), entity.blockPosition(), slot, gameTime);
        }

        Session fromResolved(ServerLevel level, MP4DeviceLocationIndex.ResolvedLocation resolved, long gameTime) {
            UUID resolvedOwnerId = resolved.sourceType() == ClientMediaSyncPayload.SOURCE_PLAYER
                    && resolved.ownerId() != null ? resolved.ownerId() : ownerId;
            return withSource(level.dimension(), resolvedOwnerId, resolved.sourceType(), resolved.sourceEntityId(),
                    resolved.sourcePos().immutable(), resolved.containerSlot(), gameTime);
        }

        private Session withSource(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> newLevel,
                UUID newOwnerId, int newSourceType, int newEntityId, BlockPos newPos, int newSlot, long gameTime) {
            return new Session(newLevel, newOwnerId, sourceId, newSourceType, newEntityId, newPos, newSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille,
                    playbackSessionId, startedGameTime, gameTime, gameTime, playbackClock);
        }
    }
}
