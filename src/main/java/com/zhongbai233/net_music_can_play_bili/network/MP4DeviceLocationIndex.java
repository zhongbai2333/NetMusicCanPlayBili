package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端用于 MP4 物品栈位置的尽力而为索引。
 * <p>
 * NeoForge 没有暴露通用的物品转移事件，因此此索引使用已有的可靠观测（玩家背包、掉落物、打开的菜单），
 * 并在执行有界重新定位搜索之前验证已知持有者。
 * </p>
 */
public final class MP4DeviceLocationIndex {
    private static final int GRAPH_TTL_TICKS = 1200;
    private static final int GRAPH_REBUILD_COOLDOWN_TICKS = 100;
    private static final int INDEX_MAX_DEPTH = 5;
    private static final int INDEX_MAX_NODES = 32;
    private static final int INDEX_MAX_STORAGE_NODES = 16;
    private static final int INDEX_MAX_FRONTIER_PER_DEPTH = 6;
    private static final int INDEX_MAX_BRANCHES_PER_NODE = 3;
    private static final int INDEX_MAX_TOTAL_BRANCH_POINTS = 3;
    private static final double ITEM_ENTITY_SEARCH_RADIUS = 4.0D;
    private static final double CONTAINER_ENTITY_SEARCH_RADIUS = 6.0D;
    private static final int FALLBACK_SCAN_RADIUS = 3;
    private static final int FALLBACK_MAX_BLOCK_ENTITIES = 64;
    private static final int FALLBACK_MAX_SLOTS = 1024;

    private static final Map<UUID, LocationRef> LOCATIONS = new ConcurrentHashMap<>();
    private static final Map<GraphKey, LogisticsGraph> GRAPHS = new ConcurrentHashMap<>();

    private MP4DeviceLocationIndex() {
    }

    public static void clear() {
        LOCATIONS.clear();
        GRAPHS.clear();
    }

    public static void recordPlayer(ServerLevel level, ServerPlayer player, UUID deviceId) {
        if (level == null || player == null || deviceId == null) {
            return;
        }
        LOCATIONS.put(deviceId, LocationRef.player(level.dimension(), player.getUUID(), player.getId(),
                player.blockPosition(), level.getGameTime()));
    }

    public static void recordItemEntity(ServerLevel level, ItemEntity entity, UUID deviceId) {
        if (level == null || entity == null || deviceId == null) {
            return;
        }
        LOCATIONS.put(deviceId, LocationRef.item(level.dimension(), entity.getId(), entity.blockPosition(),
                level.getGameTime()));
    }

    public static void recordBlockContainer(ServerLevel level, BlockPos pos, int slot, UUID deviceId) {
        if (level == null || pos == null || deviceId == null) {
            return;
        }
        LOCATIONS.put(deviceId, LocationRef.block(level.dimension(), pos.immutable(), slot, level.getGameTime()));
        ensureGraph(level, pos.immutable(), false);
    }

    public static void recordContainerEntity(ServerLevel level, Entity entity, int slot, UUID deviceId) {
        if (level == null || entity == null || deviceId == null) {
            return;
        }
        LOCATIONS.put(deviceId, LocationRef.containerEntity(level.dimension(), entity.getId(), entity.blockPosition(),
                slot, level.getGameTime()));
    }

    public static Optional<ResolvedLocation> resolve(ServerLevel level, UUID deviceId) {
        if (level == null || deviceId == null) {
            return Optional.empty();
        }
        LocationRef ref = LOCATIONS.get(deviceId);
        if (ref == null || ref.dimension() != level.dimension()) {
            return Optional.empty();
        }
        long gameTime = level.getGameTime();
        Optional<ResolvedLocation> direct = verify(level, ref, deviceId, gameTime);
        if (direct.isPresent()) {
            return direct;
        }
        if (ref.type() == HolderType.PLAYER
                || ref.type() == HolderType.BLOCK_CONTAINER
                || ref.type() == HolderType.ITEM_ENTITY
                || ref.type() == HolderType.CONTAINER_ENTITY) {
            Optional<ResolvedLocation> relocated = relocateFromBlock(level, ref, deviceId, gameTime);
            if (relocated.isPresent()) {
                return relocated;
            }
        }
        return Optional.empty();
    }

