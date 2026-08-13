package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainDirtyTracker;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCoverageCursor;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainNeighborhoodIndex;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainFixedCorePolicy;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainPackedLight;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainTintColors;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainMaterialAggregator;
import com.zhongbai233.net_music_can_play_bili.terrain.core.WeightedLruCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.LightLayer;
import org.joml.Vector3dc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * 单活跃中控台地形会话。初始化中心周围使用固定直径 25 的球形实景核心，
 * 3 格边缘通过稳定空间抖动消隐；硬范围内其余已加载地形发布聚合材质网格，未知区块保留线框。
 * 相机移动不会改变表示层级，PIP 只消费渐进生成的不可变快照。
 */
public final class TerrainPreviewManager {
    private static volatile Session active;
    private static volatile Session parked;
    private static volatile TerrainPreviewFrame published = TerrainPreviewFrame.empty();

    private TerrainPreviewManager() {
    }

    public static void update(ClientLevel level, BlockPos origin, TerrainBounds bounds,
            Vector3dc coreCenterLocal) {
        if (level == null || origin == null || bounds == null || coreCenterLocal == null) {
            return;
        }
        Session session = active;
        if (session == null && parked != null && parked.matches(level, origin, bounds, coreCenterLocal)) {
            session = parked;
            parked = null;
            session.resume();
            active = session;
            published = session.frame();
            return;
        }
        if (session == null || session.level != level || !session.origin.equals(origin)
                || !session.bounds.equals(bounds) || !session.hasCoreCenter(coreCenterLocal)) {
            if (session != null) {
                session.close();
            }
            if (parked != null) {
                parked.close();
                parked = null;
            }
                session = new Session(level, origin.immutable(), bounds, coreCenterLocal,
                    session == null ? 1L : session.generation + 1L);
            active = session;
            published = session.frame();
        }
    }

    public static void tick() {
        Session session = active;
        if (session == null) {
            return;
        }
        if (Minecraft.getInstance().level != session.level) {
            clear();
            return;
        }
        session.tick();
        published = session.frame();
    }

    public static TerrainPreviewFrame frame() {
        return published;
    }

    /** GPU 已持久化该版本网格；固定核心快照继续保留，供再次进入时直接重建 GPU。 */
    public static void markCompiled(long generation, TerrainBlockSectionSnapshot source) {
        Session session = active;
        if (session == null || session.generation != generation || source == null) {
            return;
        }
        session.markCompiled(source);
    }

    public static void markBlockDirty(ClientLevel level, BlockPos pos) {
        markBlockDirty(active, level, pos);
        markBlockDirty(parked, level, pos);
    }

