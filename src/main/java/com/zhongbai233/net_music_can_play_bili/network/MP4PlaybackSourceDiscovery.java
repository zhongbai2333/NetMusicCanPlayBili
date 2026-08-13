package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Discovers active MP4 sources and relocates sessions when their physical holder changes. */
final class MP4PlaybackSourceDiscovery {
    private final MP4PlaybackSourceSessionRegistry<MP4PlaybackSyncManager.Session> sessions;
    private final double syncRange;
    private final DiscoveredSourceStarter starter;
    private final MigratedSessionPublisher publisher;

    MP4PlaybackSourceDiscovery(
            MP4PlaybackSourceSessionRegistry<MP4PlaybackSyncManager.Session> sessions,
            double syncRange,
            DiscoveredSourceStarter starter,
            MigratedSessionPublisher publisher) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.syncRange = Math.max(0.0D, syncRange);
        this.starter = Objects.requireNonNull(starter, "starter");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    void discoverPlayingSources(ServerLevel level) {
        if (level == null) {
            return;
        }
        Set<Integer> scannedItemEntities = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            scanPlayerInventory(level, player);
            AABB range = player.getBoundingBox().inflate(syncRange);
            for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, range)) {
                if (scannedItemEntities.add(itemEntity.getId())) {
                    scanItemEntity(level, itemEntity);
                }
            }
            scanOpenMenuSlots(level, player);
        }
    }

    void scanContainerEventMenu(Player player, AbstractContainerMenu menu) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(serverPlayer.level() instanceof ServerLevel level)
                || menu == null) {
            return;
        }
        scanMenuSlots(level, serverPlayer, menu);
    }

    MP4PlaybackSyncManager.Session relocateSession(MinecraftServer server,
            MP4PlaybackSyncManager.Session session, long gameTime) {
        if (server == null || session == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Set<Integer> scannedItemEntities = new HashSet<>();
            for (ServerPlayer player : level.players()) {
                ItemStack stack = MP4Item.findByDeviceId(player, session.sourceId());
                if (stack.getItem() instanceof MP4Item && deviceState(level, stack, session.sourceId()).playing()) {
                    MP4DeviceLocationIndex.recordPlayer(level, player, session.sourceId());
                    return session.asPlayerSource(player, gameTime);
                }
                ItemStack padStack = PadItem.findByDeviceId(player, session.sourceId());
                if (PadItem.isPad(padStack)) {
                    return session.asPlayerSource(player, gameTime);
                }
                AABB range = player.getBoundingBox().inflate(syncRange);
                for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, range)) {
                    if (!scannedItemEntities.add(itemEntity.getId())) {
                        continue;
                    }
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
                MP4PlaybackSyncManager.Session menuSession = relocateToOpenMenu(level, player, session, gameTime);
                if (menuSession != null) {
                    return menuSession;
                }
            }
        }
        return null;
    }

    MP4PlaybackSyncManager.Session refreshActiveSource(MinecraftServer server,
            MP4PlaybackSyncManager.Session session, long gameTime) {
        if (server == null || session == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            MP4DeviceLocationIndex.ResolvedLocation indexed = MP4DeviceLocationIndex.resolve(level,
                    session.sourceId()).orElse(null);
            if (indexed != null && indexed.stack().getItem() instanceof MP4Item
                    && deviceState(level, indexed.stack(), session.sourceId()).playing()) {
                return session.fromResolved(indexed, gameTime);
            }
            for (ServerPlayer player : level.players()) {
                ItemStack padStack = PadItem.findByDeviceId(player, session.sourceId());
                if (PadItem.isPad(padStack)) {
                    return session.asPlayerSource(player, gameTime);
                }
                MP4PlaybackSyncManager.Session menuSession = relocateToOpenMenu(level, player, session, gameTime);
                if (menuSession != null) {
                    return menuSession;
                }
            }
        }
        return null;
    }

    static boolean sameSource(MP4PlaybackSyncManager.Session left, MP4PlaybackSyncManager.Session right) {
        return MP4PlaybackSourceObservationPolicy.samePhysicalSource(identity(left), identity(right));
    }

    private static MP4PlaybackSourceObservationPolicy.Action observationAction(
            MP4PlaybackSyncManager.Session existing, int sourceType,
            int sourceEntityId, BlockPos sourcePos, int containerSlot) {
        MP4PlaybackSourceObservationPolicy.Observation current = existing == null ? null
                : new MP4PlaybackSourceObservationPolicy.Observation(existing.sourceType(), existing.sourceEntityId(),
                        existing.sourcePos(), existing.containerSlot());
        MP4PlaybackSourceObservationPolicy.Observation observed =
                new MP4PlaybackSourceObservationPolicy.Observation(sourceType, sourceEntityId, sourcePos,
                        containerSlot);
        return MP4PlaybackSourceObservationPolicy.action(current, observed);
    }

    private static MP4PlaybackSourceObservationPolicy.PhysicalIdentity identity(
            MP4PlaybackSyncManager.Session session) {
        return session == null ? null : new MP4PlaybackSourceObservationPolicy.PhysicalIdentity(
                session.levelKey(), session.ownerId(), session.sourceType(), session.sourceEntityId(),
                session.sourcePos(), session.containerSlot());
    }

    private void scanItemEntity(ServerLevel level, ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getItem();
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (!(stack.getItem() instanceof MP4Item) || !deviceState(level, stack, deviceId).playing()) {
            return;
        }
        MP4PlaybackSyncManager.Session existing = sessions.get(deviceId);
        MP4PlaybackSourceObservationPolicy.Action action = observationAction(existing,
                ClientMediaSyncPayload.SOURCE_ITEM,
                itemEntity.getId(), itemEntity.blockPosition(), -1);
        long gameTime = level.getGameTime();
        if (action == MP4PlaybackSourceObservationPolicy.Action.MIGRATE) {
            MP4PlaybackSyncManager.Session migrated = existing.asItemSource(itemEntity.getId(),
                    itemEntity.blockPosition(), gameTime);
            MP4DeviceLocationIndex.recordItemEntity(level, itemEntity, deviceId);
            publishReplacement(level, deviceId, existing, migrated, gameTime);
        } else if (action == MP4PlaybackSourceObservationPolicy.Action.START && !hasSessionForItemEntity(itemEntity)) {
            starter.start(level, stack, deviceId, deviceId, ClientMediaSyncPayload.SOURCE_ITEM,
                    itemEntity.getId(), itemEntity.blockPosition(), -1);
        }
    }

    private void scanPlayerInventory(ServerLevel level, ServerPlayer player) {
        scanPlayerHeldStack(level, player,
                player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            scanPlayerHeldStack(level, player, inventory.getItem(slot));
        }
    }

    private void scanPlayerHeldStack(ServerLevel level, ServerPlayer player, ItemStack stack) {
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (!(stack.getItem() instanceof MP4Item) || !deviceState(level, stack, deviceId).playing()) {
            return;
        }
        deviceId = MP4DeviceIdentity.getOrCreateUnique(level, player, stack);
        if (deviceId == null) {
            return;
        }
        MP4PlaybackSyncManager.Session existing = sessions.get(deviceId);
        long gameTime = level.getGameTime();
        MP4DeviceLocationIndex.recordPlayer(level, player, deviceId);
        MP4PlaybackSourceObservationPolicy.Action action = observationAction(existing,
                ClientMediaSyncPayload.SOURCE_PLAYER,
                player.getId(), player.blockPosition(), -1);
        if (action == MP4PlaybackSourceObservationPolicy.Action.MIGRATE) {
            MP4PlaybackSyncManager.Session migrated = existing.asPlayerSource(player, gameTime);
            publishReplacement(level, deviceId, existing, migrated, gameTime);
        } else if (action == MP4PlaybackSourceObservationPolicy.Action.START) {
            starter.start(level, stack, player.getUUID(), deviceId, ClientMediaSyncPayload.SOURCE_PLAYER,
                    player.getId(), player.blockPosition(), -1);
        }
    }

    private boolean hasSessionForItemEntity(ItemEntity itemEntity) {
        int entityId = itemEntity.getId();
        UUID deviceId = MP4Item.readDeviceId(itemEntity.getItem());
        for (MP4PlaybackSyncManager.Session session : sessions.values()) {
            if (session.sourceType() == ClientMediaSyncPayload.SOURCE_ITEM
                    && (session.sourceEntityId() == entityId || session.sourceId().equals(deviceId))) {
                return true;
            }
        }
        return false;
    }

    private void scanOpenMenuSlots(ServerLevel level, ServerPlayer player) {
        if (player.containerMenu != null) {
            scanMenuSlots(level, player, player.containerMenu);
        }
    }

    private void scanMenuSlots(ServerLevel level, ServerPlayer player, AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.container != player.getInventory()) {
                scanMenuSlot(level, player, slot);
            }
        }
    }

    private void scanMenuSlot(ServerLevel level, ServerPlayer player, Slot slot) {
        ItemStack stack = slot.getItem();
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (!(stack.getItem() instanceof MP4Item) || !deviceState(level, stack, deviceId).playing()
                || deviceId == null) {
            return;
        }
        Container container = slot.container;
        long gameTime = level.getGameTime();
        MP4PlaybackSyncManager.Session existing = sessions.get(deviceId);
        if (container instanceof BlockEntity blockEntity) {
            BlockPos pos = blockEntity.getBlockPos();
            MP4DeviceLocationIndex.recordBlockContainer(level, pos, slot.index, deviceId);
            MP4PlaybackSourceObservationPolicy.Action action = observationAction(existing,
                    ClientMediaSyncPayload.SOURCE_BLOCK,
                    -1, pos, slot.index);
            if (action == MP4PlaybackSourceObservationPolicy.Action.MIGRATE) {
                MP4PlaybackSyncManager.Session migrated = existing.asBlockSource(pos.immutable(), slot.index,
                        gameTime);
                publishReplacement(level, deviceId, existing, migrated, gameTime);
            } else if (action == MP4PlaybackSourceObservationPolicy.Action.START) {
                starter.start(level, stack, deviceId, deviceId, ClientMediaSyncPayload.SOURCE_BLOCK,
                        -1, pos.immutable(), slot.index);
            }
            return;
        }
        Entity entity = containerEntity(container);
        if (entity != null) {
            MP4DeviceLocationIndex.recordContainerEntity(level, entity, slot.index, deviceId);
            MP4PlaybackSourceObservationPolicy.Action action = observationAction(existing,
                    ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY,
                    entity.getId(), entity.blockPosition(), slot.index);
            if (action == MP4PlaybackSourceObservationPolicy.Action.MIGRATE) {
                MP4PlaybackSyncManager.Session migrated = existing.asContainerEntitySource(level, entity, slot.index,
                        gameTime);
                publishReplacement(level, deviceId, existing, migrated, gameTime);
            } else if (action == MP4PlaybackSourceObservationPolicy.Action.START) {
                starter.start(level, stack, deviceId, deviceId, ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY,
                        entity.getId(), entity.blockPosition(), slot.index);
            }
            return;
        }
        if (existing == null) {
            starter.start(level, stack, player.getUUID(), deviceId, ClientMediaSyncPayload.SOURCE_PLAYER,
                    player.getId(), player.blockPosition(), -1);
        }
    }

    private MP4PlaybackSyncManager.Session relocateToOpenMenu(ServerLevel level, ServerPlayer player,
            MP4PlaybackSyncManager.Session session, long gameTime) {
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

    private void publishReplacement(ServerLevel level, UUID deviceId, MP4PlaybackSyncManager.Session existing,
            MP4PlaybackSyncManager.Session migrated, long gameTime) {
        if (sessions.replace(deviceId, existing, migrated)) {
            publisher.publish(level, migrated, gameTime);
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

    private static MP4Item.State deviceState(ServerLevel level, ItemStack stack, UUID deviceId) {
        if (deviceId == null) {
            return MP4Item.State.DEFAULT;
        }
        return MP4DeviceStateStore.getOrCreate(level, deviceId, stack).state();
    }

    @FunctionalInterface
    interface DiscoveredSourceStarter {
        void start(ServerLevel level, ItemStack stack, UUID ownerId, UUID sourceId, int sourceType,
                int sourceEntityId, BlockPos sourcePos, int containerSlot);
    }

    @FunctionalInterface
    interface MigratedSessionPublisher {
        void publish(ServerLevel level, MP4PlaybackSyncManager.Session session, long gameTime);
    }
}