    public static Optional<ResolvedLocation> relocateNear(ServerLevel level, UUID deviceId, BlockPos origin) {
        if (level == null || deviceId == null || origin == null) {
            return Optional.empty();
        }
        long gameTime = level.getGameTime();
        LocationRef ref = LocationRef.block(level.dimension(), origin.immutable(), -1, gameTime);
        return relocateFromBlock(level, ref, deviceId, gameTime);
    }

    private static Optional<ResolvedLocation> verify(ServerLevel level, LocationRef ref, UUID deviceId, long gameTime) {
        return switch (ref.type()) {
            case PLAYER -> verifyPlayer(level, ref, deviceId, gameTime);
            case ITEM_ENTITY -> verifyItemEntity(level, ref, deviceId, gameTime);
            case BLOCK_CONTAINER -> verifyBlockContainer(level, ref, deviceId, gameTime);
            case CONTAINER_ENTITY -> verifyContainerEntity(level, ref, deviceId, gameTime);
        };
    }

    private static Optional<ResolvedLocation> verifyPlayer(ServerLevel level, LocationRef ref, UUID deviceId,
            long gameTime) {
        ServerPlayer player = ref.holderId() != null
                ? level.getServer().getPlayerList().getPlayer(ref.holderId())
                : null;
        if (player == null) {
            return Optional.empty();
        }
        ItemStack stack = MP4Item.findByDeviceId(player, deviceId);
        if (!isMp4(stack, deviceId)) {
            return Optional.empty();
        }
        recordPlayer(level, player, deviceId);
        return Optional.of(new ResolvedLocation(stack, ClientMediaSyncPayload.SOURCE_PLAYER, player.getId(),
                player.blockPosition(), -1, player.getUUID()));
    }

    private static Optional<ResolvedLocation> verifyItemEntity(ServerLevel level, LocationRef ref, UUID deviceId,
            long gameTime) {
        Entity entity = level.getEntity(ref.entityId());
        if (!(entity instanceof ItemEntity itemEntity) || !isMp4(itemEntity.getItem(), deviceId)) {
            return Optional.empty();
        }
        recordItemEntity(level, itemEntity, deviceId);
        return Optional.of(new ResolvedLocation(itemEntity.getItem(), ClientMediaSyncPayload.SOURCE_ITEM,
                itemEntity.getId(), itemEntity.blockPosition(), -1, deviceId));
    }

    private static Optional<ResolvedLocation> verifyBlockContainer(ServerLevel level, LocationRef ref, UUID deviceId,
            long gameTime) {
        if (!level.isLoaded(ref.pos())) {
            return Optional.empty();
        }
        BlockEntity blockEntity = level.getBlockEntity(ref.pos());
        if (!(blockEntity instanceof Container container)) {
            return Optional.empty();
        }
        Optional<SlotMatch> match = findInContainer(container, deviceId, ref.slotHint());
        if (match.isEmpty()) {
            return Optional.empty();
        }
        int slot = match.get().slot();
        recordBlockContainer(level, ref.pos(), slot, deviceId);
        return Optional.of(new ResolvedLocation(match.get().stack(), ClientMediaSyncPayload.SOURCE_BLOCK, -1,
                ref.pos(), slot, deviceId));
    }

    private static Optional<ResolvedLocation> verifyContainerEntity(ServerLevel level, LocationRef ref, UUID deviceId,
            long gameTime) {
        Entity entity = level.getEntity(ref.entityId());
        if (!(entity instanceof Container container)) {
            return Optional.empty();
        }
        Optional<SlotMatch> match = findInContainer(container, deviceId, ref.slotHint());
        if (match.isEmpty()) {
            return Optional.empty();
        }
        int slot = match.get().slot();
        recordContainerEntity(level, entity, slot, deviceId);
        return Optional.of(new ResolvedLocation(match.get().stack(), ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY,
                entity.getId(), entity.blockPosition(), slot, deviceId));
    }

    private static Optional<ResolvedLocation> relocateFromBlock(ServerLevel level, LocationRef ref, UUID deviceId,
            long gameTime) {
        Optional<ResolvedLocation> graphHit = scanGraph(level, deviceId, ref.pos(), false);
        if (graphHit.isPresent()) {
            return graphHit;
        }
        Optional<ResolvedLocation> itemHit = scanItemEntities(level, deviceId, ref.pos());
        if (itemHit.isPresent()) {
            return itemHit;
        }
        Optional<ResolvedLocation> entityHit = scanContainerEntities(level, deviceId, ref.pos());
        if (entityHit.isPresent()) {
            return entityHit;
        }
        Optional<ResolvedLocation> rebuiltHit = scanGraph(level, deviceId, ref.pos(), true);
        if (rebuiltHit.isPresent()) {
            return rebuiltHit;
        }
        return fallbackScan(level, deviceId, ref.pos());
    }

