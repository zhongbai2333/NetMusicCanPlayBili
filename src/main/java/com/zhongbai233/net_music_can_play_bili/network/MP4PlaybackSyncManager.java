package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
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

/** 手持 MP4 设备的服务端权威播放时间线。 */
@EventBusSubscriber
public final class MP4PlaybackSyncManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int FULL_SYNC_INTERVAL_TICKS = 300;
    private static final int DISCOVERY_INTERVAL_TICKS = 40;
    private static final int SOURCE_MISSING_GRACE_TICKS = 20;
    private static final double SYNC_RANGE = 64.0D;
    private static final MP4PlaybackSourceSessionRegistry<Session> SESSION_REGISTRY =
            new MP4PlaybackSourceSessionRegistry<>();
    private static final MP4ResolveIntentRegistry RESOLVE_INTENTS = new MP4ResolveIntentRegistry();
    private static final MP4PlaybackRetryAdmission<Session> RETRY_ADMISSION =
            new MP4PlaybackRetryAdmission<>(SESSION_REGISTRY, RESOLVE_INTENTS,
                    session -> session.playbackSessionId(), session -> session.queueIndex(),
                    session -> session.rawUrl());
    private static final MP4PlaybackProgressPersistence PROGRESS_PERSISTENCE =
            new MP4PlaybackProgressPersistence();
    private static final MP4PlaybackAudienceBroadcaster AUDIENCE_BROADCASTER =
            new MP4PlaybackAudienceBroadcaster(SESSION_REGISTRY, SYNC_RANGE, RESOLVE_INTENTS::invalidate,
                    MP4PlaybackSyncManager::isPlaybackAllowed);
    private static final MP4PlaybackSourceDiscovery SOURCE_DISCOVERY = new MP4PlaybackSourceDiscovery(
            SESSION_REGISTRY, SYNC_RANGE, MP4PlaybackSyncManager::startDiscovered,
            AUDIENCE_BROADCASTER::broadcast);
    private static final MP4PlaybackQueueController QUEUE_CONTROLLER = new MP4PlaybackQueueController(
            SESSION_REGISTRY, RESOLVE_INTENTS::invalidate, PROGRESS_PERSISTENCE::record,
            PROGRESS_PERSISTENCE::flush, AUDIENCE_BROADCASTER::broadcast,
            AUDIENCE_BROADCASTER::broadcastStop, MP4PlaybackSyncManager::startDiscovered);

    private MP4PlaybackSyncManager() {
    }

    static MP4ResolveIntentRegistry.Intent beginCommandResolve(UUID deviceId, int queueIndex, String sourceUrl) {
        return RESOLVE_INTENTS.replace(deviceId, queueIndex, sourceUrl);
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
                || !isPlaybackAllowed(level, attempt.sourceUrl(), requester)) {
            completeRetryResolve(deviceId, attempt);
            return false;
        }
        int safeDurationSeconds = Math.max(1, durationSeconds);
        long elapsedMillis = clampElapsed(targetMillis, safeDurationSeconds);
        String syncedPlayUrl = PlaybackSync.withSync(resolvedPlayUrl, attempt.expectedSessionId(), elapsedMillis,
                safeDurationSeconds * 1000L);
        long gameTime = level.getGameTime();
        Session refreshed = RETRY_ADMISSION.replaceIfCurrent(deviceId, attempt,
                current -> current.withRefreshedPlayback(syncedPlayUrl, songName, safeDurationSeconds,
                        elapsedMillis, gameTime));
        if (refreshed == null) {
            return false;
        }
        refreshed.recordAudit(level, gameTime);
        PROGRESS_PERSISTENCE.persist(requester, refreshed, gameTime, true);
        AUDIENCE_BROADCASTER.broadcast(level, refreshed, gameTime);
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
        if (!isPlaybackAllowed(serverLevel, packet.rawUrl(), owner)) {
            PacketDistributor.sendToPlayer(owner,
                    MP4PlaybackSyncPacket.stop(packet.ownerId(), packet.sourceId(), packet.queueIndex()));
            return;
        }
        long gameTime = serverLevel.getGameTime();
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
                Math.max(1, packet.durationSeconds()),
                packet.volumePerMille(),
                playbackSessionId,
                gameTime - Math.round(elapsedMillis / 50.0D),
                gameTime,
                gameTime);
        RESOLVE_INTENTS.invalidate(session.sourceId());
        SESSION_REGISTRY.replace(session.sourceId(), session);
        session.recordAudit(serverLevel, gameTime);
        AUDIENCE_BROADCASTER.broadcast(serverLevel, session, gameTime);
        PROGRESS_PERSISTENCE.persist(owner, session, gameTime, true);
    }

    public static void stop(ServerPlayer owner) {
        if (owner == null) {
            return;
        }
        ItemStack stack = MP4Item.findPlayableInInventory(owner);
        stop(owner, MP4Item.readDeviceId(stack));
    }

    public static void stop(ServerPlayer owner, UUID deviceId) {
        if (owner != null && deviceId != null) {
            RESOLVE_INTENTS.invalidate(deviceId);
            Session session = SESSION_REGISTRY.remove(deviceId);
            if (session != null && owner.level() instanceof ServerLevel serverLevel) {
                PROGRESS_PERSISTENCE.persist(session.stack(serverLevel), session, serverLevel.getGameTime(), false);
                AUDIENCE_BROADCASTER.broadcastStop(serverLevel, session);
            }
        }
    }

    public static int currentProgressPerMille(ServerPlayer owner, int fallback) {
        if (owner == null || !(owner.level() instanceof ServerLevel serverLevel)) {
            return fallback;
        }
        ItemStack stack = MP4Item.findPlayableInInventory(owner);
        UUID deviceId = MP4Item.readDeviceId(stack);
        Session session = SESSION_REGISTRY.get(deviceId);
        return session != null ? MP4PlaybackProgressPolicy.progressPerMille(
                session.elapsedMillis(serverLevel.getGameTime()),
                session.durationSeconds()) : fallback;
    }

    public static long currentElapsedMillis(ServerPlayer owner, UUID deviceId, long fallback) {
        if (owner == null || !(owner.level() instanceof ServerLevel serverLevel) || deviceId == null) {
            return Math.max(0L, fallback);
        }
        Session session = SESSION_REGISTRY.get(deviceId);
        if (session != null) {
            return session.elapsedMillis(serverLevel.getGameTime());
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
                    AUDIENCE_BROADCASTER.broadcastTimeline(level, updated, level.getGameTime());
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
        int safeDurationSeconds = Math.max(1, durationSeconds);
        long elapsedMillis = clampElapsed(targetMillis, safeDurationSeconds);
        String syncedPlayUrl = PlaybackSync.withSync(resolvedPlayUrl, expectedSessionId, elapsedMillis,
                safeDurationSeconds * 1000L);
        long gameTime = level.getGameTime();
        Session refreshed = current.withRefreshedPlayback(syncedPlayUrl, songName, safeDurationSeconds,
                elapsedMillis, gameTime);
        if (!SESSION_REGISTRY.replace(deviceId, current, refreshed)) {
            return false;
        }
        refreshed.recordAudit(level, gameTime);
        PROGRESS_PERSISTENCE.persist(requester, refreshed, gameTime, true);
        AUDIENCE_BROADCASTER.broadcast(level, refreshed, gameTime);
        return true;
    }

    public static void reconcileQueueChange(ServerPlayer owner, UUID deviceId, List<ItemStack> newQueue) {
        QUEUE_CONTROLLER.reconcileQueueChange(owner, deviceId, newQueue);
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
        long gameTime = level.getGameTime();
        Session base = session.withVolume(volumePerMille);
        if (targetMillis >= 0L) {
            long elapsedMillis = clampElapsed(targetMillis, session.durationSeconds());
            base = base.withStartedGameTime(gameTime - Math.round(elapsedMillis / 50.0D), gameTime);
            PROGRESS_PERSISTENCE.recordAndFlush(level, deviceId, queueIndex, elapsedMillis,
                    session.durationSeconds(), Math.max(0, Math.min(1000, volumePerMille)),
                    java.util.Optional.of(session.playbackSessionId()), true);
        }
        Session resumed = base.asPlayerSource(owner, gameTime);
        if (!SESSION_REGISTRY.replace(deviceId, session, resumed)) {
            return false;
        }
        RESOLVE_INTENTS.invalidate(deviceId);
        AUDIENCE_BROADCASTER.broadcast(level, resumed, gameTime);
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
        long gameTime = level.getGameTime();
        Session migrated = session.asItemSource(itemEntity.getId(), itemEntity.blockPosition(), gameTime);
        MP4DeviceLocationIndex.recordItemEntity(level, itemEntity, deviceId);
        PROGRESS_PERSISTENCE.persist(itemEntity.getItem(), migrated, gameTime, true);
        SESSION_REGISTRY.replace(migrated.sourceId(), migrated);
        AUDIENCE_BROADCASTER.broadcast(level, migrated, gameTime);
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
        for (ServerLevel level : event.getServer().getAllLevels()) {
            PROGRESS_PERSISTENCE.flush(level);
            MP4DeviceStateStore.flush(level);
        }
        PROGRESS_PERSISTENCE.clearRuntime();
        AudioLinkIndex.clear();
        MP4DeviceLocationIndex.clear();
        MP4DeviceHolderTracker.clear();
        PadDeviceHolderTracker.clear();
        PadDocumentStore.clearRuntime();
        com.zhongbai233.net_music_can_play_bili.server.ControlConsoleEditLeaseRegistry.clear();
        com.zhongbai233.net_music_can_play_bili.server.ControlConsoleConsumerLeaseRegistry.clear();
        SESSION_REGISTRY.clear();
        RESOLVE_INTENTS.clear();
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID playerId = player.getUUID();
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
        if (SESSION_REGISTRY.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Session> entry : SESSION_REGISTRY.entries()) {
            UUID deviceId = entry.getKey();
            Session session = entry.getValue();
            ServerLevel serverLevel = server.getLevel(session.levelKey());
            if (serverLevel == null) {
                if (SESSION_REGISTRY.remove(deviceId, session)) {
                    RESOLVE_INTENTS.invalidate(session.sourceId());
                }
                continue;
            }
            long gameTime = serverLevel.getGameTime();
            Session refreshed = SOURCE_DISCOVERY.refreshActiveSource(server, session, gameTime);
            if (refreshed != null && !MP4PlaybackSourceDiscovery.sameSource(session, refreshed)) {
                if (!SESSION_REGISTRY.replace(deviceId, session, refreshed)) {
                    continue;
                }
                session = refreshed;
                ServerLevel refreshedLevel = server.getLevel(session.levelKey());
                if (refreshedLevel != null) {
                    AUDIENCE_BROADCASTER.broadcast(refreshedLevel, session, gameTime);
                }
            }
            ItemStack stack = session.stack(serverLevel);
            if (!isActiveMediaStack(serverLevel, stack, session.sourceId())) {
                Session relocated = SOURCE_DISCOVERY.relocateSession(server, session, gameTime);
                if (relocated != null) {
                    if (!SESSION_REGISTRY.replace(deviceId, session, relocated)) {
                        continue;
                    }
                    AUDIENCE_BROADCASTER.broadcast(serverLevel, relocated, gameTime);
                    continue;
                }
                Long missingSince = SESSION_REGISTRY.markMissingIfCurrent(deviceId, session, gameTime);
                if (missingSince == null) {
                    continue;
                }
                if (gameTime - missingSince < SOURCE_MISSING_GRACE_TICKS) {
                    continue;
                }
                if (!SESSION_REGISTRY.remove(deviceId, session)) {
                    continue;
                }
                PROGRESS_PERSISTENCE.persist(stack, session, gameTime, false);
                RESOLVE_INTENTS.invalidate(session.sourceId());
                AUDIENCE_BROADCASTER.broadcastStop(serverLevel, session);
                continue;
            }
            if (!SESSION_REGISTRY.clearMissingIfCurrent(deviceId, session)) {
                continue;
            }
            long elapsed = session.elapsedMillis(gameTime);
            if (elapsed >= (long) session.durationSeconds() * 1000L) {
                if (!SESSION_REGISTRY.remove(deviceId, session)) {
                    continue;
                }
                if (!QUEUE_CONTROLLER.tryAdvanceQueue(serverLevel, stack, session)) {
                    PROGRESS_PERSISTENCE.persist(stack, session.resetToStart(gameTime), gameTime, false);
                    AUDIENCE_BROADCASTER.broadcastStop(serverLevel, session);
                }
                continue;
            }
            PROGRESS_PERSISTENCE.persist(stack, session, gameTime, true);
            session.recordAudit(serverLevel, gameTime);
            if (gameTime - session.lastSyncGameTime() >= SYNC_INTERVAL_TICKS) {
                Session synced = session.withLastSyncGameTime(gameTime);
                if (!SESSION_REGISTRY.replace(deviceId, session, synced)) {
                    continue;
                }
                if (gameTime - session.lastFullSyncGameTime() >= FULL_SYNC_INTERVAL_TICKS) {
                    Session fullSynced = synced.withLastFullSyncGameTime(gameTime);
                    if (SESSION_REGISTRY.replace(deviceId, synced, fullSynced)) {
                        AUDIENCE_BROADCASTER.broadcast(serverLevel, fullSynced, gameTime);
                    }
                } else {
                    AUDIENCE_BROADCASTER.broadcastTimeline(serverLevel, synced, gameTime);
                }
            }
        }
    }

    private static void startDiscovered(ServerLevel level, ItemStack stack, UUID ownerId, UUID sourceId, int sourceType,
            int sourceEntityId, BlockPos sourcePos, int containerSlot) {
        if (SESSION_REGISTRY.contains(sourceId)) {
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
        if (songInfo == null || songInfo.vip && !MusicPlayResolverManager.canResolve(songInfo)) {
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
        if (!isPlaybackAllowed(level, songInfo.songUrl, owner)) {
            MP4DeviceStateStore.updateState(level, sourceId,
                    new MP4Item.State(false, state.shuffle(), state.videoEnabled(),
                            state.landscape(), state.qualityIndex(), index, state.queueScrollOffset(),
                            state.volumePerMille(),
                            state.repeatMode(), state.playlistOpen(), state.lyricsEnabled(), state.subtitleMode(),
                            state.subtitleAiEnabled(), state.progressPerMille(), state.rotationHintShown()));
            return;
        }
        ItemMusicCD.SongInfo original = songInfo.clone();
        long targetMillis = PROGRESS_PERSISTENCE.targetMillis(level, sourceId, stack, state, index);
        MP4ResolveIntentRegistry.Intent intent = RESOLVE_INTENTS.begin(sourceId, index, original.songUrl);
        if (intent == null) {
            return;
        }
        if (SESSION_REGISTRY.contains(sourceId)) {
            RESOLVE_INTENTS.complete(sourceId, intent);
            return;
        }
        MusicPlayResolverManager.resolve(original.clone()).whenCompleteAsync((resolved, error) -> {
            if (!RESOLVE_INTENTS.isCurrent(sourceId, intent)) {
                return;
            }
            if (error != null) {
                RESOLVE_INTENTS.complete(sourceId, intent);
                LOGGER.error("MP4 自动接管播放源解析失败: {}", original.songName, error);
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
            if (!isPlaybackAllowed(level, original.songUrl, currentOwner)) {
                RESOLVE_INTENTS.complete(sourceId, intent);
                return;
            }
            String rawUrl = original.songUrl != null ? original.songUrl : "";
            String playUrl = resolved.songUrl != null && !resolved.songUrl.isBlank() ? resolved.songUrl : rawUrl;
            if (BiliApiClient.isStoredVideoSelection(rawUrl)) {
                playUrl = rawUrl;
            }
            if (playUrl.isBlank()) {
                RESOLVE_INTENTS.complete(sourceId, intent);
                return;
            }
            String songName = resolved.songName != null && !resolved.songName.isBlank() ? resolved.songName
                    : original.songName;
            int durationSeconds = Math.max(1, resolved.songTime > 0 ? resolved.songTime : original.songTime);
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
            long gameTime = level.getGameTime();
            Session session = new Session(level.dimension(), ownerId, deviceId, sourceType, sourceEntityId, sourcePos,
                    containerSlot, index, syncedPlayUrl, rawUrl, songName == null ? "" : songName, durationSeconds,
                    state.volumePerMille(), playbackSessionId, gameTime - Math.round(elapsedMillis / 50.0D), gameTime,
                    gameTime);
            SESSION_REGISTRY.replace(deviceId, session);
            RESOLVE_INTENTS.complete(deviceId, intent);
            session.recordAudit(level, gameTime);
            PROGRESS_PERSISTENCE.persist(stack, session, gameTime, true);
            AUDIENCE_BROADCASTER.broadcast(level, session, gameTime);
        }, level.getServer());
    }

    public static void stopExternalPlaybackForLinkedHeadphones(ServerPlayer actor, UUID deviceId) {
        AUDIENCE_BROADCASTER.stopExternalPlaybackForLinkedHeadphones(actor, deviceId);
    }

    private static boolean isPlaybackAllowed(ServerLevel level, String sourceUrl, ServerPlayer actor) {
        if (BiliSongInfoSanitizer.isForbiddenBiliDirectUrl(sourceUrl)) {
            if (actor != null) {
                actor.sendSystemMessage(BiliWhitelistManager.denialMessage(actor, sourceUrl, "播放"));
            }
            return false;
        }
        if (!BiliWhitelistManager.enabled() || BiliWhitelistManager.canonicalResource(sourceUrl).isEmpty()) {
            return true;
        }
        if (BiliWhitelistManager.isAllowed(level.getServer(), sourceUrl)) {
            return true;
        }
        if (actor != null) {
            actor.sendSystemMessage(BiliWhitelistManager.denialMessage(actor, sourceUrl, "播放"));
        }
        return false;
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

    private static long clampElapsed(long elapsedMillis, int durationSeconds) {
        long max = Math.max(0L, (long) Math.max(1, durationSeconds) * 1000L - 50L);
        return Math.max(0L, Math.min(max, elapsedMillis));
    }

    private static MP4Item.State deviceState(ServerLevel level, ItemStack stack, UUID deviceId) {
        if (deviceId == null) {
            return MP4Item.State.DEFAULT;
        }
        return MP4DeviceStateStore.getOrCreate(level, deviceId, stack).state();
    }

    private static boolean isActiveMediaStack(ServerLevel level, ItemStack stack, UUID deviceId) {
        if (stack.getItem() instanceof MP4Item) {
            return deviceState(level, stack, deviceId).playing();
        }
        return PadItem.isPad(stack) && deviceId != null && deviceId.equals(PadItem.readDeviceId(stack));
    }

    record Session(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> levelKey,
            UUID ownerId, UUID sourceId, int sourceType, int sourceEntityId, BlockPos sourcePos, int containerSlot,
            int queueIndex, String playUrl, String rawUrl, String songName, int durationSeconds, int volumePerMille,
            PlaybackSessionId playbackSessionId, long startedGameTime,
            long lastSyncGameTime, long lastFullSyncGameTime) {
        Session {
            playbackSessionId = Objects.requireNonNull(playbackSessionId, "playbackSessionId");
        }

        Session(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> levelKey,
                UUID ownerId, UUID sourceId, int sourceType, int sourceEntityId, BlockPos sourcePos,
                int containerSlot, int queueIndex, String playUrl, String rawUrl, String songName,
                int durationSeconds, int volumePerMille, String sessionId, long startedGameTime,
                long lastSyncGameTime, long lastFullSyncGameTime) {
            this(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot, queueIndex,
                    playUrl, rawUrl, songName, durationSeconds, volumePerMille, PlaybackSessionId.of(sessionId),
                    startedGameTime, lastSyncGameTime, lastFullSyncGameTime);
        }

        String sessionId() {
            return playbackSessionId.value();
        }

        long elapsedMillis(long gameTime) {
            return Math.min((long) durationSeconds * 1000L,
                    Math.max(0L, (gameTime - startedGameTime) * 50L));
        }

        Session withVolume(int newVolumePerMille) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds,
                    Math.max(0, Math.min(1000, newVolumePerMille)), playbackSessionId, startedGameTime,
                    lastSyncGameTime,
                    lastFullSyncGameTime);
        }

        Session withLastSyncGameTime(long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, playbackSessionId,
                    startedGameTime, gameTime, lastFullSyncGameTime);
        }

        Session withLastFullSyncGameTime(long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, playbackSessionId,
                    startedGameTime, lastSyncGameTime, gameTime);
        }

        Session withStartedGameTime(long newStartedGameTime, long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, playbackSessionId,
                    newStartedGameTime, gameTime, gameTime);
        }

        Session withRefreshedPlayback(String refreshedPlayUrl, String refreshedSongName,
                int refreshedDurationSeconds, long elapsedMillis, long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, refreshedPlayUrl, rawUrl, refreshedSongName != null ? refreshedSongName : songName,
                    Math.max(1, refreshedDurationSeconds), volumePerMille, playbackSessionId,
                    gameTime - Math.round(Math.max(0L, elapsedMillis) / 50.0D), gameTime, gameTime);
        }

        Session withQueueIndex(int newQueueIndex, long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    Math.max(0, newQueueIndex), playUrl, rawUrl, songName, durationSeconds, volumePerMille,
                    playbackSessionId, startedGameTime, gameTime, gameTime);
        }

        Session resetToStart(long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, playbackSessionId,
                    gameTime, gameTime, gameTime);
        }

        Session asItemSource(int itemEntityId, BlockPos itemPos, long gameTime) {
            return new Session(levelKey, ownerId, sourceId, ClientMediaSyncPayload.SOURCE_ITEM, itemEntityId,
                    itemPos.immutable(), -1, queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille,
                    playbackSessionId, startedGameTime, gameTime, gameTime);
        }

        Session asPlayerSource(ServerPlayer player, long gameTime) {
            return new Session(player.level().dimension(), player.getUUID(), sourceId,
                    ClientMediaSyncPayload.SOURCE_PLAYER,
                    player.getId(), player.blockPosition(), -1, queueIndex, playUrl, rawUrl, songName, durationSeconds,
                    volumePerMille, playbackSessionId, startedGameTime, gameTime, gameTime);
        }

        Session asBlockSource(BlockPos pos, int slot, long gameTime) {
            return new Session(levelKey, ownerId, sourceId, ClientMediaSyncPayload.SOURCE_BLOCK, -1, pos.immutable(),
                    slot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, playbackSessionId,
                    startedGameTime, gameTime, gameTime);
        }

        Session asContainerEntitySource(ServerLevel level, Entity entity, int slot, long gameTime) {
            return new Session(level.dimension(), ownerId, sourceId, ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY,
                    entity.getId(), entity.blockPosition(), slot, queueIndex, playUrl, rawUrl, songName,
                    durationSeconds,
                    volumePerMille, playbackSessionId, startedGameTime, gameTime, gameTime);
        }

        Session fromResolved(MP4DeviceLocationIndex.ResolvedLocation resolved, long gameTime) {
            UUID resolvedOwnerId = resolved.sourceType() == ClientMediaSyncPayload.SOURCE_PLAYER
                    && resolved.ownerId() != null ? resolved.ownerId() : ownerId;
            return new Session(levelKey, resolvedOwnerId, sourceId, resolved.sourceType(), resolved.sourceEntityId(),
                    resolved.sourcePos().immutable(),
                    resolved.containerSlot(), queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille,
                    playbackSessionId, startedGameTime, gameTime, gameTime);
        }

        void markContainerChanged() {
            ServerLevel level = currentLevel();
            if (level == null) {
                return;
            }
            if (sourceType == ClientMediaSyncPayload.SOURCE_BLOCK
                    && level.getBlockEntity(sourcePos) instanceof Container container) {
                container.setChanged();
                return;
            }
            if (sourceType == ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY
                    && level.getEntity(sourceEntityId) instanceof Container container) {
                container.setChanged();
            }
        }

        ServerLevel currentLevel() {
            MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            return server != null ? server.getLevel(levelKey) : null;
        }

        ItemStack stack(ServerLevel level) {
            MP4DeviceLocationIndex.ResolvedLocation indexed = MP4DeviceLocationIndex.resolve(level, sourceId)
                    .orElse(null);
            if (indexed != null && indexed.stack().getItem() instanceof MP4Item) {
                return indexed.stack();
            }
            if (sourceType == ClientMediaSyncPayload.SOURCE_ITEM) {
                if (level.getEntity(sourceEntityId) instanceof ItemEntity item) {
                    MP4DeviceLocationIndex.recordItemEntity(level, item, sourceId);
                    return item.getItem();
                }
            }
            if (sourceType == ClientMediaSyncPayload.SOURCE_BLOCK
                    && level.getBlockEntity(sourcePos) instanceof Container container
                    && containerSlot >= 0 && containerSlot < container.getContainerSize()) {
                MP4DeviceLocationIndex.recordBlockContainer(level, sourcePos, containerSlot, sourceId);
                return container.getItem(containerSlot);
            }
            if (sourceType == ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY
                    && level.getEntity(sourceEntityId) instanceof Entity entity
                    && entity instanceof Container container
                    && containerSlot >= 0 && containerSlot < container.getContainerSize()) {
                MP4DeviceLocationIndex.recordContainerEntity(level, entity, containerSlot, sourceId);
                return container.getItem(containerSlot);
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(sourceId);
            if (player == null) {
                for (ServerPlayer candidate : level.players()) {
                    ItemStack stack = MP4Item.findByDeviceId(candidate, sourceId);
                    if (stack.getItem() instanceof MP4Item) {
                        return stack;
                    }
                    ItemStack padStack = PadItem.findByDeviceId(candidate, sourceId);
                    if (PadItem.isPad(padStack)) {
                        return padStack;
                    }
                }
                return ItemStack.EMPTY;
            }
            ItemStack stack = MP4Item.findByDeviceId(player, sourceId);
            if (stack.getItem() instanceof MP4Item) {
                return stack;
            }
            return PadItem.findByDeviceId(player, sourceId);
        }

        SourcePosition sourcePosition(ServerLevel level) {
            if (sourceType == ClientMediaSyncPayload.SOURCE_ITEM) {
                if (level.getEntity(sourceEntityId) instanceof ItemEntity item) {
                    MP4DeviceLocationIndex.recordItemEntity(level, item, sourceId);
                    return new SourcePosition(item.getId(), item.getX(), item.getY() + 0.25D, item.getZ());
                }
            }
            if (sourceType == ClientMediaSyncPayload.SOURCE_BLOCK) {
                return new SourcePosition(-1, sourcePos.getX() + 0.5D, sourcePos.getY() + 0.5D,
                        sourcePos.getZ() + 0.5D);
            }
            if (sourceType == ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY
                    && level.getEntity(sourceEntityId) instanceof Entity entity) {
                MP4DeviceLocationIndex.recordContainerEntity(level, entity, containerSlot, sourceId);
                return new SourcePosition(entity.getId(), entity.getX(), entity.getY() + 0.5D, entity.getZ());
            }
            ServerPlayer player = ownerId != null ? level.getServer().getPlayerList().getPlayer(ownerId) : null;
            if (player != null) {
                return new SourcePosition(player.getId(), player.getX(), player.getY() + 1.2D, player.getZ());
            }
            return new SourcePosition(sourceEntityId, sourcePos.getX() + 0.5D, sourcePos.getY() + 0.5D,
                    sourcePos.getZ() + 0.5D);
        }

        void recordAudit(ServerLevel level, long gameTime) {
            SourcePosition position = sourcePosition(level);
            PlaybackAuditManager.recordMp4(level, sourceId,
                    BlockPos.containing(position.x(), position.y(), position.z()),
                    position.x(), position.y() + 0.65D, position.z(), songName, rawUrl,
                    durationSeconds, elapsedMillis(gameTime), ownerId);
        }
    }

    record SourcePosition(int entityId, double x, double y, double z) {
    }
}
