package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.bili.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.AABB;
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

import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 手持 MP4 设备的服务端权威播放时间线。 */
@EventBusSubscriber
public final class MP4PlaybackSyncManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int FULL_SYNC_INTERVAL_TICKS = 300;
    private static final int DISCOVERY_INTERVAL_TICKS = 40;
    private static final int SOURCE_MISSING_GRACE_TICKS = 20;
    private static final double SYNC_RANGE = 64.0D;
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> MISSING_SINCE = new ConcurrentHashMap<>();
    private static final Map<UUID, MP4PlaybackSavedData.Entry> RUNTIME_PROGRESS = new ConcurrentHashMap<>();

    private MP4PlaybackSyncManager() {
    }

    public static void start(ServerPlayer owner, MP4PlaybackSyncPacket packet) {
        if (owner == null || packet == null || !packet.playing() || packet.sessionId().isBlank()) {
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
                packet.sessionId(),
                gameTime - Math.round(elapsedMillis / 50.0D),
                gameTime,
                gameTime);
        SESSIONS.put(session.sourceId(), session);
        MISSING_SINCE.remove(session.sourceId());
        session.recordAudit(serverLevel, gameTime);
        send(serverLevel, session, gameTime);
        persistProgress(owner, session, gameTime, true);
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
            Session session = SESSIONS.remove(deviceId);
            MISSING_SINCE.remove(deviceId);
            if (session != null && owner.level() instanceof ServerLevel serverLevel) {
                persistProgress(session.stack(serverLevel), session, serverLevel.getGameTime(), false);
                sendStop(serverLevel, session);
            }
        }
    }

    public static int currentProgressPerMille(ServerPlayer owner, int fallback) {
        if (owner == null || !(owner.level() instanceof ServerLevel serverLevel)) {
            return fallback;
        }
        ItemStack stack = MP4Item.findPlayableInInventory(owner);
        UUID deviceId = MP4Item.readDeviceId(stack);
        Session session = deviceId != null ? SESSIONS.get(deviceId) : null;
        return session != null ? progressPerMille(session.elapsedMillis(serverLevel.getGameTime()),
                session.durationSeconds()) : fallback;
    }

    public static long currentElapsedMillis(ServerPlayer owner, UUID deviceId, long fallback) {
        if (owner == null || !(owner.level() instanceof ServerLevel serverLevel) || deviceId == null) {
            return Math.max(0L, fallback);
        }
        Session session = SESSIONS.get(deviceId);
        if (session != null) {
            return session.elapsedMillis(serverLevel.getGameTime());
        }
        MP4PlaybackSavedData.Entry runtime = RUNTIME_PROGRESS.get(deviceId);
        if (runtime != null) {
            return runtime.elapsedMillis();
        }
        return MP4PlaybackSavedData.get(serverLevel).get(deviceId)
                .map(entry -> entry.elapsedMillis())
                .orElse(Math.max(0L, fallback));
    }

    public static long savedElapsedMillis(ServerPlayer owner, UUID deviceId, int queueIndex, long fallback) {
        if (owner == null || !(owner.level() instanceof ServerLevel serverLevel) || deviceId == null) {
            return Math.max(0L, fallback);
        }
        MP4PlaybackSavedData.Entry runtime = RUNTIME_PROGRESS.get(deviceId);
        if (runtime != null && runtime.queueIndex() == queueIndex) {
            return runtime.elapsedMillis();
        }
        return MP4PlaybackSavedData.get(serverLevel).elapsedMillis(deviceId, queueIndex, Math.max(0L, fallback));
    }

    public static void recordProgress(ServerPlayer owner, UUID deviceId, int queueIndex, long elapsedMillis,
            List<ItemStack> queue, int volumePerMille, boolean playing) {
        int durationSeconds = 0;
        if (queueIndex >= 0 && queueIndex < queue.size()) {
            @SuppressWarnings("null")
            ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(queue.get(queueIndex));
            durationSeconds = songInfo != null ? Math.max(0, songInfo.songTime) : 0;
        }
        recordProgress(owner, deviceId, queueIndex, elapsedMillis, durationSeconds, volumePerMille, "", playing);
    }

    public static void recordProgress(ServerPlayer owner, UUID deviceId, int queueIndex, long elapsedMillis,
            int durationSeconds, int volumePerMille, String sessionId, boolean playing) {
        if (owner == null || !(owner.level() instanceof ServerLevel serverLevel) || deviceId == null) {
            return;
        }
        recordRuntimeProgress(deviceId, queueIndex, elapsedMillis, durationSeconds, volumePerMille, sessionId, playing);
        flushRuntimeProgress(serverLevel, deviceId);
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
        SESSIONS.computeIfPresent(deviceId, (ignored, session) -> {
            Session updated = session.withVolume(volumePerMille);
            MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                ServerLevel level = server.getLevel(updated.levelKey());
                if (level != null) {
                    sendTimeline(level, updated, level.getGameTime());
                }
            }
            return updated;
        });
    }

    public static int activeQueueIndex(UUID deviceId) {
        Session session = deviceId == null ? null : SESSIONS.get(deviceId);
        return session != null ? session.queueIndex() : -1;
    }

    public static void reconcileQueueChange(ServerPlayer owner, UUID deviceId, List<ItemStack> newQueue) {
        if (owner == null || deviceId == null || !(owner.level() instanceof ServerLevel level)) {
            return;
        }
        Session session = SESSIONS.get(deviceId);
        if (session == null) {
            return;
        }
        List<ItemStack> safeNewQueue = newQueue == null ? List.of() : newQueue;
        int newIndex = indexOfSongUrl(safeNewQueue, session.rawUrl());
        long gameTime = level.getGameTime();
        long elapsedMillis = session.elapsedMillis(gameTime);
        if (newIndex >= 0) {
            if (newIndex != session.queueIndex()) {
                Session remapped = session.withQueueIndex(newIndex, gameTime);
                SESSIONS.put(deviceId, remapped);
                recordRuntimeProgress(deviceId, newIndex, elapsedMillis, remapped.durationSeconds(),
                        remapped.volumePerMille(), remapped.sessionId(), true);
                MP4DeviceStateStore.recordPlayback(level, deviceId, newIndex, elapsedMillis,
                        remapped.durationSeconds(), remapped.volumePerMille(), remapped.sessionId(), true);
                send(level, remapped, gameTime);
            }
            return;
        }
        SESSIONS.remove(deviceId);
        MISSING_SINCE.remove(deviceId);
        sendStop(level, session);
        int selectedIndex = safeNewQueue.isEmpty() ? 0 : Math.max(0, Math.min(safeNewQueue.size() - 1,
                Math.min(session.queueIndex(), safeNewQueue.size() - 1)));
        int durationSeconds = durationSeconds(safeNewQueue, selectedIndex);
        recordRuntimeProgress(deviceId, selectedIndex, 0L, durationSeconds, session.volumePerMille(), "", false);
        flushRuntimeProgress(level, deviceId);
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, ItemStack.EMPTY);
        MP4Item.State state = entry.state();
        MP4DeviceStateStore.update(level, deviceId, new MP4DeviceStateStore.DeviceEntry(
                new MP4Item.State(false, state.shuffle(), state.videoEnabled(), state.landscape(),
                        state.qualityIndex(), selectedIndex, state.queueScrollOffset(), session.volumePerMille(),
                        state.repeatMode(), state.playlistOpen(), state.lyricsEnabled(), state.subtitleMode(),
                        state.subtitleAiEnabled(), 0, state.rotationHintShown()),
                safeNewQueue, 0L, durationSeconds, ""));
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
        MP4PlaybackSyncPacket stop = stopPacketFor(deviceId);
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
        Session session = SESSIONS.get(deviceId);
        if (session == null || session.queueIndex() != queueIndex) {
            return false;
        }
        long gameTime = level.getGameTime();
        Session base = session.withVolume(volumePerMille);
        if (targetMillis >= 0L) {
            long elapsedMillis = clampElapsed(targetMillis, session.durationSeconds());
            base = base.withStartedGameTime(gameTime - Math.round(elapsedMillis / 50.0D), gameTime);
            recordRuntimeProgress(deviceId, queueIndex, elapsedMillis, session.durationSeconds(),
                    Math.max(0, Math.min(1000, volumePerMille)), session.sessionId(), true);
            flushRuntimeProgress(level, deviceId);
        }
        Session resumed = base.asPlayerSource(owner, gameTime);
        SESSIONS.put(deviceId, resumed);
        send(level, resumed, gameTime);
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
        Session session = SESSIONS.remove(deviceId);
        if (session == null) {
            return;
        }
        long gameTime = level.getGameTime();
        Session migrated = session.asItemSource(itemEntity.getId(), itemEntity.blockPosition(), gameTime);
        MP4DeviceLocationIndex.recordItemEntity(level, itemEntity, deviceId);
        persistProgress(itemEntity.getItem(), migrated, gameTime, true);
        SESSIONS.put(migrated.sourceId(), migrated);
        MISSING_SINCE.remove(migrated.sourceId());
        send(level, migrated, gameTime);
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        scanContainerEventMenu(event.getEntity(), event.getContainer());
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        scanContainerEventMenu(event.getEntity(), event.getContainer());
    }

    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level) {
            flushRuntimeProgress(level);
            MP4DeviceStateStore.flush(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            flushRuntimeProgress(level);
            MP4DeviceStateStore.flush(level);
        }
        AudioLinkIndex.clear();
        MP4DeviceLocationIndex.clear();
        MP4DeviceHolderTracker.clear();
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID playerId = player.getUUID();
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
        if (server.getTickCount() % DISCOVERY_INTERVAL_TICKS == 0) {
            for (ServerLevel level : server.getAllLevels()) {
                for (ServerPlayer player : level.players()) {
                    AudioLinkIndex.updatePlayerHeadphones(player);
                }
                discoverPlayingSources(level);
            }
        }
        if (SESSIONS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Session>> iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Session> entry = iterator.next();
            Session session = entry.getValue();
            ServerLevel serverLevel = server.getLevel(session.levelKey());
            if (serverLevel == null) {
                iterator.remove();
                continue;
            }
            long gameTime = serverLevel.getGameTime();
            Session refreshed = refreshActiveSource(server, session, gameTime);
            if (refreshed != null && !sameSource(session, refreshed)) {
                session = refreshed;
                entry.setValue(session);
                MISSING_SINCE.remove(session.sourceId());
                ServerLevel refreshedLevel = server.getLevel(session.levelKey());
                if (refreshedLevel != null) {
                    send(refreshedLevel, session, gameTime);
                }
            }
            ItemStack stack = session.stack(serverLevel);
            if (!(stack.getItem() instanceof MP4Item)
                    || !deviceState(serverLevel, stack, session.sourceId()).playing()) {
                Session relocated = relocateSession(server, session, gameTime);
                if (relocated != null) {
                    entry.setValue(relocated);
                    MISSING_SINCE.remove(session.sourceId());
                    send(serverLevel, relocated, gameTime);
                    continue;
                }
                long missingSince = MISSING_SINCE.computeIfAbsent(session.sourceId(), ignored -> gameTime);
                if (gameTime - missingSince < SOURCE_MISSING_GRACE_TICKS) {
                    continue;
                }
                persistProgress(stack, session, gameTime, false);
                iterator.remove();
                MISSING_SINCE.remove(session.sourceId());
                sendStop(serverLevel, session);
                continue;
            }
            MISSING_SINCE.remove(session.sourceId());
            long elapsed = session.elapsedMillis(gameTime);
            if (elapsed >= (long) session.durationSeconds() * 1000L) {
                iterator.remove();
                MISSING_SINCE.remove(session.sourceId());
                if (!tryAdvanceQueue(serverLevel, stack, session)) {
                    persistProgress(stack, session.resetToStart(gameTime), gameTime, false);
                    sendStop(serverLevel, session);
                }
                continue;
            }
            persistProgress(stack, session, gameTime, true);
            session.recordAudit(serverLevel, gameTime);
            if (gameTime - session.lastSyncGameTime() >= SYNC_INTERVAL_TICKS) {
                Session synced = session.withLastSyncGameTime(gameTime);
                entry.setValue(synced);
                if (gameTime - session.lastFullSyncGameTime() >= FULL_SYNC_INTERVAL_TICKS) {
                    Session fullSynced = synced.withLastFullSyncGameTime(gameTime);
                    send(serverLevel, fullSynced, gameTime);
                    entry.setValue(fullSynced);
                } else {
                    sendTimeline(serverLevel, synced, gameTime);
                }
            }
        }
    }

    private static void discoverPlayingSources(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            scanPlayerInventory(level, player);
            AABB range = player.getBoundingBox().inflate(SYNC_RANGE);
            for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, range)) {
                ItemStack stack = itemEntity.getItem();
                UUID itemDeviceId = MP4Item.readDeviceId(stack);
                if (stack.getItem() instanceof MP4Item && deviceState(level, stack, itemDeviceId).playing()) {
                    UUID deviceId = itemDeviceId;
                    Session existing = SESSIONS.get(deviceId);
                    long gameTime = level.getGameTime();
                    if (existing != null && (existing.sourceType() != MP4PlaybackSyncPacket.SOURCE_ITEM
                            || existing.sourceEntityId() != itemEntity.getId())) {
                        Session migrated = existing.asItemSource(itemEntity.getId(), itemEntity.blockPosition(),
                                gameTime);
                        MP4DeviceLocationIndex.recordItemEntity(level, itemEntity, deviceId);
                        SESSIONS.put(deviceId, migrated);
                        MISSING_SINCE.remove(deviceId);
                        send(level, migrated, gameTime);
                    } else if (existing == null && !hasSessionForItemEntity(itemEntity)) {
                        startDiscovered(level, stack, deviceId, deviceId,
                                MP4PlaybackSyncPacket.SOURCE_ITEM, itemEntity.getId(), itemEntity.blockPosition(), -1);
                    }
                }
            }
            scanOpenMenuSlots(level, player);
        }
    }

    private static void scanPlayerInventory(ServerLevel level, ServerPlayer player) {
        scanPlayerHeldStack(level, player,
                player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            scanPlayerHeldStack(level, player, inventory.getItem(slot));
        }
    }

    private static void scanPlayerHeldStack(ServerLevel level, ServerPlayer player, ItemStack stack) {
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (!(stack.getItem() instanceof MP4Item) || !deviceState(level, stack, deviceId).playing()) {
            return;
        }
        deviceId = MP4DeviceIdentity.getOrCreateUnique(level, player, stack);
        if (deviceId == null) {
            return;
        }
        Session existing = SESSIONS.get(deviceId);
        long gameTime = level.getGameTime();
        MP4DeviceLocationIndex.recordPlayer(level, player, deviceId);
        if (existing != null && existing.sourceType() != MP4PlaybackSyncPacket.SOURCE_PLAYER) {
            Session migrated = existing.asPlayerSource(player, gameTime);
            SESSIONS.put(deviceId, migrated);
            MISSING_SINCE.remove(deviceId);
            send(level, migrated, gameTime);
        } else if (existing == null) {
            startDiscovered(level, stack, player.getUUID(), deviceId, MP4PlaybackSyncPacket.SOURCE_PLAYER,
                    player.getId(), player.blockPosition(), -1);
        }
    }

    private static boolean hasSessionForItemEntity(ItemEntity itemEntity) {
        int entityId = itemEntity.getId();
        UUID deviceId = MP4Item.readDeviceId(itemEntity.getItem());
        for (Session session : SESSIONS.values()) {
            if (session.sourceType() == MP4PlaybackSyncPacket.SOURCE_ITEM
                    && (session.sourceEntityId() == entityId || session.sourceId().equals(deviceId))) {
                return true;
            }
        }
        return false;
    }

    private static void scanOpenMenuSlots(ServerLevel level, ServerPlayer player) {
        if (player.containerMenu == null) {
            return;
        }
        scanMenuSlots(level, player, player.containerMenu);
    }

    private static void scanContainerEventMenu(Player player, AbstractContainerMenu menu) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(serverPlayer.level() instanceof ServerLevel level)
                || menu == null) {
            return;
        }
        scanMenuSlots(level, serverPlayer, menu);
    }

    private static void scanMenuSlots(ServerLevel level, ServerPlayer player, AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) {
                continue;
            }
            scanMenuSlot(level, player, slot);
        }
    }

    private static void scanMenuSlot(ServerLevel level, ServerPlayer player, Slot slot) {
        ItemStack stack = slot.getItem();
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (!(stack.getItem() instanceof MP4Item) || !deviceState(level, stack, deviceId).playing()) {
            return;
        }
        if (deviceId == null) {
            return;
        }
        Container container = slot.container;
        long gameTime = level.getGameTime();
        Session existing = SESSIONS.get(deviceId);
        if (container instanceof BlockEntity blockEntity) {
            BlockPos pos = blockEntity.getBlockPos();
            MP4DeviceLocationIndex.recordBlockContainer(level, pos, slot.index, deviceId);
            if (existing != null && (existing.sourceType() != MP4PlaybackSyncPacket.SOURCE_BLOCK
                    || !existing.sourcePos().equals(pos) || existing.containerSlot() != slot.index)) {
                Session migrated = existing.asBlockSource(pos.immutable(), slot.index, gameTime);
                SESSIONS.put(deviceId, migrated);
                MISSING_SINCE.remove(deviceId);
                send(level, migrated, gameTime);
            } else if (existing == null) {
                startDiscovered(level, stack, deviceId, deviceId, MP4PlaybackSyncPacket.SOURCE_BLOCK, -1,
                        pos.immutable(), slot.index);
            }
            return;
        }
        Entity entity = containerEntity(container);
        if (entity != null) {
            MP4DeviceLocationIndex.recordContainerEntity(level, entity, slot.index, deviceId);
            if (existing != null && (existing.sourceType() != MP4PlaybackSyncPacket.SOURCE_CONTAINER_ENTITY
                    || existing.sourceEntityId() != entity.getId() || existing.containerSlot() != slot.index)) {
                Session migrated = existing.asContainerEntitySource(level, entity, slot.index, gameTime);
                SESSIONS.put(deviceId, migrated);
                MISSING_SINCE.remove(deviceId);
                send(level, migrated, gameTime);
            } else if (existing == null) {
                startDiscovered(level, stack, deviceId, deviceId, MP4PlaybackSyncPacket.SOURCE_CONTAINER_ENTITY,
                        entity.getId(), entity.blockPosition(), slot.index);
            }
            return;
        }
        if (existing == null) {
            startDiscovered(level, stack, player.getUUID(), deviceId, MP4PlaybackSyncPacket.SOURCE_PLAYER,
                    player.getId(), player.blockPosition(), -1);
        }
    }

    private static Entity containerEntity(Container container) {
        if (container instanceof Entity entity) {
            return entity;
        }
        if (container instanceof ContainerEntity containerEntity) {
            return (Entity) containerEntity;
        }
        return null;
    }

    private static void startDiscovered(ServerLevel level, ItemStack stack, UUID ownerId, UUID sourceId, int sourceType,
            int sourceEntityId, BlockPos sourcePos, int containerSlot) {
        if (SESSIONS.containsKey(sourceId)) {
            return;
        }
        MP4DeviceStateStore.DeviceEntry deviceEntry = MP4DeviceStateStore.getOrCreate(level, sourceId, stack);
        List<ItemStack> queue = queueForDevice(deviceEntry, stack);
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
        long targetMillis = targetMillis(level, sourceId, stack, state, index);
        MusicPlayResolverManager.resolve(original.clone()).thenAcceptAsync(resolved -> {
            if (!(stack.getItem() instanceof MP4Item) || !deviceState(level, stack, sourceId).playing()) {
                return;
            }
            List<ItemStack> currentQueue = queueForDevice(MP4DeviceStateStore.getOrCreate(level, sourceId, stack),
                    stack);
            if (index < 0 || index >= currentQueue.size()) {
                return;
            }
            @SuppressWarnings("null")
            ItemMusicCD.SongInfo current = ItemMusicCD.getSongInfo(currentQueue.get(index));
            if (current == null || !Objects.equals(current.songUrl, original.songUrl)) {
                return;
            }
            ServerPlayer currentOwner = level.getServer().getPlayerList().getPlayer(ownerId);
            if (!isPlaybackAllowed(level, original.songUrl, currentOwner)) {
                return;
            }
            String rawUrl = original.songUrl != null ? original.songUrl : "";
            String playUrl = resolved.songUrl != null && !resolved.songUrl.isBlank() ? resolved.songUrl : rawUrl;
            if (BiliApiClient.isStoredVideoSelection(rawUrl)) {
                playUrl = rawUrl;
            }
            if (playUrl.isBlank()) {
                return;
            }
            String songName = resolved.songName != null && !resolved.songName.isBlank() ? resolved.songName
                    : original.songName;
            int durationSeconds = Math.max(1, resolved.songTime > 0 ? resolved.songTime : original.songTime);
            long elapsedMillis = clampElapsed(targetMillis, durationSeconds);
            UUID deviceId = MP4Item.readDeviceId(stack);
            if (deviceId == null) {
                return;
            }
            String sessionId = deviceId + "-mp4-" + System.nanoTime();
            String syncedPlayUrl = PlaybackSync.withSync(playUrl, sessionId, elapsedMillis, durationSeconds * 1000L);
            long gameTime = level.getGameTime();
            Session session = new Session(level.dimension(), ownerId, deviceId, sourceType, sourceEntityId, sourcePos,
                    containerSlot, index, syncedPlayUrl, rawUrl, songName == null ? "" : songName, durationSeconds,
                    state.volumePerMille(), sessionId, gameTime - Math.round(elapsedMillis / 50.0D), gameTime,
                    gameTime);
            SESSIONS.put(deviceId, session);
            MISSING_SINCE.remove(deviceId);
            session.recordAudit(level, gameTime);
            persistProgress(stack, session, gameTime, true);
            send(level, session, gameTime);
        }, level.getServer()).exceptionally(error -> {
            LOGGER.error("MP4 自动接管播放源解析失败: {}", original.songName, error);
            return null;
        });
    }

    private static boolean tryAdvanceQueue(ServerLevel level, ItemStack stack, Session session) {
        MP4DeviceStateStore.DeviceEntry deviceEntry = MP4DeviceStateStore.getOrCreate(level, session.sourceId(), stack);
        List<ItemStack> queue = queueForDevice(deviceEntry, stack);
        if (queue.isEmpty()) {
            return false;
        }
        MP4Item.State state = deviceEntry.state();
        int nextIndex = session.queueIndex();
        if (state.repeatMode() == 1) {
            nextIndex = Math.max(0, Math.min(queue.size() - 1, session.queueIndex()));
        } else if (session.queueIndex() < queue.size() - 1) {
            nextIndex = session.queueIndex() + 1;
        } else if (state.repeatMode() == 2) {
            nextIndex = 0;
        } else {
            return false;
        }
        MP4DeviceStateStore.update(level, session.sourceId(), new MP4DeviceStateStore.DeviceEntry(
                new MP4Item.State(true, state.shuffle(), state.videoEnabled(), state.landscape(),
                        state.qualityIndex(), nextIndex, state.queueScrollOffset(), state.volumePerMille(),
                        state.repeatMode(),
                        state.playlistOpen(), state.lyricsEnabled(), state.subtitleMode(), state.subtitleAiEnabled(), 0,
                        state.rotationHintShown()),
                queue, 0L, 0, ""));
        recordRuntimeProgress(session.sourceId(), nextIndex, 0L, 0, session.volumePerMille(), "", true);
        flushRuntimeProgress(level, session.sourceId());
        session.markContainerChanged();
        startDiscovered(level, stack, session.ownerId(), session.sourceId(), session.sourceType(),
                session.sourceEntityId(), session.sourcePos(), session.containerSlot());
        return true;
    }

    private static void send(ServerLevel level, Session session, long gameTime) {
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(session.ownerId());
        if (!isPlaybackAllowed(level, session.rawUrl(), owner)) {
            SESSIONS.remove(session.sourceId());
            sendStop(level, session);
            return;
        }
        SourcePosition pos = session.sourcePosition(level);
        MP4PlaybackSyncPacket packet = new MP4PlaybackSyncPacket(
                session.ownerId(),
                session.sourceId(),
                session.sourceType(),
                pos.entityId(),
                pos.x(),
                pos.y(),
                pos.z(),
                true,
                session.queueIndex(),
                session.playUrl(),
                session.rawUrl(),
                session.songName(),
                session.durationSeconds(),
                session.volumePerMille(),
                session.sessionId(),
                session.elapsedMillis(gameTime),
                false);
        MP4PlaybackSyncPacket headphonePacket = new MP4PlaybackSyncPacket(
                packet.ownerId(), packet.sourceId(), packet.sourceType(), packet.sourceEntityId(), packet.sourceX(),
                packet.sourceY(), packet.sourceZ(), packet.playing(), packet.queueIndex(), packet.playUrl(),
                packet.rawUrl(), packet.songName(), packet.durationSeconds(), packet.volumePerMille(),
                packet.sessionId(), packet.elapsedMillis(), true);
        owner = level.getServer().getPlayerList().getPlayer(session.ownerId());
        boolean headphoneLinked = AudioLinkIndex.hasHeadphoneLinkedToMp4(session.sourceId());
        boolean routedToHeadphones = sendToHeadphoneListeners(level, session, pos, headphonePacket, gameTime);
        if (!headphoneLinked) {
            PacketDistributor.sendToPlayersNear(level, null, pos.x(), pos.y(), pos.z(), SYNC_RANGE, packet);
        }
        LOGGER.trace(
                "MP4 播放同步下发: owner={} source={} type={} song='{}' session={} elapsed={}ms host={} ownerOnline={} headphoneLinked={} routedToHeadphones={}",
                session.ownerId(), session.sourceId(), session.sourceType(), session.songName(), session.sessionId(),
                session.elapsedMillis(gameTime), safeHost(session.playUrl()), owner != null && owner.level() == level,
                headphoneLinked, routedToHeadphones);
    }

    private static void sendTimeline(ServerLevel level, Session session, long gameTime) {
        SourcePosition pos = session.sourcePosition(level);
        MP4PlaybackTimelinePacket packet = new MP4PlaybackTimelinePacket(session.sourceId(), session.sessionId(),
                session.elapsedMillis(gameTime), session.volumePerMille(), false);
        MP4PlaybackTimelinePacket headphonePacket = new MP4PlaybackTimelinePacket(session.sourceId(),
                session.sessionId(), session.elapsedMillis(gameTime), session.volumePerMille(), true);
        boolean headphoneLinked = AudioLinkIndex.hasHeadphoneLinkedToMp4(session.sourceId());
        sendTimelineToHeadphoneListeners(level, session, pos, headphonePacket);
        if (!headphoneLinked) {
            PacketDistributor.sendToPlayersNear(level, null, pos.x(), pos.y(), pos.z(), SYNC_RANGE, packet);
        }
    }

    public static void stopExternalPlaybackForLinkedHeadphones(ServerPlayer actor, UUID deviceId) {
        if (actor == null || deviceId == null || !(actor.level() instanceof ServerLevel level)) {
            return;
        }
        Session session = SESSIONS.get(deviceId);
        if (session == null) {
            return;
        }
        SourcePosition pos = session.sourcePosition(level);
        MP4PlaybackSyncPacket stop = MP4PlaybackSyncPacket.stop(session.ownerId(), session.sourceId(),
                session.queueIndex());
        double rangeSquared = SYNC_RANGE * SYNC_RANGE;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos.x(), pos.y(), pos.z()) > rangeSquared) {
                continue;
            }
            if (isWearingHeadphonesLinkedTo(player, session.sourceId())) {
                continue;
            }
            PacketDistributor.sendToPlayer(player, stop);
        }
    }

    private static void sendStop(ServerLevel level, Session session) {
        SourcePosition pos = session.sourcePosition(level);
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(session.ownerId());
        if (owner != null && owner.level() == level) {
            PacketDistributor.sendToPlayer(owner,
                    MP4PlaybackSyncPacket.stop(session.ownerId(), session.sourceId(), session.queueIndex()));
        }
        PacketDistributor.sendToPlayersNear(level, null, pos.x(), pos.y(), pos.z(), SYNC_RANGE,
                MP4PlaybackSyncPacket.stop(session.ownerId(), session.sourceId(), session.queueIndex()));
    }

    private static MP4PlaybackSyncPacket stopPacketFor(UUID deviceId) {
        Session session = SESSIONS.get(deviceId);
        if (session != null) {
            return MP4PlaybackSyncPacket.stop(session.ownerId(), session.sourceId(), session.queueIndex());
        }
        return MP4PlaybackSyncPacket.stop(deviceId, deviceId, 0);
    }

    private static boolean sendToHeadphoneListeners(ServerLevel level, Session session, SourcePosition pos,
            MP4PlaybackSyncPacket packet, long gameTime) {
        boolean routed = false;
        MP4PlaybackSyncPacket stop = MP4PlaybackSyncPacket.stop(session.ownerId(), session.sourceId(),
                session.queueIndex());
        for (UUID playerId : AudioLinkIndex.headphonePlayersForMp4(session.sourceId())) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
            if (player == null || player.level() != level) {
                AudioLinkIndex.removeHeadphonePlayer(playerId);
                continue;
            }
            boolean linked = isWearingHeadphonesLinkedTo(player, session.sourceId());
            double distanceSquared = player.distanceToSqr(pos.x(), pos.y(), pos.z());
            if (linked && distanceSquared <= AudioLinkData.MP4_HEADPHONE_RANGE_SQUARED) {
                AudioLinkIndex.updatePlayerHeadphones(player);
                PacketDistributor.sendToPlayer(player, packet);
                routed = true;
            } else {
                if (linked && distanceSquared > AudioLinkData.MP4_HEADPHONE_RANGE_SQUARED) {
                    clearLinkedMp4(player);
                    player.sendSystemMessage(Component.translatable(
                            "message.net_music_can_play_bili.headphones.mp4_out_of_range"));
                }
                PacketDistributor.sendToPlayer(player, stop);
            }
        }
        return routed;
    }

    private static void sendTimelineToHeadphoneListeners(ServerLevel level, Session session, SourcePosition pos,
            MP4PlaybackTimelinePacket packet) {
        MP4PlaybackSyncPacket stop = MP4PlaybackSyncPacket.stop(session.ownerId(), session.sourceId(),
                session.queueIndex());
        for (UUID playerId : AudioLinkIndex.headphonePlayersForMp4(session.sourceId())) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
            if (player == null || player.level() != level) {
                AudioLinkIndex.removeHeadphonePlayer(playerId);
                continue;
            }
            boolean linked = isWearingHeadphonesLinkedTo(player, session.sourceId());
            double distanceSquared = player.distanceToSqr(pos.x(), pos.y(), pos.z());
            if (linked && distanceSquared <= AudioLinkData.MP4_HEADPHONE_RANGE_SQUARED) {
                AudioLinkIndex.updatePlayerHeadphones(player);
                PacketDistributor.sendToPlayer(player, packet);
            } else {
                if (linked && distanceSquared > AudioLinkData.MP4_HEADPHONE_RANGE_SQUARED) {
                    clearLinkedMp4(player);
                    player.sendSystemMessage(Component.translatable(
                            "message.net_music_can_play_bili.headphones.mp4_out_of_range"));
                }
                PacketDistributor.sendToPlayer(player, stop);
            }
        }
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

    private static boolean isWearingHeadphonesLinkedTo(ServerPlayer player, UUID deviceId) {
        if (player == null || deviceId == null) {
            return false;
        }
        ItemStack head = EquippedMediaItems.firstHeadphones(player);
        return HeadphoneAbility.has(head)
                && deviceId.equals(AudioLinkData.readHeadphoneMp4(head));
    }

    private static void clearLinkedMp4(ServerPlayer player) {
        ItemStack head = EquippedMediaItems.firstHeadphones(player);
        if (HeadphoneAbility.has(head)) {
            AudioLinkData.clearHeadphoneMp4(head);
            player.getInventory().setChanged();
            AudioLinkIndex.removeHeadphonePlayer(player);
            AudioLinkIndex.updatePlayerHeadphones(player);
        }
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
        if (!HolographicGlassesItem.boundToMp4(stack, deviceId)) {
            return false;
        }
        HolographicGlassesItem.clearBoundMp4(stack, deviceId);
        return true;
    }

    private static void persistProgress(ServerPlayer owner, Session session, long gameTime, boolean playing) {
        ItemStack stack = MP4Item.findPlayableInInventory(owner);
        long elapsedMillis = session.elapsedMillis(gameTime);
        ServerLevel level = (ServerLevel) owner.level();
        recordRuntimeProgress(session.sourceId(), session.queueIndex(), elapsedMillis, session.durationSeconds(),
                session.volumePerMille(), session.sessionId(), playing);
        MP4DeviceStateStore.recordPlayback(level, session.sourceId(), session.queueIndex(), elapsedMillis,
                session.durationSeconds(), session.volumePerMille(), session.sessionId(), playing);
        if (!playing) {
            flushRuntimeProgress(level, session.sourceId());
        }
        if (playing) {
            return;
        }
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        MP4Item.State state = MP4DeviceStateStore.getOrCreate(level, session.sourceId(), stack).state();
        int progress = progressPerMille(elapsedMillis, session.durationSeconds());
        MP4DeviceStateStore.updateState(level, session.sourceId(), new MP4Item.State(
                playing,
                state.shuffle(),
                state.videoEnabled(),
                state.landscape(),
                state.qualityIndex(),
                session.queueIndex(),
                state.queueScrollOffset(),
                session.volumePerMille(),
                state.repeatMode(),
                state.playlistOpen(),
                state.lyricsEnabled(),
                state.subtitleMode(),
                state.subtitleAiEnabled(),
                progress,
                state.rotationHintShown()));
    }

    private static void persistProgress(ItemStack stack, Session session, long gameTime, boolean playing) {
        long elapsedMillis = session.elapsedMillis(gameTime);
        ServerLevel level = session.currentLevel();
        recordRuntimeProgress(session.sourceId(), session.queueIndex(), elapsedMillis, session.durationSeconds(),
                session.volumePerMille(), session.sessionId(), playing);
        if (level != null) {
            MP4DeviceStateStore.recordPlayback(level, session.sourceId(), session.queueIndex(), elapsedMillis,
                    session.durationSeconds(), session.volumePerMille(), session.sessionId(), playing);
        }
        if (level != null && !playing) {
            flushRuntimeProgress(level, session.sourceId());
        }
        if (playing) {
            return;
        }
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        MP4Item.State state = MP4DeviceStateStore.getOrCreate(level, session.sourceId(), stack).state();
        int progress = progressPerMille(elapsedMillis, session.durationSeconds());
        MP4DeviceStateStore.updateState(level, session.sourceId(),
                new MP4Item.State(playing, state.shuffle(), state.videoEnabled(), state.landscape(),
                        state.qualityIndex(), session.queueIndex(), state.queueScrollOffset(), session.volumePerMille(),
                        state.repeatMode(), state.playlistOpen(), state.lyricsEnabled(), state.subtitleMode(),
                        state.subtitleAiEnabled(), progress, state.rotationHintShown()));
        session.markContainerChanged();
    }

    private static long targetMillis(ServerLevel level, UUID deviceId, ItemStack stack, MP4Item.State state,
            int queueIndex) {
        List<ItemStack> queue = queueForDevice(MP4DeviceStateStore.getOrCreate(level, deviceId, stack), stack);
        if (queueIndex < 0 || queueIndex >= queue.size()) {
            return 0L;
        }
        @SuppressWarnings("null")
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(queue.get(queueIndex));
        int durationSeconds = songInfo != null ? Math.max(0, songInfo.songTime) : 0;
        if (durationSeconds <= 0) {
            return 0L;
        }
        long durationMillis = durationSeconds * 1000L;
        long fallbackMillis = Math.round(state.progressPerMille() / 1000.0D * durationMillis);
        MP4PlaybackSavedData.Entry runtime = RUNTIME_PROGRESS.get(deviceId);
        long elapsedMillis = runtime != null && runtime.queueIndex() == queueIndex
                ? runtime.elapsedMillis()
                : MP4PlaybackSavedData.get(level).elapsedMillis(deviceId, queueIndex, fallbackMillis);
        return Math.max(0L, Math.min(durationMillis - 50L, elapsedMillis));
    }

    private static void recordRuntimeProgress(UUID deviceId, int queueIndex, long elapsedMillis, int durationSeconds,
            int volumePerMille, String sessionId, boolean playing) {
        if (deviceId == null) {
            return;
        }
        RUNTIME_PROGRESS.put(deviceId, new MP4PlaybackSavedData.Entry(queueIndex, elapsedMillis, durationSeconds,
                volumePerMille, sessionId == null ? "" : sessionId, playing).normalized());
    }

    private static void flushRuntimeProgress(ServerLevel level) {
        if (level == null || RUNTIME_PROGRESS.isEmpty()) {
            return;
        }
        MP4PlaybackSavedData data = MP4PlaybackSavedData.get(level);
        RUNTIME_PROGRESS.forEach(data::put);
    }

    private static void flushRuntimeProgress(ServerLevel level, UUID deviceId) {
        if (level == null || deviceId == null) {
            return;
        }
        MP4PlaybackSavedData.Entry entry = RUNTIME_PROGRESS.get(deviceId);
        if (entry != null) {
            MP4PlaybackSavedData.get(level).put(deviceId, entry);
        }
    }

    private static Session relocateSession(MinecraftServer server, Session session, long gameTime) {
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                ItemStack stack = MP4Item.findByDeviceId(player, session.sourceId());
                if (stack.getItem() instanceof MP4Item && deviceState(level, stack, session.sourceId()).playing()) {
                    MP4DeviceLocationIndex.recordPlayer(level, player, session.sourceId());
                    return session.asPlayerSource(player, gameTime);
                }
                AABB range = player.getBoundingBox().inflate(SYNC_RANGE);
                for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, range)) {
                    ItemStack itemStack = itemEntity.getItem();
                    if (itemStack.getItem() instanceof MP4Item
                            && session.sourceId().equals(MP4Item.readDeviceId(itemStack))
                            && deviceState(level, itemStack, session.sourceId()).playing()) {
                        MP4DeviceLocationIndex.recordItemEntity(level, itemEntity, session.sourceId());
                        return session.asItemSource(itemEntity.getId(), itemEntity.blockPosition(), gameTime);
                    }
                }
                MP4DeviceLocationIndex.ResolvedLocation indexed = MP4DeviceLocationIndex.resolve(level,
                        session.sourceId()).orElse(null);
                if (indexed != null && indexed.stack().getItem() instanceof MP4Item
                        && deviceState(level, indexed.stack(), session.sourceId()).playing()) {
                    return session.fromResolved(indexed, gameTime);
                }
                Session menuSession = relocateToOpenMenu(level, player, session, gameTime);
                if (menuSession != null) {
                    return menuSession;
                }
            }
        }
        return null;
    }

    private static Session refreshActiveSource(MinecraftServer server, Session session, long gameTime) {
        for (ServerLevel level : server.getAllLevels()) {
            MP4DeviceLocationIndex.ResolvedLocation indexed = MP4DeviceLocationIndex.resolve(level,
                    session.sourceId()).orElse(null);
            if (indexed != null && indexed.stack().getItem() instanceof MP4Item
                    && deviceState(level, indexed.stack(), session.sourceId()).playing()) {
                return session.fromResolved(indexed, gameTime);
            }
            for (ServerPlayer player : level.players()) {
                Session menuSession = relocateToOpenMenu(level, player, session, gameTime);
                if (menuSession != null) {
                    return menuSession;
                }
            }
        }
        return null;
    }

    private static boolean sameSource(Session left, Session right) {
        return left != null && right != null
                && left.levelKey().equals(right.levelKey())
                && left.sourceType() == right.sourceType()
                && left.sourceEntityId() == right.sourceEntityId()
                && left.sourcePos().equals(right.sourcePos())
                && left.containerSlot() == right.containerSlot()
                && Objects.equals(left.ownerId(), right.ownerId());
    }

    private static Session relocateToOpenMenu(ServerLevel level, ServerPlayer player, Session session, long gameTime) {
        if (player.containerMenu == null) {
            return null;
        }
        for (Slot slot : player.containerMenu.slots) {
            if (slot.container == player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!(stack.getItem() instanceof MP4Item) || !session.sourceId().equals(MP4Item.readDeviceId(stack))
                    || !deviceState(level, stack, session.sourceId()).playing()) {
                continue;
            }
            Container container = slot.container;
            if (container instanceof BlockEntity blockEntity) {
                MP4DeviceLocationIndex.recordBlockContainer(level, blockEntity.getBlockPos(), slot.index,
                        session.sourceId());
                return session.asBlockSource(blockEntity.getBlockPos().immutable(), slot.index, gameTime);
            }
            Entity entity = containerEntity(container);
            if (entity != null) {
                MP4DeviceLocationIndex.recordContainerEntity(level, entity, slot.index, session.sourceId());
                return session.asContainerEntitySource(level, entity, slot.index, gameTime);
            }
            return session.asPlayerSource(player, gameTime);
        }
        return null;
    }

    private static long clampElapsed(long elapsedMillis, int durationSeconds) {
        long max = Math.max(0L, (long) Math.max(1, durationSeconds) * 1000L - 50L);
        return Math.max(0L, Math.min(max, elapsedMillis));
    }

    private static String safeHost(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        try {
            String stripped = PlaybackSync.strip(value);
            String host = URI.create(stripped != null ? stripped : value).getHost();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static int progressPerMille(long elapsedMillis, int durationSeconds) {
        long durationMillis = Math.max(1L, (long) Math.max(1, durationSeconds) * 1000L);
        long elapsed = Math.max(0L, Math.min(durationMillis, elapsedMillis));
        return Math.max(0, Math.min(1000, (int) Math.round(elapsed * 1000.0D / durationMillis)));
    }

    private static int durationSeconds(List<ItemStack> queue, int queueIndex) {
        if (queueIndex < 0 || queueIndex >= queue.size()) {
            return 0;
        }
        @SuppressWarnings("null")
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(queue.get(queueIndex));
        return songInfo != null ? Math.max(0, songInfo.songTime) : 0;
    }

    private static int indexOfSongUrl(List<ItemStack> queue, String songUrl) {
        if (songUrl == null || songUrl.isBlank()) {
            return -1;
        }
        for (int index = 0; index < queue.size(); index++) {
            @SuppressWarnings("null")
            ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(queue.get(index));
            if (songInfo != null && Objects.equals(songUrl, songInfo.songUrl)) {
                return index;
            }
        }
        return -1;
    }

    private static MP4Item.State deviceState(ServerLevel level, ItemStack stack, UUID deviceId) {
        if (deviceId == null) {
            return MP4Item.State.DEFAULT;
        }
        return MP4DeviceStateStore.getOrCreate(level, deviceId, stack).state();
    }

    private static List<ItemStack> queueForDevice(MP4DeviceStateStore.DeviceEntry entry, ItemStack stack) {
        List<ItemStack> itemQueue = MP4Item.readQueue(stack);
        if (!itemQueue.isEmpty()) {
            return itemQueue;
        }
        return entry != null ? entry.queue() : List.of();
    }

    private record Session(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> levelKey,
            UUID ownerId, UUID sourceId, int sourceType, int sourceEntityId, BlockPos sourcePos, int containerSlot,
            int queueIndex, String playUrl, String rawUrl, String songName, int durationSeconds, int volumePerMille,
            String sessionId, long startedGameTime,
            long lastSyncGameTime, long lastFullSyncGameTime) {
        long elapsedMillis(long gameTime) {
            return Math.min((long) durationSeconds * 1000L,
                    Math.max(0L, (gameTime - startedGameTime) * 50L));
        }

        Session withVolume(int newVolumePerMille) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds,
                    Math.max(0, Math.min(1000, newVolumePerMille)), sessionId, startedGameTime, lastSyncGameTime,
                    lastFullSyncGameTime);
        }

        Session withLastSyncGameTime(long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, sessionId, startedGameTime,
                    gameTime, lastFullSyncGameTime);
        }

        Session withLastFullSyncGameTime(long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, sessionId, startedGameTime,
                    lastSyncGameTime, gameTime);
        }

        Session withStartedGameTime(long newStartedGameTime, long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, sessionId,
                    newStartedGameTime, gameTime, gameTime);
        }

        Session withQueueIndex(int newQueueIndex, long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                Math.max(0, newQueueIndex), playUrl, rawUrl, songName, durationSeconds, volumePerMille,
                sessionId, startedGameTime, gameTime, gameTime);
        }

        Session resetToStart(long gameTime) {
            return new Session(levelKey, ownerId, sourceId, sourceType, sourceEntityId, sourcePos, containerSlot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, sessionId,
                    gameTime, gameTime, gameTime);
        }

        Session asItemSource(int itemEntityId, BlockPos itemPos, long gameTime) {
            return new Session(levelKey, ownerId, sourceId, MP4PlaybackSyncPacket.SOURCE_ITEM, itemEntityId,
                    itemPos.immutable(), -1, queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille,
                    sessionId, startedGameTime, gameTime, gameTime);
        }

        Session asPlayerSource(ServerPlayer player, long gameTime) {
            return new Session(player.level().dimension(), player.getUUID(), sourceId,
                    MP4PlaybackSyncPacket.SOURCE_PLAYER,
                    player.getId(), player.blockPosition(), -1, queueIndex, playUrl, rawUrl, songName, durationSeconds,
                    volumePerMille, sessionId, startedGameTime, gameTime, gameTime);
        }

        Session asBlockSource(BlockPos pos, int slot, long gameTime) {
            return new Session(levelKey, ownerId, sourceId, MP4PlaybackSyncPacket.SOURCE_BLOCK, -1, pos.immutable(),
                    slot,
                    queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, sessionId, startedGameTime,
                    gameTime, gameTime);
        }

        Session asContainerEntitySource(ServerLevel level, Entity entity, int slot, long gameTime) {
            return new Session(level.dimension(), ownerId, sourceId, MP4PlaybackSyncPacket.SOURCE_CONTAINER_ENTITY,
                    entity.getId(), entity.blockPosition(), slot, queueIndex, playUrl, rawUrl, songName,
                    durationSeconds,
                    volumePerMille, sessionId, startedGameTime, gameTime, gameTime);
        }

        Session fromResolved(MP4DeviceLocationIndex.ResolvedLocation resolved, long gameTime) {
            UUID resolvedOwnerId = resolved.sourceType() == MP4PlaybackSyncPacket.SOURCE_PLAYER
                    && resolved.ownerId() != null ? resolved.ownerId() : ownerId;
            return new Session(levelKey, resolvedOwnerId, sourceId, resolved.sourceType(), resolved.sourceEntityId(),
                    resolved.sourcePos().immutable(),
                    resolved.containerSlot(), queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille,
                    sessionId, startedGameTime, gameTime, gameTime);
        }

        void markContainerChanged() {
            ServerLevel level = currentLevel();
            if (level == null) {
                return;
            }
            if (sourceType == MP4PlaybackSyncPacket.SOURCE_BLOCK
                    && level.getBlockEntity(sourcePos) instanceof Container container) {
                container.setChanged();
                return;
            }
            if (sourceType == MP4PlaybackSyncPacket.SOURCE_CONTAINER_ENTITY
                    && level.getEntity(sourceEntityId) instanceof Container container) {
                container.setChanged();
            }
        }

        private ServerLevel currentLevel() {
            MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            return server != null ? server.getLevel(levelKey) : null;
        }

        ItemStack stack(ServerLevel level) {
            MP4DeviceLocationIndex.ResolvedLocation indexed = MP4DeviceLocationIndex.resolve(level, sourceId)
                    .orElse(null);
            if (indexed != null && indexed.stack().getItem() instanceof MP4Item) {
                return indexed.stack();
            }
            if (sourceType == MP4PlaybackSyncPacket.SOURCE_ITEM) {
                if (level.getEntity(sourceEntityId) instanceof ItemEntity item) {
                    MP4DeviceLocationIndex.recordItemEntity(level, item, sourceId);
                    return item.getItem();
                }
            }
            if (sourceType == MP4PlaybackSyncPacket.SOURCE_BLOCK
                    && level.getBlockEntity(sourcePos) instanceof Container container
                    && containerSlot >= 0 && containerSlot < container.getContainerSize()) {
                MP4DeviceLocationIndex.recordBlockContainer(level, sourcePos, containerSlot, sourceId);
                return container.getItem(containerSlot);
            }
            if (sourceType == MP4PlaybackSyncPacket.SOURCE_CONTAINER_ENTITY
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
                }
                return ItemStack.EMPTY;
            }
            return MP4Item.findByDeviceId(player, sourceId);
        }

        SourcePosition sourcePosition(ServerLevel level) {
            if (sourceType == MP4PlaybackSyncPacket.SOURCE_ITEM) {
                if (level.getEntity(sourceEntityId) instanceof ItemEntity item) {
                    MP4DeviceLocationIndex.recordItemEntity(level, item, sourceId);
                    return new SourcePosition(item.getId(), item.getX(), item.getY() + 0.25D, item.getZ());
                }
            }
            if (sourceType == MP4PlaybackSyncPacket.SOURCE_BLOCK) {
                return new SourcePosition(-1, sourcePos.getX() + 0.5D, sourcePos.getY() + 0.5D,
                        sourcePos.getZ() + 0.5D);
            }
            if (sourceType == MP4PlaybackSyncPacket.SOURCE_CONTAINER_ENTITY
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

    private record SourcePosition(int entityId, double x, double y, double z) {
    }
}