    private static void markBlockDirty(Session session, ClientLevel level, BlockPos pos) {
        if (session != null && session.level == level
                && session.bounds.contains(pos.getX(), pos.getY(), pos.getZ())) {
            session.dirty.markBlockAndBoundaryNeighbors(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    public static void markChunkUnloaded(ClientLevel level, int chunkX, int chunkZ) {
        if (active != null && active.level == level) {
            active.markChunkUnloaded(chunkX, chunkZ);
            published = active.frame();
        }
        if (parked != null && parked.level == level) {
            parked.markChunkUnloaded(chunkX, chunkZ);
        }
    }

    public static void markChunkLoaded(ClientLevel level, int chunkX, int chunkZ) {
        if (active != null && active.level == level) {
            active.markChunkLoaded(chunkX, chunkZ);
            published = active.frame();
        }
        if (parked != null && parked.level == level) {
            parked.markChunkLoaded(chunkX, chunkZ);
        }
    }

    public static void close(BlockPos origin) {
        Session session = active;
        if (session != null && session.origin.equals(origin)) {
            active = null;
            if (parked != null && parked != session) {
                parked.close();
            }
            session.suspend();
            parked = session;
            published = TerrainPreviewFrame.empty();
        }
    }

    public static void clear() {
        Session session = active;
        active = null;
        if (session != null) {
            session.close();
        }
        Session cached = parked;
        parked = null;
        if (cached != null && cached != session) {
            cached.close();
        }
        published = TerrainPreviewFrame.empty();
    }

    private static final class Session {
        private static final long FULL_BLOCK_CACHE_BYTES = 256L * 1024L * 1024L;
        private static final long ESTIMATED_SECTION_BASE_BYTES = 256L;
        private static final long ESTIMATED_VISIBLE_BLOCK_BYTES = 48L;
        private static final long ESTIMATED_NEIGHBORHOOD_CELL_BYTES = 9L;
        private static final long CAPTURE_BUDGET_NANOS = 4_000_000L;
        private static final int MAX_CAPTURES_PER_TICK = 8;
        private static final int MAX_BUFFERED_SNAPSHOTS = 32;
        private static final int COVERAGE_CANDIDATES_PER_TICK = 64;
        private static final int MAX_PENDING_SECTIONS = 512;
        private static final int MAX_UNKNOWN_SECTIONS = 256;
        private static final int MAX_BLOCK_ENTITY_PREVIEWS = 128;
        private final ClientLevel level;
        private final BlockPos origin;
        private final TerrainBounds bounds;
        private final long generation;
        private final TerrainDirtyTracker dirty = new TerrainDirtyTracker(1024);
        private final WeightedLruCache<TerrainSectionKey, TerrainBlockSectionSnapshot> sections =
            new WeightedLruCache<>(FULL_BLOCK_CACHE_BYTES, snapshot -> snapshot.estimatedBytes());
        private final ArrayDeque<TerrainSectionKey> pending = new ArrayDeque<>();
        private final Set<TerrainSectionKey> queued = new HashSet<>();
        private final Set<TerrainSectionKey> removedSections = new HashSet<>();
        private final Set<TerrainSectionKey> fullDetailSectionKeys = new HashSet<>();
        private final LinkedHashMap<TerrainSectionKey, TerrainOverviewCell> unknownSections =
            new LinkedHashMap<>();
        private final LinkedHashMap<TerrainSectionKey, List<TerrainOverviewCell>> overviewBySection =
            new LinkedHashMap<>();
        private final Set<BlockPos> blockEntityPositions = new HashSet<>();
        private List<TerrainBlockEntityPreview> blockEntityPreviews = List.of();
        private final TerrainCoverageCursor coverageCursor;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private TerrainPreviewFrame cachedFrame;
        private boolean frameDirty = true;
        private boolean closed;
        private boolean suspended;
        private final org.joml.Vector3d coreCenterLocal;
        private final double coreCenterX;
        private final double coreCenterY;
        private final double coreCenterZ;
        private final long coreSeed;

        private Session(ClientLevel level, BlockPos origin, TerrainBounds bounds,
            Vector3dc coreCenterLocal, long generation) {
            this.level = level;
            this.origin = origin;
            this.bounds = bounds;
            this.generation = generation;
            this.coreCenterLocal = new org.joml.Vector3d(coreCenterLocal);
            this.coreCenterX = origin.getX() + coreCenterLocal.x();
            this.coreCenterY = origin.getY() + coreCenterLocal.y();
            this.coreCenterZ = origin.getZ() + coreCenterLocal.z();
            this.coreSeed = seed(origin);
            this.coverageCursor = new TerrainCoverageCursor(bounds,
                coverageFocus(origin, coreCenterLocal));
        }

        private boolean hasCoreCenter(Vector3dc center) {
            return coreCenterLocal.distanceSquared(center) < 1.0e-8D;
        }

        private boolean matches(ClientLevel level, BlockPos origin, TerrainBounds bounds, Vector3dc center) {
            return !closed && this.level == level && this.origin.equals(origin)
                    && this.bounds.equals(bounds) && hasCoreCenter(center);
        }

        private void suspend() {
            suspended = true;
        }

        private void resume() {
            suspended = false;
            frameDirty = true;
        }

        private static long seed(BlockPos origin) {
            long value = ((long) origin.getX() * 341873128712L)
                ^ ((long) origin.getY() * 132897987541L)
                ^ ((long) origin.getZ() * 42317861L);
            return value ^ value >>> 29;
        }

        private static TerrainSectionKey coverageFocus(BlockPos origin, Vector3dc cameraLocal) {
            return TerrainSectionKey.fromBlock(
                    floorBlock(origin.getX(), cameraLocal.x()),
                    floorBlock(origin.getY(), cameraLocal.y()),
                    floorBlock(origin.getZ(), cameraLocal.z()));
        }

        private static int floorBlock(int origin, double local) {
            double world = origin + local;
            if (world <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
            if (world >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) Math.floor(world);
        }

        private void scheduleCoverageStep() {
            if (queued.size() >= MAX_PENDING_SECTIONS) {
                return;
            }
            for (TerrainSectionKey key : coverageCursor.next(COVERAGE_CANDIDATES_PER_TICK)) {
                if (level.hasChunk(key.x(), key.z())) {
                    if (unknownSections.remove(key) != null) {
                        frameDirty = true;
                    }
                    enqueue(key);
                } else {
                    rememberUnknown(key);
                }
            }
        }

        private void tick() {
            if (closed || suspended) {
                return;
            }
            scheduleCoverageStep();
            List<TerrainSectionKey> dirtySections = dirty.drain(8);
            for (TerrainSectionKey key : dirtySections) {
                sections.remove(key);
                fullDetailSectionKeys.remove(key);
                overviewBySection.remove(key);
                enqueueFirst(key);
            }
            long deadline = System.nanoTime() + CAPTURE_BUDGET_NANOS;
            int captured = 0;
            while (captured < MAX_CAPTURES_PER_TICK) {
                TerrainSectionKey key = pending.pollFirst();
                if (key == null) {
                    break;
                }
                frameDirty = true;
                queued.remove(key);
                boolean detailCandidate = TerrainFixedCorePolicy.sectionMayContainDetail(
                    coreCenterX, coreCenterY, coreCenterZ,
                    key.minBlockX(), key.minBlockY(), key.minBlockZ());
                if (detailCandidate && dirtySections.isEmpty()
                    && sections.size() >= MAX_BUFFERED_SNAPSHOTS) {
                    enqueue(key);
                    break;
                }
                capture(key);
                captured++;
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            refreshBlockEntityPreviews();
        }

        private void capture(TerrainSectionKey key) {
            int chunkX = key.x();
            int chunkZ = key.z();
            if (removedSections.contains(key) || !level.hasChunk(chunkX, chunkZ)) {
                return;
            }
            if (!TerrainFixedCorePolicy.sectionMayContainDetail(coreCenterX, coreCenterY, coreCenterZ,
                    key.minBlockX(), key.minBlockY(), key.minBlockZ())) {
                captureMaterialLod(key);
                return;
            }
            CapturedNeighborhood neighborhood = captureNeighborhood(key);
            blockEntityPositions.removeIf(pos -> TerrainSectionKey.fromBlock(
                    pos.getX(), pos.getY(), pos.getZ()).equals(key));
            List<TerrainBlockSectionSnapshot.VisibleBlock> detail = new ArrayList<>();
            List<TerrainBlockSectionSnapshot.VisibleBlock> wire = new ArrayList<>();
            for (int localY = 0; localY < TerrainSectionKey.SIZE; localY++) {
                int worldY = key.minBlockY() + localY;
                if (worldY < bounds.minY() || worldY > bounds.maxY()
                        || worldY < level.getMinY() || worldY >= level.getMaxY()) {
                    continue;
                }
                for (int localZ = 0; localZ < TerrainSectionKey.SIZE; localZ++) {
                    int worldZ = key.minBlockZ() + localZ;
                    if (worldZ < bounds.minZ() || worldZ > bounds.maxZ()) {
                        continue;
                    }
                    for (int localX = 0; localX < TerrainSectionKey.SIZE; localX++) {
                        int worldX = key.minBlockX() + localX;
                        if (worldX < bounds.minX() || worldX > bounds.maxX()) {
                            continue;
                        }
                        BlockState state = neighborhood.states().get(TerrainNeighborhoodIndex.index(
                            localX, localY, localZ));
                        if (!isRenderableState(state)) {
                            continue;
                        }
                        cursor.set(worldX, worldY, worldZ);
                        var block = new TerrainBlockSectionSnapshot.VisibleBlock(localX, localY, localZ, 1, state,
                            new TerrainTintColors(
                                safeBlockTint(BiomeColors.GRASS_COLOR_RESOLVER),
                                safeBlockTint(BiomeColors.FOLIAGE_COLOR_RESOLVER),
                                safeBlockTint(BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER),
                                safeBlockTint(BiomeColors.WATER_COLOR_RESOLVER)),
                            safeTintLayers(state), safePackedLightAtCursor());
                        if (rendersDetail(worldX, worldY, worldZ)) {
                            detail.add(block);
                            if (state.hasBlockEntity()
                                    && blockEntityPositions.size() < MAX_BLOCK_ENTITY_PREVIEWS) {
                                blockEntityPositions.add(cursor.immutable());
                            }
                        } else {
                            wire.add(block);
                        }
                    }
                }
            }
            long estimatedBytes = ESTIMATED_SECTION_BASE_BYTES
                    + detail.size() * ESTIMATED_VISIBLE_BLOCK_BYTES
                    + neighborhood.states().size() * ESTIMATED_NEIGHBORHOOD_CELL_BYTES;
            if (detail.isEmpty()) {
                sections.remove(key);
                fullDetailSectionKeys.remove(key);
            } else {
                sections.put(key, new TerrainBlockSectionSnapshot(key, detail,
                        maskedNeighborhood(key, neighborhood.states()), neighborhood.light(), estimatedBytes));
                fullDetailSectionKeys.add(key);
            }
            if (wire.isEmpty()) {
                overviewBySection.remove(key);
            } else {
                overviewBySection.put(key, aggregateOverview(key, wire, overviewCellSize(key)));
            }
            frameDirty = true;
        }

        private void captureMaterialLod(TerrainSectionKey key) {
            List<TerrainMaterialAggregator.Sample<BlockState>> visible = new ArrayList<>();
            for (int localY = 0; localY < TerrainSectionKey.SIZE; localY++) {
                int worldY = key.minBlockY() + localY;
                if (worldY < bounds.minY() || worldY > bounds.maxY()
                        || worldY < level.getMinY() || worldY >= level.getMaxY()) {
                    continue;
                }
                for (int localZ = 0; localZ < TerrainSectionKey.SIZE; localZ++) {
                    int worldZ = key.minBlockZ() + localZ;
                    if (worldZ < bounds.minZ() || worldZ > bounds.maxZ()) {
                        continue;
                    }
                    for (int localX = 0; localX < TerrainSectionKey.SIZE; localX++) {
                        int worldX = key.minBlockX() + localX;
                        if (worldX < bounds.minX() || worldX > bounds.maxX()) {
                            continue;
                        }
                        BlockState state = safeBlockState(worldX, worldY, worldZ);
                        if (state != null && isRenderableState(state)) {
                            visible.add(new TerrainMaterialAggregator.Sample<>(localX, localY, localZ, state));
                        }
                    }
                }
            }
            if (visible.isEmpty()) {
                sections.remove(key);
                fullDetailSectionKeys.remove(key);
                overviewBySection.remove(key);
            } else {
                int cellSize = overviewCellSize(key);
                List<TerrainBlockSectionSnapshot.VisibleBlock> materialCells = new ArrayList<>();
                long tintLayerCount = 0L;
                for (TerrainMaterialAggregator.Cell<BlockState> cell
                        : TerrainMaterialAggregator.aggregate(visible, cellSize)) {
                    TerrainMaterialAggregator.Sample<BlockState> representative = cell.representative();
                    int worldX = key.minBlockX() + representative.localX();
                    int worldY = key.minBlockY() + representative.localY();
                    int worldZ = key.minBlockZ() + representative.localZ();
                    cursor.set(worldX, worldY, worldZ);
                    List<Integer> tintLayers = safeTintLayers(representative.material());
                    tintLayerCount += tintLayers.size();
                    materialCells.add(new TerrainBlockSectionSnapshot.VisibleBlock(
                            cell.localX(), cell.localY(), cell.localZ(), cell.size(),
                            representative.material(), new TerrainTintColors(
                                safeBlockTint(BiomeColors.GRASS_COLOR_RESOLVER),
                                safeBlockTint(BiomeColors.FOLIAGE_COLOR_RESOLVER),
                                safeBlockTint(BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER),
                                safeBlockTint(BiomeColors.WATER_COLOR_RESOLVER)),
                            tintLayers, safePackedLightAtCursor()));
                }
                long estimatedBytes = ESTIMATED_SECTION_BASE_BYTES
                        + materialCells.size() * ESTIMATED_VISIBLE_BLOCK_BYTES
                        + tintLayerCount * Integer.BYTES;
                sections.put(key, new TerrainBlockSectionSnapshot(key, materialCells, estimatedBytes));
                fullDetailSectionKeys.add(key);
                overviewBySection.remove(key);
            }
            frameDirty = true;
        }

        private boolean rendersDetail(int worldX, int worldY, int worldZ) {
            return TerrainFixedCorePolicy.rendersBlock(coreSeed, coreCenterX, coreCenterY, coreCenterZ,
                    worldX, worldY, worldZ);
        }

        private int overviewCellSize(TerrainSectionKey key) {
            double centerX = key.minBlockX() + TerrainSectionKey.SIZE * 0.5D;
            double centerY = key.minBlockY() + TerrainSectionKey.SIZE * 0.5D;
            double centerZ = key.minBlockZ() + TerrainSectionKey.SIZE * 0.5D;
            double dx = centerX - coreCenterX;
            double dy = centerY - coreCenterY;
            double dz = centerZ - coreCenterZ;
            return TerrainFixedCorePolicy.overviewCellSize(Math.sqrt(dx * dx + dy * dy + dz * dz));
        }

        /** Mod 方块可能通过状态扩展抛出异常；预览应把它降级为空气，而不是击穿客户端 tick。 */
        private static boolean isRenderableState(BlockState state) {
            try {
                return state.getRenderShape() == RenderShape.MODEL
                    || !state.getFluidState().isEmpty();
            } catch (Throwable incompatibleModState) {
                if (incompatibleModState instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                if (incompatibleModState instanceof Error fatal) {
                    throw fatal;
                }
                return false;
            }
        }

        private List<BlockState> maskedNeighborhood(TerrainSectionKey key, List<BlockState> source) {
            List<BlockState> masked = new ArrayList<>(source);
            int index = 0;
            for (int localY = TerrainNeighborhoodIndex.MIN_LOCAL;
                    localY <= TerrainNeighborhoodIndex.MAX_LOCAL; localY++) {
                for (int localZ = TerrainNeighborhoodIndex.MIN_LOCAL;
                        localZ <= TerrainNeighborhoodIndex.MAX_LOCAL; localZ++) {
                    for (int localX = TerrainNeighborhoodIndex.MIN_LOCAL;
                            localX <= TerrainNeighborhoodIndex.MAX_LOCAL; localX++) {
                        int worldX = key.minBlockX() + localX;
                        int worldY = key.minBlockY() + localY;
                        int worldZ = key.minBlockZ() + localZ;
                        if (!rendersDetail(worldX, worldY, worldZ)) {
                            masked.set(index, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                        }
                        index++;
                    }
                }
            }
            return List.copyOf(masked);
        }

        private void markCompiled(TerrainBlockSectionSnapshot source) {
            // 固定核心至多跨越少量 section；保留不可变 CPU 快照以便再次进入时直接重建 GPU，
            // 避免重新读取 Level、光照、biome tint 与 20³ 邻域。
        }

        private CapturedNeighborhood captureNeighborhood(TerrainSectionKey key) {
            List<BlockState> states = new ArrayList<>(TerrainNeighborhoodIndex.CELL_COUNT);
            byte[] light = new byte[TerrainNeighborhoodIndex.CELL_COUNT];
            boolean[] loadedChunks = captureLoadedChunks(key);
            boolean[] readableColumns = captureReadableColumns(key, loadedChunks);
            int index = 0;
            for (int localY = TerrainNeighborhoodIndex.MIN_LOCAL;
                    localY <= TerrainNeighborhoodIndex.MAX_LOCAL; localY++) {
                int worldY = key.minBlockY() + localY;
                boolean validY = worldY >= level.getMinY() && worldY < level.getMaxY()
                    && worldY >= bounds.minY() && worldY <= bounds.maxY();
                for (int localZ = TerrainNeighborhoodIndex.MIN_LOCAL;
                        localZ <= TerrainNeighborhoodIndex.MAX_LOCAL; localZ++) {
                    int worldZ = key.minBlockZ() + localZ;
                    int columnBase = (localZ - TerrainNeighborhoodIndex.MIN_LOCAL)
                        * TerrainNeighborhoodIndex.SIZE;
                    for (int localX = TerrainNeighborhoodIndex.MIN_LOCAL;
                            localX <= TerrainNeighborhoodIndex.MAX_LOCAL; localX++) {
                        int worldX = key.minBlockX() + localX;
                        boolean readable = validY && readableColumns[columnBase
                            + localX - TerrainNeighborhoodIndex.MIN_LOCAL];
                        BlockState state = readable ? safeBlockState(worldX, worldY, worldZ) : null;
                        states.add(state != null ? state : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                        light[index++] = state != null ? safePackedLightAtCursor() : 0;
                    }
                }
            }
            return new CapturedNeighborhood(states, light);
        }

        private boolean[] captureReadableColumns(TerrainSectionKey key, boolean[] loadedChunks) {
            boolean[] readable = new boolean[TerrainNeighborhoodIndex.SIZE
                    * TerrainNeighborhoodIndex.SIZE];
            int index = 0;
            for (int localZ = TerrainNeighborhoodIndex.MIN_LOCAL;
                    localZ <= TerrainNeighborhoodIndex.MAX_LOCAL; localZ++) {
                int worldZ = key.minBlockZ() + localZ;
                boolean validZ = worldZ >= bounds.minZ() && worldZ <= bounds.maxZ();
                for (int localX = TerrainNeighborhoodIndex.MIN_LOCAL;
                        localX <= TerrainNeighborhoodIndex.MAX_LOCAL; localX++) {
                    int worldX = key.minBlockX() + localX;
                    readable[index++] = validZ && worldX >= bounds.minX() && worldX <= bounds.maxX()
                            && loadedChunks[TerrainNeighborhoodIndex.neighborChunkIndex(localX, localZ)];
                }
            }
            return readable;
        }

        private boolean[] captureLoadedChunks(TerrainSectionKey key) {
            boolean[] loaded = new boolean[9];
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    loaded[(offsetZ + 1) * 3 + offsetX + 1] =
                            level.hasChunk(key.x() + offsetX, key.z() + offsetZ);
                }
            }
            return loaded;
        }

        private byte safePackedLightAtCursor() {
            try {
                return TerrainPackedLight.pack(level.getBrightness(LightLayer.BLOCK, cursor),
                        level.getBrightness(LightLayer.SKY, cursor));
            } catch (Throwable incompatibleModLighting) {
                if (incompatibleModLighting instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                return 0;
            }
        }

        private BlockState safeBlockState(int x, int y, int z) {
            try {
                cursor.set(x, y, z);
                return level.getBlockState(cursor);
            } catch (Throwable incompatibleModBlock) {
                if (incompatibleModBlock instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                return null;
            }
        }

        private int safeBlockTint(net.minecraft.world.level.ColorResolver resolver) {
            try {
                return level.getBlockTint(cursor, resolver);
            } catch (Throwable incompatibleModBiome) {
                if (incompatibleModBiome instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                return -1;
            }
        }

        /** Freezes every registered vanilla/mod tint layer on the client thread. */
        private List<Integer> safeTintLayers(BlockState state) {
            try {
                var sources = Minecraft.getInstance().getBlockColors().getTintSources(state);
                if (sources.isEmpty()) {
                    return List.of();
                }
                List<Integer> colors = new ArrayList<>(Math.min(sources.size(), 32));
                for (int index = 0; index < sources.size() && index < 32; index++) {
                    try {
                        colors.add(sources.get(index).colorInWorld(state, level, cursor));
                    } catch (Throwable incompatibleTintSource) {
                        if (incompatibleTintSource instanceof VirtualMachineError fatal) {
                            throw fatal;
                        }
                        colors.add(-1);
                    }
                }
                return List.copyOf(colors);
            } catch (Throwable incompatibleBlockColors) {
                if (incompatibleBlockColors instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                return List.of();
            }
        }

        private void refreshBlockEntityPreviews() {
            if (blockEntityPositions.isEmpty()) {
                if (!blockEntityPreviews.isEmpty()) {
                    blockEntityPreviews = List.of();
                    frameDirty = true;
                }
                return;
            }
            List<TerrainBlockEntityPreview> refreshed = new ArrayList<>(blockEntityPositions.size());
            for (BlockPos pos : blockEntityPositions) {
                if (refreshed.size() >= MAX_BLOCK_ENTITY_PREVIEWS
                        || !level.hasChunk(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16))) {
                    continue;
                }
                try {
                    var blockEntity = level.getBlockEntity(pos);
                    if (blockEntity == null) {
                        continue;
                    }
                    var preview = extractBlockEntityPreview(blockEntity);
                    if (preview != null) {
                        refreshed.add(preview);
                    }
                } catch (Throwable incompatibleBlockEntity) {
                    if (incompatibleBlockEntity instanceof VirtualMachineError fatal) {
                        throw fatal;
                    }
                }
            }
            blockEntityPreviews = List.copyOf(refreshed);
            frameDirty = true;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static TerrainBlockEntityPreview extractBlockEntityPreview(
                net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
            var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
            net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer =
                    dispatcher.getRenderer(blockEntity);
            if (renderer == null) {
                return null;
            }
            net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState state =
                    (net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState) renderer.createRenderState();
            renderer.extractRenderState(blockEntity, state, 0.0F,
                    net.minecraft.world.phys.Vec3.atCenterOf(blockEntity.getBlockPos()), null);
            return new TerrainBlockEntityPreview(blockEntity.getBlockPos(), state);
        }

        private record CapturedNeighborhood(List<BlockState> states, byte[] light) {
        }

        private void enqueue(TerrainSectionKey key) {
            if (!removedSections.contains(key) && bounds.intersects(key)
                    && sections.get(key).isEmpty() && queued.size() < MAX_PENDING_SECTIONS
                    && queued.add(key)) {
                pending.addLast(key);
            }
        }

        private void enqueueFirst(TerrainSectionKey key) {
            if (!removedSections.contains(key) && bounds.intersects(key) && queued.add(key)) {
                pending.addFirst(key);
            }
        }

        private TerrainPreviewFrame frame() {
            if (!frameDirty && cachedFrame != null) {
                return cachedFrame;
            }
            List<TerrainBlockSectionSnapshot> values = new ArrayList<>(sections.snapshot().values());
            values.sort(Comparator.comparingInt((TerrainBlockSectionSnapshot snapshot) -> snapshot.section().y())
                    .thenComparingInt(snapshot -> snapshot.section().z())
                    .thenComparingInt(snapshot -> snapshot.section().x()));
                List<TerrainOverviewCell> overview = new ArrayList<>(unknownSections.values());
                overviewBySection.values().forEach(overview::addAll);
                List<TerrainWireframeMesher.Segment> wireframe = TerrainWireframeMesher.mesh(overview, bounds);
                cachedFrame = new TerrainPreviewFrame(generation, origin.getX(), origin.getY(), origin.getZ(),
                    coreCenterX, coreCenterY, coreCenterZ, bounds,
                    overview, wireframe, List.of(), values, fullDetailSectionKeys, removedSections,
                    blockEntityPreviews,
                    pending.size(), sections.size() + overviewBySection.size());
            frameDirty = false;
            return cachedFrame;
        }

        private void close() {
            closed = true;
            dirty.clear();
            sections.clear();
            pending.clear();
            queued.clear();
            removedSections.clear();
            fullDetailSectionKeys.clear();
            unknownSections.clear();
            overviewBySection.clear();
            blockEntityPositions.clear();
            blockEntityPreviews = List.of();
        }

        private void markChunkUnloaded(int chunkX, int chunkZ) {
            for (TerrainSectionKey key : sectionKeysForChunk(chunkX, chunkZ, bounds,
                    level.getMinY(), level.getMaxY())) {
                sections.remove(key);
                fullDetailSectionKeys.remove(key);
                overviewBySection.remove(key);
                blockEntityPositions.removeIf(pos -> TerrainSectionKey.fromBlock(
                        pos.getX(), pos.getY(), pos.getZ()).equals(key));
                pending.removeIf(key::equals);
                queued.remove(key);
                removedSections.add(key);
                rememberUnknown(key);
            }
            frameDirty = true;
        }

        private void markChunkLoaded(int chunkX, int chunkZ) {
            for (TerrainSectionKey key : sectionKeysForChunk(chunkX, chunkZ, bounds,
                    level.getMinY(), level.getMaxY())) {
                removedSections.remove(key);
                unknownSections.remove(key);
                overviewBySection.remove(key);
                enqueue(key);
            }
            frameDirty = true;
        }

        private void rememberUnknown(TerrainSectionKey key) {
            if (!bounds.intersects(key) || unknownSections.containsKey(key)) {
                return;
            }
            while (unknownSections.size() >= MAX_UNKNOWN_SECTIONS) {
                var iterator = unknownSections.entrySet().iterator();
                iterator.next();
                iterator.remove();
            }
            unknownSections.put(key, new TerrainOverviewCell(key.minBlockX(), key.minBlockY(),
                    key.minBlockZ(), TerrainSectionKey.SIZE,
                    com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample.RenderCategory.UNKNOWN));
            frameDirty = true;
        }
    }

    static List<TerrainOverviewCell> aggregateOverview(TerrainSectionKey key,
            List<TerrainBlockSectionSnapshot.VisibleBlock> visible, int size) {
        List<com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainOverviewAggregator.Cell> input =
                new ArrayList<>(visible.size());
        for (TerrainBlockSectionSnapshot.VisibleBlock block : visible) {
            input.add(new com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainOverviewAggregator.Cell(
                    block.localX(), block.localY(), block.localZ(),
                    com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample.RenderCategory.MODEL));
        }
        var cells = com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainOverviewAggregator
                .aggregate(input, size);
        List<TerrainOverviewCell> result = new ArrayList<>(cells.size());
        for (var cell : cells) {
            result.add(new TerrainOverviewCell(key.minBlockX() + cell.localX(),
                    key.minBlockY() + cell.localY(), key.minBlockZ() + cell.localZ(), size, cell.material()));
        }
        return List.copyOf(result);
    }

    static Set<TerrainSectionKey> sectionKeysForChunk(int chunkX, int chunkZ, TerrainBounds bounds,
            int levelMinY, int levelMaxY) {
        TerrainSectionKey horizontal = new TerrainSectionKey(chunkX, 0, chunkZ);
        if (horizontal.minBlockX() > bounds.maxX()
            || horizontal.minBlockX() + TerrainSectionKey.SIZE - 1 < bounds.minX()
            || horizontal.minBlockZ() > bounds.maxZ()
            || horizontal.minBlockZ() + TerrainSectionKey.SIZE - 1 < bounds.minZ()) {
            return Set.of();
        }
        int minY = Math.floorDiv(Math.max(bounds.minY(), levelMinY), TerrainSectionKey.SIZE);
        int maxY = Math.floorDiv(Math.min(bounds.maxY(), levelMaxY - 1), TerrainSectionKey.SIZE);
        Set<TerrainSectionKey> keys = new HashSet<>();
        for (int y = minY; y <= maxY; y++) {
            keys.add(new TerrainSectionKey(chunkX, y, chunkZ));
        }
        return Set.copyOf(keys);
    }
}