    private static Optional<ResolvedLocation> scanGraph(ServerLevel level, UUID deviceId, BlockPos origin,
            boolean forceRebuild) {
        LogisticsGraph graph = ensureGraph(level, origin, forceRebuild);
        if (graph == null) {
            return Optional.empty();
        }
        for (StorageNode node : graph.storageNodes()) {
            Optional<ResolvedLocation> found = scanBlockStorage(level, deviceId, node.pos());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<ResolvedLocation> scanBlockStorage(ServerLevel level, UUID deviceId, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return Optional.empty();
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof Container container) {
            Optional<SlotMatch> match = findInContainer(container, deviceId, -1);
            if (match.isPresent()) {
                recordBlockContainer(level, pos, match.get().slot(), deviceId);
                return Optional.of(new ResolvedLocation(match.get().stack(), ClientMediaSyncPayload.SOURCE_BLOCK, -1,
                        pos.immutable(), match.get().slot(), deviceId));
            }
        }
        ResourceHandler<ItemResource> handler = itemHandler(level, pos);
        if (handler == null) {
            return Optional.empty();
        }
        int max = Math.min(handler.size(), FALLBACK_MAX_SLOTS);
        for (int slot = 0; slot < max; slot++) {
            ItemResource resource = handler.getResource(slot);
            if (resource.isEmpty()) {
                continue;
            }
            ItemStack stack = resource.toStack(handler.getAmountAsInt(slot));
            if (isMp4(stack, deviceId)) {
                recordBlockContainer(level, pos, slot, deviceId);
                return Optional.of(new ResolvedLocation(stack, ClientMediaSyncPayload.SOURCE_BLOCK, -1,
                        pos.immutable(), slot, deviceId));
            }
        }
        return Optional.empty();
    }

    private static Optional<ResolvedLocation> scanItemEntities(ServerLevel level, UUID deviceId, BlockPos origin) {
        AABB area = new AABB(origin).inflate(ITEM_ENTITY_SEARCH_RADIUS);
        for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, area)) {
            if (isMp4(itemEntity.getItem(), deviceId)) {
                recordItemEntity(level, itemEntity, deviceId);
                return Optional.of(new ResolvedLocation(itemEntity.getItem(), ClientMediaSyncPayload.SOURCE_ITEM,
                        itemEntity.getId(), itemEntity.blockPosition(), -1, deviceId));
            }
        }
        return Optional.empty();
    }

    private static Optional<ResolvedLocation> scanContainerEntities(ServerLevel level, UUID deviceId, BlockPos origin) {
        AABB area = new AABB(origin).inflate(CONTAINER_ENTITY_SEARCH_RADIUS);
        for (Entity entity : level.getEntitiesOfClass(Entity.class, area,
                candidate -> candidate instanceof Container)) {
            if (!(entity instanceof Container container)) {
                continue;
            }
            Optional<SlotMatch> match = findInContainer(container, deviceId, -1);
            if (match.isPresent()) {
                recordContainerEntity(level, entity, match.get().slot(), deviceId);
                return Optional.of(new ResolvedLocation(match.get().stack(),
                        ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY, entity.getId(), entity.blockPosition(),
                        match.get().slot(), deviceId));
            }
        }
        return Optional.empty();
    }

    private static Optional<ResolvedLocation> fallbackScan(ServerLevel level, UUID deviceId, BlockPos origin) {
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos.betweenClosedStream(origin.offset(-FALLBACK_SCAN_RADIUS, -FALLBACK_SCAN_RADIUS, -FALLBACK_SCAN_RADIUS),
                origin.offset(FALLBACK_SCAN_RADIUS, FALLBACK_SCAN_RADIUS, FALLBACK_SCAN_RADIUS))
                .map(pos -> pos.immutable())
                .filter(level::isLoaded)
                .filter(pos -> level.getBlockEntity(pos) != null || itemHandler(level, pos) != null)
                .sorted(Comparator.comparingInt(pos -> pos.distManhattan(origin)))
                .limit(FALLBACK_MAX_BLOCK_ENTITIES)
                .forEach(candidates::add);
        int slotsScanned = 0;
        for (BlockPos pos : candidates) {
            if (slotsScanned >= FALLBACK_MAX_SLOTS) {
                break;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Container container) {
                slotsScanned += container.getContainerSize();
            }
            Optional<ResolvedLocation> found = scanBlockStorage(level, deviceId, pos);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static LogisticsGraph ensureGraph(ServerLevel level, BlockPos origin, boolean forceRebuild) {
        if (level == null || origin == null || !level.isLoaded(origin)) {
            return null;
        }
        GraphKey key = new GraphKey(level.dimension(), origin.immutable());
        LogisticsGraph existing = GRAPHS.get(key);
        long gameTime = level.getGameTime();
        if (!forceRebuild && existing != null && gameTime - existing.createdGameTime() <= GRAPH_TTL_TICKS) {
            return existing;
        }
        if (forceRebuild && existing != null && gameTime - existing.createdGameTime() < GRAPH_REBUILD_COOLDOWN_TICKS) {
            return existing;
        }
        LogisticsGraph built = buildGraph(level, origin.immutable(), gameTime);
        GRAPHS.put(key, built);
        return built;
    }

    private static LogisticsGraph buildGraph(ServerLevel level, BlockPos origin, long gameTime) {
        List<StorageNode> storageNodes = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        visited.add(origin);
        for (Direction direction : Direction.values()) {
            BlockPos next = origin.relative(direction).immutable();
            if (level.isLoaded(next)) {
                queue.add(new SearchNode(next, direction.getOpposite(), 1, 0));
                visited.add(next);
            }
        }

        int totalNodes = 0;
        int branchPoints = 0;
        Map<Integer, Integer> frontier = new HashMap<>();
        while (!queue.isEmpty() && totalNodes < INDEX_MAX_NODES && storageNodes.size() < INDEX_MAX_STORAGE_NODES) {
            SearchNode node = queue.removeFirst();
            if (node.depth() > INDEX_MAX_DEPTH) {
                continue;
            }
            int depthFrontier = frontier.compute(node.depth(), (ignored, value) -> value == null ? 1 : value + 1);
            if (depthFrontier > INDEX_MAX_FRONTIER_PER_DEPTH) {
                continue;
            }
            NodeKind kind = classify(level, node.pos());
            if (kind == NodeKind.NONE) {
                continue;
            }
            totalNodes++;
            if (kind.canStore()) {
                storageNodes
                        .add(new StorageNode(node.pos(), node.depth(), score(kind, node.depth(), node.branchDepth())));
            }
            if (!kind.canTransit()) {
                continue;
            }
            List<SearchNode> nextNodes = new ArrayList<>();
            for (Direction direction : Direction.values()) {
                if (direction == node.cameFrom()) {
                    continue;
                }
                BlockPos next = node.pos().relative(direction).immutable();
                if (!visited.add(next) || !level.isLoaded(next)) {
                    continue;
                }
                NodeKind nextKind = classify(level, next);
                if (nextKind == NodeKind.NONE) {
                    continue;
                }
                nextNodes.add(new SearchNode(next, direction.getOpposite(), node.depth() + 1,
                        node.branchDepth() + (nextNodes.isEmpty() ? 0 : 1)));
            }
            if (nextNodes.size() >= INDEX_MAX_BRANCHES_PER_NODE) {
                branchPoints++;
                for (SearchNode next : nextNodes) {
                    NodeKind nextKind = classify(level, next.pos());
                    if (nextKind.canStore() && storageNodes.size() < INDEX_MAX_STORAGE_NODES) {
                        storageNodes.add(new StorageNode(next.pos(), next.depth(),
                                score(nextKind, next.depth(), next.branchDepth()) - 30));
                    }
                }
                if (branchPoints > INDEX_MAX_TOTAL_BRANCH_POINTS) {
                    break;
                }
                continue;
            }
            queue.addAll(nextNodes);
        }
        storageNodes.sort(Comparator.comparingInt((StorageNode node) -> node.score()).reversed());
        return new LogisticsGraph(gameTime, List.copyOf(storageNodes));
    }

    private static int score(NodeKind kind, int depth, int branchDepth) {
        return kind.baseScore() - depth * 8 - branchDepth * 10;
    }

    private static NodeKind classify(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return NodeKind.NONE;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        boolean hasItemHandler = itemHandler(level, pos) != null;
        if (blockEntity instanceof Container) {
            if (isKnownTransitBlock(level, pos) || hasItemHandler) {
                return NodeKind.STORAGE_AND_TRANSIT;
            }
            return NodeKind.STORAGE;
        }
        if (hasItemHandler) {
            return NodeKind.STORAGE_AND_TRANSIT;
        }
        if (isKnownTransitBlock(level, pos)) {
            return NodeKind.TRANSIT;
        }
        return NodeKind.NONE;
    }

    private static boolean isKnownTransitBlock(ServerLevel level, BlockPos pos) {
        var block = level.getBlockState(pos).getBlock();
        return block instanceof HopperBlock || block instanceof DropperBlock || block instanceof DispenserBlock;
    }

    private static ResourceHandler<ItemResource> itemHandler(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, direction);
            if (handler != null) {
                return handler;
            }
        }
        return level.getCapability(Capabilities.Item.BLOCK, pos, null);
    }

    private static Optional<SlotMatch> findInContainer(Container container, UUID deviceId, int slotHint) {
        if (container == null || deviceId == null) {
            return Optional.empty();
        }
        if (slotHint >= 0 && slotHint < container.getContainerSize()) {
            ItemStack hinted = container.getItem(slotHint);
            if (isMp4(hinted, deviceId)) {
                return Optional.of(new SlotMatch(slotHint, hinted));
            }
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (isMp4(stack, deviceId)) {
                return Optional.of(new SlotMatch(slot, stack));
            }
        }
        return Optional.empty();
    }

    private static boolean isMp4(ItemStack stack, UUID deviceId) {
        return !stack.isEmpty() && stack.getItem() instanceof MP4Item && deviceId.equals(MP4Item.readDeviceId(stack));
    }

    public record ResolvedLocation(ItemStack stack, int sourceType, int sourceEntityId, BlockPos sourcePos,
            int containerSlot, UUID ownerId) {
    }

    private enum HolderType {
        PLAYER,
        ITEM_ENTITY,
        BLOCK_CONTAINER,
        CONTAINER_ENTITY
    }

    private enum NodeKind {
        NONE(false, false, 0),
        STORAGE(true, false, 70),
        TRANSIT(false, true, 80),
        STORAGE_AND_TRANSIT(true, true, 100);

        private final boolean canStore;
        private final boolean canTransit;
        private final int baseScore;

        NodeKind(boolean canStore, boolean canTransit, int baseScore) {
            this.canStore = canStore;
            this.canTransit = canTransit;
            this.baseScore = baseScore;
        }

        boolean canStore() {
            return canStore;
        }

        boolean canTransit() {
            return canTransit;
        }

        int baseScore() {
            return baseScore;
        }
    }

    private record LocationRef(HolderType type, ResourceKey<Level> dimension, UUID holderId, int entityId,
            BlockPos pos, int slotHint, long lastSeenGameTime) {
        static LocationRef player(ResourceKey<Level> dimension, UUID playerId, int entityId, BlockPos pos,
                long gameTime) {
            return new LocationRef(HolderType.PLAYER, dimension, playerId, entityId, pos.immutable(), -1, gameTime);
        }

        static LocationRef item(ResourceKey<Level> dimension, int entityId, BlockPos pos, long gameTime) {
            return new LocationRef(HolderType.ITEM_ENTITY, dimension, null, entityId, pos.immutable(), -1, gameTime);
        }

        static LocationRef block(ResourceKey<Level> dimension, BlockPos pos, int slot, long gameTime) {
            return new LocationRef(HolderType.BLOCK_CONTAINER, dimension, null, -1, pos.immutable(), slot, gameTime);
        }

        static LocationRef containerEntity(ResourceKey<Level> dimension, int entityId, BlockPos pos, int slot,
                long gameTime) {
            return new LocationRef(HolderType.CONTAINER_ENTITY, dimension, null, entityId, pos.immutable(), slot,
                    gameTime);
        }
    }

    private record SlotMatch(int slot, ItemStack stack) {
    }

    private record GraphKey(ResourceKey<Level> dimension, BlockPos origin) {
    }

    private record LogisticsGraph(long createdGameTime, List<StorageNode> storageNodes) {
    }

    private record StorageNode(BlockPos pos, int depth, int score) {
    }

    private record SearchNode(BlockPos pos, Direction cameFrom, int depth, int branchDepth) {
    }
}
