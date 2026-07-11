package com.zhongbai233.net_music_can_play_bili.client.pad;

import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 客户端 Pad 地图缓存：分帧采样，禁止在渲染帧里同步扫全图。 */
public final class PadMapClientCache {
    private static final int RESAMPLE_CHUNK_DISTANCE = Integer.getInteger("ncpb.pad.map_resample_chunks", 2);
    private static final int CHUNKS_PER_TICK = Integer.getInteger("ncpb.pad.map_chunks_per_tick", 24);
    private static final int FAST_CHUNKS_PER_TICK = Integer.getInteger("ncpb.pad.map_fast_chunks_per_tick", 64);
    private static final int CELLS_PER_CHUNK_BUDGET = Integer.getInteger("ncpb.pad.map_cells_per_chunk_budget", 32);
    private static final int INITIAL_VISIBLE_BURST_CELLS = Integer.getInteger("ncpb.pad.map_initial_burst_cells",
            8192);
    private static final int MAX_JOB_LAG_CHUNKS = Integer.getInteger("ncpb.pad.map_max_job_lag_chunks", 3);
    private static final int DIRTY_CHUNKS_PER_TICK = Math.max(1,
            Integer.getInteger("ncpb.pad.map_dirty_chunks_per_tick", 4));
    private static final int MAP_UPDATE_INTERVAL_TICKS = Math.max(1,
            Integer.getInteger("ncpb.pad.map_update_interval_ticks", 1));
    private static final int UNKNOWN_RETRY_INTERVAL_TICKS = Math.max(20,
            Integer.getInteger("ncpb.pad.map_unknown_retry_ticks", 40));
    private static final int RECENTER_BLOCKS = Integer.getInteger("ncpb.pad.map_recenter_blocks", 16);
    private static final int INDOOR_RECENTER_BLOCKS = Integer.getInteger("ncpb.pad.map_indoor_recenter_blocks", 8);
    private static final int INDOOR_CEILING_SCAN_BLOCKS = Integer.getInteger("ncpb.pad.map_indoor_ceiling_scan_blocks",
            96);
    private static final int INDOOR_CEILING_MIN_HITS = Integer.getInteger("ncpb.pad.map_indoor_ceiling_min_hits", 5);
    private static final int INDOOR_ARTIFICIAL_MIN_HITS = Integer.getInteger("ncpb.pad.map_indoor_artificial_min_hits",
            5);
    private static final int INDOOR_ENTER_CONFIRM_TICKS = Integer.getInteger("ncpb.pad.map_indoor_enter_confirm_ticks",
            2);
    private static final int INDOOR_EXIT_CONFIRM_TICKS = Integer.getInteger("ncpb.pad.map_indoor_exit_confirm_ticks",
            40);
    private static final int INDOOR_FLOOR_CHANGE_CONFIRM_TICKS = Integer
            .getInteger("ncpb.pad.map_indoor_floor_confirm_ticks", 4);
    private static final int INDOOR_JUMP_TOLERANCE_BLOCKS = Integer
            .getInteger("ncpb.pad.map_indoor_jump_tolerance_blocks", 2);
    private static final float OUTDOOR_ZOOM = Float.parseFloat(System.getProperty("ncpb.pad.map_outdoor_zoom", "1.25"));
    private static final float INDOOR_ZOOM = Float.parseFloat(System.getProperty("ncpb.pad.map_indoor_zoom", "3.0"));
    private static final float INDOOR_DISPLAY_SCALE = Float
            .parseFloat(System.getProperty("ncpb.pad.map_indoor_display_scale", "2.0"));
    private static final int PREVIEW_CHUNKS = Integer.getInteger("ncpb.pad.map_preview_chunks", 1);
    private static final int CELL_CACHE_LIMIT = Integer.getInteger("ncpb.pad.map_cell_cache_limit", 524288);
    private static final int CHUNK_CACHE_LIMIT = Integer.getInteger("ncpb.pad.map_chunk_cache_limit", 8192);
    private static final int DISK_FLUSH_INTERVAL_TICKS = Integer.getInteger("ncpb.pad.map_disk_flush_ticks", 200);
    private static final boolean DISK_CACHE_ENABLED = Boolean
            .parseBoolean(System.getProperty("ncpb.pad.map_disk_cache", "true"));
    private static final ExecutorService DISK_FLUSH_EXECUTOR = Executors.newSingleThreadExecutor(
            NetMusicThreadFactory.daemon("pad-map-disk-flush"));
    private static final LinkedHashMap<CellKey, PadMapTileKind> CELL_CACHE = new LinkedHashMap<>(1024, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CellKey, PadMapTileKind> eldest) {
            return size() > CELL_CACHE_LIMIT;
        }
    };
    private static final LinkedHashMap<ChunkKey, ChunkTile> CHUNK_CACHE = new LinkedHashMap<>(1024, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ChunkKey, ChunkTile> eldest) {
            return size() > CHUNK_CACHE_LIMIT;
        }
    };
    private static final PadMapDirtyChunkTracker DIRTY_CHUNKS = new PadMapDirtyChunkTracker(CHUNK_CACHE_LIMIT);
    private static final PadMapViewSnapshotCache VIEW_SNAPSHOTS = new PadMapViewSnapshotCache();
    private static PadMapSnapshot completed;
    private static PadMapSnapshot placeholder;
    private static Job activeJob;
    private static PadMapViewProfile completedProfile = PadMapViewProfile.OUTDOOR;
    private static boolean manualViewActive;
    private static int manualCenterX;
    private static int manualCenterZ;
    private static float manualZoom = OUTDOOR_ZOOM;
    private static String activeDiskScope;
    private static String syncedWorldScopeId;
    private static boolean diskCacheLoaded;
    private static boolean diskCacheDirty;
    private static boolean snapshotCacheDirty;
    private static int diskFlushTicker;
    private static final PadMapViewProfileStabilizer PROFILE_STABILIZER = new PadMapViewProfileStabilizer(
            INDOOR_ENTER_CONFIRM_TICKS, INDOOR_EXIT_CONFIRM_TICKS, INDOOR_FLOOR_CHANGE_CONFIRM_TICKS,
            INDOOR_JUMP_TOLERANCE_BLOCKS);
    private static final PadMapViewProfileDetector PROFILE_DETECTOR = new PadMapViewProfileDetector(
            INDOOR_CEILING_SCAN_BLOCKS, INDOOR_CEILING_MIN_HITS, INDOOR_ARTIFICIAL_MIN_HITS);
    private static final PadMapJobScheduler JOB_SCHEDULER = new PadMapJobScheduler(RECENTER_BLOCKS,
            INDOOR_RECENTER_BLOCKS, MAX_JOB_LAG_CHUNKS, RESAMPLE_CHUNK_DISTANCE, CHUNKS_PER_TICK,
            FAST_CHUNKS_PER_TICK);
    private static final PadMapSnapshotComposer SNAPSHOT_COMPOSER = new PadMapSnapshotComposer(INDOOR_DISPLAY_SCALE);
    private static int mapUpdateCountdown;
    private static long nextUnknownRetryTick;
    private static Level activeLevel;

    private PadMapClientCache() {
    }

    public static PadMapSnapshot snapshot(int playerX, int playerZ) {
        if (completed == null) {
            return placeholder(playerX, playerZ);
        }
        return completed;
    }

    public static void setManualView(int centerX, int centerZ, float zoom) {
        manualViewActive = true;
        manualCenterX = centerX;
        manualCenterZ = centerZ;
        manualZoom = zoom;
    }

    public static void clearManualView() {
        manualViewActive = false;
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            if (activeLevel != null) {
                flushDiskCache();
                clearLevelRuntimeState();
                activeLevel = null;
            }
            return;
        }
        if (activeLevel != minecraft.level) {
            clearLevelRuntimeState();
            activeLevel = minecraft.level;
        }
        updateDiskScope(minecraft);
        loadDiskCacheIfNeeded();
        if (!isPadRelevant(minecraft)) {
            activeJob = null;
            mapUpdateCountdown = 0;
            maybeFlushDiskCache();
            return;
        }
        if (mapUpdateCountdown > 0) {
            mapUpdateCountdown--;
            maybeFlushDiskCache();
            return;
        }
        mapUpdateCountdown = MAP_UPDATE_INTERVAL_TICKS - 1;
        int playerX = minecraft.player.blockPosition().getX();
        int playerY = minecraft.player.blockPosition().getY();
        int playerZ = minecraft.player.blockPosition().getZ();
        int targetX = manualViewActive ? manualCenterX : playerX;
        int targetZ = manualViewActive ? manualCenterZ : playerZ;
        float targetZoom = manualViewActive ? manualZoom : OUTDOOR_ZOOM;
        BlockPos profilePos = manualViewActive
                ? new BlockPos(targetX, playerY, targetZ)
                : minecraft.player.blockPosition();
        PadMapViewProfile rawProfile = PROFILE_DETECTOR.detect(minecraft.level, profilePos);
        int rawFloorY = rawProfile == PadMapViewProfile.INDOOR
                ? PROFILE_DETECTOR.normalizeIndoorFloorY(minecraft.level, profilePos)
                : PROFILE_DETECTOR.outdoorLayerY();
        PadMapViewProfileStabilizer.Result stableView = PROFILE_STABILIZER.update(rawProfile, rawFloorY);
        PadMapViewProfile profile = stableView.profile();
        int targetY = stableView.floorY();
        if (!manualViewActive) {
            targetZoom = profile == PadMapViewProfile.INDOOR ? INDOOR_ZOOM : OUTDOOR_ZOOM;
        }
        cancelStaleJob(targetX, targetY, targetZ, profile, targetZoom);
        activateViewSnapshot(targetX, targetY, targetZ, profile, targetZoom);
        PadMapSnapshot dirtySeed = invalidateDirtyChunks(minecraft.level, targetY, profile, targetZoom);
        if (dirtySeed != null) {
            activeJob = new Job(minecraft.level, targetX, targetZ, targetY, profile, targetZoom, dirtySeed);
        }
        maybeStartJob(minecraft.level, targetX, targetY, targetZ, profile, targetZoom);
        maybeStartUnknownRetryJob(minecraft.level, targetX, targetY, targetZ, profile, targetZoom);
        publishTransitionIfMissing(targetX, targetY, targetZ, profile, targetZoom);
        if (activeJob != null) {
            long started = System.nanoTime();
            boolean done = activeJob.step(chunksPerTickFor(playerX, playerZ));
            PadPerfLogger.recordSampleStep(System.nanoTime() - started);
            if (done) {
                completed = activeJob.finish();
                completedProfile = activeJob.profile;
                VIEW_SNAPSHOTS.put(completedProfile, completed);
                if (completedProfile == PadMapViewProfile.OUTDOOR && !completed.hasUnknownTiles()) {
                    snapshotCacheDirty = true;
                }
                int steps = activeJob.steps();
                activeJob = null;
                PadPerfLogger.recordSampleJobComplete(steps);
            } else if (activeJob.shouldPublishPreview()) {
                completed = activeJob.preview();
            }
        }
        maybeFlushDiskCache();
    }

    private static void activateViewSnapshot(int playerX, int playerY, int playerZ, PadMapViewProfile profile,
            float zoom) {
        int cellSize = PadMapSampler.cellSizeForZoom(zoom);
        if (completed != null && completedProfile == profile && completed.centerY() == playerY
                && completed.cellSizeBlocks() == cellSize) {
            return;
        }
        PadMapSnapshot cached = VIEW_SNAPSHOTS.get(profile, playerY, cellSize);
        completed = cached;
        completedProfile = profile;
    }

    private static void publishTransitionIfMissing(int playerX, int playerY, int playerZ,
            PadMapViewProfile profile, float zoom) {
        if (completed == null) {
            completed = transitionSnapshot(playerX, playerY, playerZ, profile, zoom);
            completedProfile = profile;
        }
    }

    private static void cancelStaleJob(int playerX, int playerY, int playerZ, PadMapViewProfile profile, float zoom) {
        if (JOB_SCHEDULER.shouldCancel(activeJob, playerX, playerY, playerZ, profile, zoom)) {
            activeJob = null;
        }
    }

    private static int chunksPerTickFor(int playerX, int playerZ) {
        return JOB_SCHEDULER.chunksPerTick(activeJob, playerX, playerZ);
    }

    private static void maybeStartJob(Level level, int playerX, int playerY, int playerZ,
            PadMapViewProfile profile,
            float zoom) {
        if (activeJob != null) {
            return;
        }
        if (!JOB_SCHEDULER.shouldStart(completed, completedProfile, playerX, playerY, playerZ, profile)) {
            return;
        }
        activeJob = new Job(level, playerX, playerZ, playerY, profile, zoom,
                JOB_SCHEDULER.canSeedPrevious(completed, completedProfile, profile, zoom) ? completed : null);
    }

    private static void maybeStartUnknownRetryJob(Level level, int playerX, int playerY, int playerZ,
            PadMapViewProfile profile, float zoom) {
        if (activeJob != null || completed == null || completedProfile != profile || !completed.hasUnknownTiles()) {
            return;
        }
        long gameTime = level.getGameTime();
        if (gameTime < nextUnknownRetryTick) {
            return;
        }
        nextUnknownRetryTick = gameTime + UNKNOWN_RETRY_INTERVAL_TICKS;
        activeJob = new Job(level, playerX, playerZ, playerY, profile, zoom,
                JOB_SCHEDULER.canSeedPrevious(completed, completedProfile, profile, zoom) ? completed : null);
    }

    public static void markChunkDirty(Level level, int chunkX, int chunkZ) {
        if (level == null) {
            return;
        }
        DIRTY_CHUNKS.mark(level.dimension().identifier().toString(), chunkX, chunkZ);
    }

    public static void markBlockDirty(Level level, BlockPos pos) {
        if (pos != null) {
            markChunkDirty(level, Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
        }
    }

    private static PadMapSnapshot invalidateDirtyChunks(Level level, int centerY, PadMapViewProfile profile,
            float zoom) {
        if (activeJob != null || completed == null) {
            return null;
        }
        int cellSize = PadMapSampler.cellSizeForZoom(zoom);
        String dimension = level.dimension().identifier().toString();
        List<PadMapDirtyChunkTracker.Key> dirty = DIRTY_CHUNKS.drainForDimension(dimension, DIRTY_CHUNKS_PER_TICK);
        if (dirty.isEmpty()) {
            return null;
        }
        VIEW_SNAPSHOTS.invalidate(dirty);
        PadMapSnapshot invalidatedSnapshot = PadMapDirtyInvalidation.invalidateSnapshot(completed, cellSize, dirty);
        boolean invalidated = invalidatedSnapshot != null;
        if (profile == PadMapViewProfile.OUTDOOR) {
            List<PadMapDirtyInvalidation.CellRange> dirtyCellRanges = PadMapDirtyInvalidation.cellRanges(dirty,
                    cellSize);
            synchronized (CELL_CACHE) {
                int before = CELL_CACHE.size();
                for (PadMapDirtyInvalidation.CellRange dirtyCellRange : dirtyCellRanges) {
                    for (int cellZ = dirtyCellRange.minCellZ(); cellZ <= dirtyCellRange.maxCellZ(); cellZ++) {
                        for (int cellX = dirtyCellRange.minCellX(); cellX <= dirtyCellRange.maxCellX(); cellX++) {
                            CELL_CACHE.remove(new CellKey(dimension, cellSize, cellX, cellZ));
                        }
                    }
                }
                invalidated |= CELL_CACHE.size() != before;
            }
        }
        if (!invalidated) {
            return null;
        }
        diskCacheDirty = true;
        return invalidatedSnapshot;
    }

    private static void resetProfileStability() {
        PROFILE_STABILIZER.reset();
    }

    private static void clearLevelRuntimeState() {
        activeJob = null;
        completed = null;
        placeholder = null;
        VIEW_SNAPSHOTS.clear();
        DIRTY_CHUNKS.clear();
        mapUpdateCountdown = 0;
        nextUnknownRetryTick = 0L;
        manualViewActive = false;
        resetProfileStability();
    }

    private static boolean isPadRelevant(Minecraft minecraft) {
        if (PadFocusState.active()) {
            return true;
        }
        return minecraft.player.getMainHandItem().getItem() instanceof PadItem
                || minecraft.player.getOffhandItem().getItem() instanceof PadItem;
    }

    private static PadMapSnapshot placeholder(int playerX, int playerZ) {
        if (placeholder != null) {
            return placeholder;
        }
        int size = PadMapSampler.DEFAULT_SIZE;
        int height = PadMapSampler.DEFAULT_HEIGHT;
        PadMapTileKind[] tiles = new PadMapTileKind[size * height];
        java.util.Arrays.fill(tiles, PadMapTileKind.UNKNOWN);
        placeholder = new PadMapSnapshot(Math.floorDiv(playerX, 16) * 16, PROFILE_DETECTOR.outdoorLayerY(),
                Math.floorDiv(playerZ, 16) * 16, 1, size, height, tiles);
        return placeholder;
    }

    private static PadMapSnapshot transitionSnapshot(int playerX, int playerY, int playerZ, PadMapViewProfile profile,
            float zoom) {
        int width = PadMapSampler.DEFAULT_WIDTH;
        int height = PadMapSampler.DEFAULT_HEIGHT;
        int cellSize = PadMapSampler.cellSizeForZoom(zoom);
        PadMapTileKind[] tiles = new PadMapTileKind[width * height];
        java.util.Arrays.fill(tiles, PadMapTileKind.UNKNOWN);
        return SNAPSHOT_COMPOSER.compose(playerX, playerY, playerZ, cellSize, width, height, tiles, profile);
    }

    private static PadMapTileKind cachedClassify(Level level, BlockPos.MutableBlockPos mutable, String dimension,
            int worldX, int worldZ, int cellSize) {
        int cellX = Math.floorDiv(worldX, cellSize);
        int cellZ = Math.floorDiv(worldZ, cellSize);
        CellKey key = new CellKey(dimension, cellSize, cellX, cellZ);
        synchronized (CELL_CACHE) {
            PadMapTileKind cached = CELL_CACHE.get(key);
            if (cached != null) {
                PadPerfLogger.recordCellCacheHit();
                return cached;
            }
        }
        PadPerfLogger.recordCellCacheMiss();
        PadMapTileKind kind = PadMapSampler.classifyCell(level, mutable, cellX * cellSize, cellZ * cellSize,
                cellSize);
        if (kind == PadMapTileKind.UNKNOWN) {
            return kind;
        }
        synchronized (CELL_CACHE) {
            CELL_CACHE.put(key, kind);
        }
        diskCacheDirty = true;
        return kind;
    }

    public static int memoryCacheSize() {
        synchronized (CELL_CACHE) {
            return CELL_CACHE.size();
        }
    }

    public static String describeStatus() {
        Job job = activeJob;
        int dirtySize = DIRTY_CHUNKS.size();
        String completedInfo = completed == null
                ? "completed=none"
                : "completed=" + completed.width() + "x" + completed.height()
                        + " cell=" + completed.cellSizeBlocks()
                        + " center=(" + completed.centerX() + "," + completed.centerZ() + ")"
                        + " profile=" + completedProfile;
        String jobInfo = job == null ? "job=none" : job.describe();
        return completedInfo
                + ", " + jobInfo
                + ", memoryCells=" + memoryCacheSize()
                + ", dirtyChunks=" + dirtySize
                + ", cellBudget=" + (CHUNKS_PER_TICK * CELLS_PER_CHUNK_BUDGET) + "/"
                + (FAST_CHUNKS_PER_TICK * CELLS_PER_CHUNK_BUDGET)
                + ", initialBurst=" + INITIAL_VISIBLE_BURST_CELLS
                + ", recenter=" + RECENTER_BLOCKS
                + ", disk=" + diskCachePath();
    }

    public static void setServerWorldScope(String worldScopeId, String worldName) {
        String normalizedScopeId = isBlank(worldScopeId) ? null : worldScopeId.trim();
        if (java.util.Objects.equals(syncedWorldScopeId, normalizedScopeId)) {
            return;
        }
        flushDiskCache();
        syncedWorldScopeId = normalizedScopeId;
        clearAllCaches(false);
        activeDiskScope = null;
        diskCacheLoaded = false;
    }

    public static Path diskCachePath() {
        return PadMapDiskCachePaths.cells(Minecraft.getInstance(), syncedWorldScopeId);
    }

    public static Path snapshotCachePath() {
        return PadMapDiskCachePaths.snapshot(Minecraft.getInstance(), syncedWorldScopeId);
    }

    public static void flushDiskCache() {
        if (!DISK_CACHE_ENABLED || !diskCacheLoaded || !hasSyncedDiskScope()) {
            return;
        }
        PadMapCellDiskCodec.Snapshot cells = captureCellDiskSnapshot();
        PadMapChunkDiskCodec.Snapshot chunks = captureChunkDiskSnapshot();
        PadMapSnapshotDiskCodec.Snapshot snapshot = captureSnapshotDiskSnapshot();
        if (cells == null && chunks == null && snapshot == null) {
            return;
        }
        DISK_FLUSH_EXECUTOR.execute(() -> {
            PadMapCellDiskCodec.write(cells);
            PadMapChunkDiskCodec.write(chunks);
            PadMapSnapshotDiskCodec.write(snapshot);
        });
    }

    private static PadMapCellDiskCodec.Snapshot captureCellDiskSnapshot() {
        if (!diskCacheDirty) {
            return null;
        }
        Path path = diskCachePath();
        List<PadMapCellDiskCodec.Entry> entries = new ArrayList<>();
        synchronized (CELL_CACHE) {
            for (Map.Entry<CellKey, PadMapTileKind> entry : CELL_CACHE.entrySet()) {
                if (entry.getValue() != PadMapTileKind.UNKNOWN) {
                    CellKey key = entry.getKey();
                    entries.add(new PadMapCellDiskCodec.Entry(key.dimension(), key.cellSize(), key.cellX(),
                            key.cellZ(), entry.getValue()));
                }
            }
        }
        diskCacheDirty = false;
        return new PadMapCellDiskCodec.Snapshot(path, entries);
    }

    private static PadMapSnapshotDiskCodec.Snapshot captureSnapshotDiskSnapshot() {
        if (!snapshotCacheDirty || completed == null || completed.tiles() == null || completed.hasUnknownTiles()) {
            return null;
        }
        Path path = snapshotCachePath();
        PadMapSnapshot snapshot = completed;
        PadMapTileKind[] tiles = java.util.Arrays.copyOf(snapshot.tiles(), snapshot.tiles().length);
        snapshotCacheDirty = false;
        return new PadMapSnapshotDiskCodec.Snapshot(path, snapshot.centerX(), snapshot.centerY(), snapshot.centerZ(),
                snapshot.cellSizeBlocks(), snapshot.width(), snapshot.height(), tiles);
    }

    private static PadMapChunkDiskCodec.Snapshot captureChunkDiskSnapshot() {
        return null;
    }

    public static int clearAllCaches(boolean deleteDisk) {
        activeJob = null;
        completed = null;
        placeholder = null;
        VIEW_SNAPSHOTS.clear();
        DIRTY_CHUNKS.clear();
        resetProfileStability();
        nextUnknownRetryTick = 0L;
        int previousSize;
        synchronized (CELL_CACHE) {
            previousSize = CELL_CACHE.size();
            CELL_CACHE.clear();
        }
        synchronized (CHUNK_CACHE) {
            CHUNK_CACHE.clear();
        }
        diskCacheLoaded = true;
        diskCacheDirty = false;
        snapshotCacheDirty = false;
        if (deleteDisk) {
            try {
                Files.deleteIfExists(diskCachePath());
                Files.deleteIfExists(snapshotCachePath());
                Files.deleteIfExists(chunkCachePath());
            } catch (IOException ignored) {
            }
        }
        return previousSize;
    }

    static void clearMemorySnapshots() {
        activeJob = null;
        completed = null;
        placeholder = null;
        VIEW_SNAPSHOTS.clear();
        DIRTY_CHUNKS.clear();
        resetProfileStability();
        nextUnknownRetryTick = 0L;
    }

    private static void maybeFlushDiskCache() {
        if (++diskFlushTicker >= DISK_FLUSH_INTERVAL_TICKS) {
            diskFlushTicker = 0;
            flushDiskCache();
        }
    }

    private static void loadDiskCacheIfNeeded() {
        if (!DISK_CACHE_ENABLED || diskCacheLoaded || !hasSyncedDiskScope()) {
            return;
        }
        diskCacheLoaded = true;
        loadSnapshotDiskCache();
        Path path = diskCachePath();
        List<PadMapCellDiskCodec.Entry> entries = PadMapCellDiskCodec.read(path, CELL_CACHE_LIMIT);
        if (entries == null) {
            return;
        }
        synchronized (CELL_CACHE) {
            CELL_CACHE.clear();
            for (PadMapCellDiskCodec.Entry entry : entries) {
                CELL_CACHE.put(new CellKey(entry.dimension(), entry.cellSize(), entry.cellX(), entry.cellZ()),
                        entry.kind());
            }
        }
    }

    private static void loadSnapshotDiskCache() {
        Path path = snapshotCachePath();
        PadMapSnapshot snapshot = PadMapSnapshotDiskCodec.read(path, PadMapSampler.DEFAULT_WIDTH,
                PadMapSampler.DEFAULT_HEIGHT, PadMapSampler.cellSizeForZoom(OUTDOOR_ZOOM));
        if (snapshot != null) {
            completed = snapshot;
            completedProfile = PadMapViewProfile.OUTDOOR;
            VIEW_SNAPSHOTS.put(PadMapViewProfile.OUTDOOR, snapshot);
        }
    }

    public static Path chunkCachePath() {
        return PadMapDiskCachePaths.chunks(Minecraft.getInstance(), syncedWorldScopeId);
    }

    private static void updateDiskScope(Minecraft minecraft) {
        String scope = currentDiskScope(minecraft);
        if (scope.equals(activeDiskScope)) {
            return;
        }
        flushDiskCache();
        activeDiskScope = scope;
        activeJob = null;
        completed = null;
        placeholder = null;
        VIEW_SNAPSHOTS.clear();
        DIRTY_CHUNKS.clear();
        resetProfileStability();
        nextUnknownRetryTick = 0L;
        synchronized (CELL_CACHE) {
            CELL_CACHE.clear();
        }
        synchronized (CHUNK_CACHE) {
            CHUNK_CACHE.clear();
        }
        diskCacheLoaded = false;
        diskCacheDirty = false;
        snapshotCacheDirty = false;
    }

    private static boolean hasSyncedDiskScope() {
        return !isBlank(syncedWorldScopeId);
    }

    private static String currentDiskScope(Minecraft minecraft) {
        return PadMapDiskCachePaths.worldScope(syncedWorldScopeId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static final class Job {
        private final Level level;
        private final int centerX;
        private final int centerZ;
        private final int floorY;
        private final PadMapViewProfile profile;
        private final int size;
        private final int height;
        private final int cellSize;
        private final String dimension;
        private final float zoom;
        private final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        private final PadMapTileKind[] tiles;
        private final List<PadMapSamplePlan.Cell> pendingCells;
        private final int previewReadyCells;
        private final PadMapJobProgress progress;

        Job(Level level, int centerX, int centerZ, int playerY, PadMapViewProfile profile, float zoom,
                PadMapSnapshot previous) {
            this.level = level;
            this.zoom = zoom;
            this.cellSize = PadMapSampler.cellSizeForZoom(zoom);
            this.dimension = level == null ? "" : level.dimension().identifier().toString();
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.floorY = playerY;
            this.profile = profile;
            this.size = PadMapSampler.DEFAULT_SIZE;
            this.height = PadMapSampler.DEFAULT_HEIGHT;
            this.tiles = new PadMapTileKind[size * height];
            java.util.Arrays.fill(this.tiles, PadMapTileKind.UNKNOWN);
            PadMapSnapshotSeeder.seed(previous, tiles, centerX, floorY, centerZ, cellSize, size, height);
            this.pendingCells = PadMapSamplePlan.collectPendingCells(centerX, centerZ, cellSize, size, height,
                    PadMapSampler.DEFAULT_VIEW_WIDTH, PadMapSampler.DEFAULT_VIEW_HEIGHT, tiles);
            if (level != null) {
                this.pendingCells.removeIf(cell -> !PadMapSampler.isCellReady(level, cell.worldX(), cell.worldZ(),
                        cellSize));
            }
            this.previewReadyCells = previewReadyCells(pendingCells);
            this.progress = new PadMapJobProgress(pendingCells.size(), CELLS_PER_CHUNK_BUDGET,
                    INITIAL_VISIBLE_BURST_CELLS, PREVIEW_CHUNKS);
        }

        private boolean step(int chunkBudget) {
            PadMapJobProgress.Step step = progress.beginStep(chunkBudget);
            for (int i = step.startInclusive(); i < step.endExclusive(); i++) {
                PadMapSamplePlan.Cell cell = pendingCells.get(i);
                tiles[cell.index()] = sampleCell(cell.worldX(), cell.worldZ());
            }
            return progress.done();
        }

        private boolean shouldPublishPreview() {
            return progress.doneCells() >= previewReadyCells && progress.shouldPublishPreview();
        }

        private static int previewReadyCells(List<PadMapSamplePlan.Cell> cells) {
            int count = 0;
            for (PadMapSamplePlan.Cell cell : cells) {
                if (cell.priority() <= 0) {
                    count++;
                }
            }
            return Math.max(1, count);
        }

        private PadMapSnapshot preview() {
            progress.markPreviewPublished();
            return SNAPSHOT_COMPOSER.compose(centerX, floorY, centerZ, cellSize, size, height, tiles, profile);
        }

        private PadMapTileKind sampleCell(int worldX, int worldZ) {
            return profile == PadMapViewProfile.INDOOR
                    ? PadMapSampler.classifyInteriorCell(level, mutable, worldX, worldZ, floorY, cellSize)
                    : cachedClassify(level, mutable, dimension, worldX, worldZ, cellSize);
        }

        private PadMapSnapshot finish() {
            return SNAPSHOT_COMPOSER.compose(centerX, floorY, centerZ, cellSize, size, height, tiles, profile);
        }

        private int steps() {
            return progress.steps();
        }

        int centerX() {
            return centerX;
        }

        int centerZ() {
            return centerZ;
        }

        int floorY() {
            return floorY;
        }

        PadMapViewProfile profile() {
            return profile;
        }

        float zoom() {
            return zoom;
        }

        private String describe() {
            return "job=" + profile
                    + " " + progress.doneCells() + "/" + progress.totalCells() + " cells " + progress.percent() + "%"
                    + " center=(" + centerX + "," + centerZ + ")"
                    + " cell=" + cellSize
                    + " steps=" + progress.steps();
        }
    }

    private static final class ChunkTile {
        private final PadMapTileKind[] tiles = new PadMapTileKind[16 * 16];

        private ChunkTile() {
            java.util.Arrays.fill(tiles, PadMapTileKind.UNKNOWN);
        }
    }

    private record CellKey(String dimension, int cellSize, int cellX, int cellZ) {
        @Override
        public int hashCode() {
            int hash = dimension.hashCode();
            hash = 31 * hash + cellSize;
            hash ^= Integer.rotateLeft(mix(cellX), 11);
            hash ^= Integer.rotateLeft(mix(cellZ), 23);
            return mix(hash);
        }

        private static int mix(int value) {
            value ^= value >>> 16;
            value *= 0x7FEB352D;
            value ^= value >>> 15;
            value *= 0x846CA68B;
            return value ^ value >>> 16;
        }
    }

    private record ChunkKey(String dimension, int chunkX, int chunkZ, int cellSize, int floorY,
            PadMapViewProfile profile) {
    }

}
