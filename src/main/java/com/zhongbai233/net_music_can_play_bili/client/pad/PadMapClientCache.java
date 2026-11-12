package com.zhongbai233.net_music_can_play_bili.client.pad;

import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;

import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 客户端 Pad 地图缓存：分帧采样，禁止在渲染帧里同步扫全图。 */
public final class PadMapClientCache {
    private static final PadMapProperties.Cache PROPERTIES = PadMapProperties.cache();
    private static final int RESAMPLE_CHUNK_DISTANCE = PROPERTIES.resampleChunkDistance();
    private static final int CHUNKS_PER_TICK = PROPERTIES.chunksPerTick();
    private static final int FAST_CHUNKS_PER_TICK = PROPERTIES.fastChunksPerTick();
    private static final int CELLS_PER_CHUNK_BUDGET = PROPERTIES.cellsPerChunkBudget();
    private static final int INITIAL_VISIBLE_BURST_CELLS = PROPERTIES.initialVisibleBurstCells();
    private static final int MAX_JOB_LAG_CHUNKS = PROPERTIES.maxJobLagChunks();
    private static final int DIRTY_CHUNKS_PER_TICK = PROPERTIES.dirtyChunksPerTick();
    private static final int MAP_UPDATE_INTERVAL_TICKS = PROPERTIES.updateIntervalTicks();
    private static final int UNKNOWN_RETRY_INTERVAL_TICKS = PROPERTIES.unknownRetryTicks();
    private static final int RECENTER_BLOCKS = PROPERTIES.recenterBlocks();
    private static final int INDOOR_RECENTER_BLOCKS = PROPERTIES.indoorRecenterBlocks();
    private static final int INDOOR_CEILING_SCAN_BLOCKS = PROPERTIES.indoorCeilingScanBlocks();
    private static final int INDOOR_CEILING_MIN_HITS = PROPERTIES.indoorCeilingMinHits();
    private static final int INDOOR_ARTIFICIAL_MIN_HITS = PROPERTIES.indoorArtificialMinHits();
    private static final int INDOOR_ENTER_CONFIRM_TICKS = PROPERTIES.indoorEnterConfirmTicks();
    private static final int INDOOR_EXIT_CONFIRM_TICKS = PROPERTIES.indoorExitConfirmTicks();
    private static final int INDOOR_FLOOR_CHANGE_CONFIRM_TICKS = PROPERTIES.indoorFloorConfirmTicks();
    private static final int INDOOR_JUMP_TOLERANCE_BLOCKS = PROPERTIES.indoorJumpToleranceBlocks();
    private static final float OUTDOOR_ZOOM = PROPERTIES.outdoorZoom();
    private static final float INDOOR_ZOOM = PROPERTIES.indoorZoom();
    private static final float INDOOR_DISPLAY_SCALE = PROPERTIES.indoorDisplayScale();
    private static final int PREVIEW_CHUNKS = PROPERTIES.previewChunks();
    private static final int CELL_CACHE_LIMIT = PROPERTIES.cellCacheLimit();
    private static final int DIRTY_CHUNK_LIMIT = PROPERTIES.dirtyChunkLimit();
    private static final int DISK_FLUSH_INTERVAL_TICKS = PROPERTIES.diskFlushTicks();
    private static final boolean DISK_CACHE_ENABLED = PROPERTIES.diskCacheEnabled();
    private static final ExecutorService DISK_FLUSH_EXECUTOR = Executors.newSingleThreadExecutor(
            NetMusicThreadFactory.daemon("pad-map-disk-flush"));
    private static final AtomicBoolean DISK_FLUSH_IN_PROGRESS = new AtomicBoolean(false);
    private static final Object DISK_FLUSH_LOCK = new Object();
    // One writer plus at most two queued immutable captures; a full queue retains the live dirty owner.
    private static final int MAX_PENDING_DISK_FLUSHES = 2;
    private static final ArrayDeque<DiskFlushBatch> PENDING_DISK_FLUSHES = new ArrayDeque<>();
    private static DiskFlushBatch writingDiskFlush;
    private static final PadMapCellMemoryCache CELL_CACHE = new PadMapCellMemoryCache(CELL_CACHE_LIMIT);
    private static final PadMapDirtyChunkTracker DIRTY_CHUNKS = new PadMapDirtyChunkTracker(DIRTY_CHUNK_LIMIT);
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
    // Bind destinations before sampling: Minecraft.level may already be the next dimension at flush time.
    private static Path activeDiskWorldRoot;
    private static Path activeCellDiskPath;
    private static Path activeSnapshotDiskPath;
    private static Level snapshotLoadedLevel;
    private static Path snapshotLoadedPath;
    private static boolean contextTransitionBlocked;
    private static boolean worldScopeChangePending;
    private static String pendingWorldScopeId;
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
        if (contextTransitionBlocked || completed == null) {
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
        if (worldScopeChangePending && !applyPendingWorldScope()) {
            return;
        }
        if (minecraft.player == null || minecraft.level == null) {
            if (activeLevel != null) {
                if (!flushBeforeContextChange()) {
                    blockContextTransition();
                    return;
                }
                clearLevelRuntimeState();
                invalidateSnapshotLoad();
                activeLevel = null;
            }
            contextTransitionBlocked = false;
            return;
        }
        if (!updateDiskScope(minecraft)) {
            return;
        }
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
        int cellSize = PadMapSamplingPolicy.cellSizeForZoom(zoom);
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
        if (JOB_SCHEDULER.shouldCancel(schedulerView(activeJob), playerX, playerY, playerZ, profile, zoom)) {
            activeJob = null;
        }
    }

    private static int chunksPerTickFor(int playerX, int playerZ) {
        return JOB_SCHEDULER.chunksPerTick(schedulerView(activeJob), playerX, playerZ);
    }

    private static PadMapJobScheduler.JobView schedulerView(Job job) {
        return job == null ? null : job.schedulerView();
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
        long gameTime = MonotonicMediaClock.nowTick();
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
        int cellSize = PadMapSamplingPolicy.cellSizeForZoom(zoom);
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
            int before = CELL_CACHE.size();
            for (PadMapDirtyInvalidation.CellRange dirtyCellRange : dirtyCellRanges) {
                for (int cellZ = dirtyCellRange.minCellZ(); cellZ <= dirtyCellRange.maxCellZ(); cellZ++) {
                    for (int cellX = dirtyCellRange.minCellX(); cellX <= dirtyCellRange.maxCellX(); cellX++) {
                        CELL_CACHE.remove(new PadMapCellMemoryCache.Key(dimension, cellSize, cellX, cellZ));
                    }
                }
            }
            invalidated |= CELL_CACHE.size() != before;
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
        int cellSize = PadMapSamplingPolicy.cellSizeForZoom(zoom);
        PadMapTileKind[] tiles = new PadMapTileKind[width * height];
        java.util.Arrays.fill(tiles, PadMapTileKind.UNKNOWN);
        return SNAPSHOT_COMPOSER.compose(playerX, playerY, playerZ, cellSize, width, height, tiles, profile);
    }

    private static PadMapTileKind cachedClassify(Level level, BlockPos.MutableBlockPos mutable, String dimension,
            int worldX, int worldZ, int cellSize) {
        int cellX = Math.floorDiv(worldX, cellSize);
        int cellZ = Math.floorDiv(worldZ, cellSize);
        PadMapCellMemoryCache.Key key = new PadMapCellMemoryCache.Key(dimension, cellSize, cellX, cellZ);
        PadMapTileKind cached = CELL_CACHE.get(key);
        if (cached != null) {
            PadPerfLogger.recordCellCacheHit();
            return cached;
        }
        PadPerfLogger.recordCellCacheMiss();
        PadMapTileKind kind = PadMapSampler.classifyCell(level, mutable, cellX * cellSize, cellZ * cellSize,
                cellSize);
        if (kind == PadMapTileKind.UNKNOWN) {
            return kind;
        }
        CELL_CACHE.put(key, kind);
        diskCacheDirty = true;
        return kind;
    }

    public static int memoryCacheSize() {
        return CELL_CACHE.size();
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
        if (Objects.equals(syncedWorldScopeId, normalizedScopeId)) {
            worldScopeChangePending = false;
            pendingWorldScopeId = null;
            return;
        }
        pendingWorldScopeId = normalizedScopeId;
        worldScopeChangePending = true;
        applyPendingWorldScope();
    }

    private static boolean applyPendingWorldScope() {
        // Do not publish null/the next ID until the old bound dirty payload has been admitted.
        if (!flushBeforeContextChange()) {
            blockContextTransition();
            return false;
        }
        syncedWorldScopeId = pendingWorldScopeId;
        worldScopeChangePending = false;
        pendingWorldScopeId = null;
        clearAllCaches(false);
        activeDiskScope = null;
        activeDiskWorldRoot = null;
        activeCellDiskPath = null;
        activeSnapshotDiskPath = null;
        invalidateSnapshotLoad();
        diskCacheLoaded = false;
        contextTransitionBlocked = false;
        return true;
    }

    public static Path diskCachePath() {
        return PadMapDiskCachePaths.cells(Minecraft.getInstance(), syncedWorldScopeId);
    }

    public static Path snapshotCachePath() {
        return PadMapDiskCachePaths.snapshot(Minecraft.getInstance(), syncedWorldScopeId);
    }

    public static void flushDiskCache() {
        enqueueDiskFlush(false);
    }

    private static boolean flushBeforeContextChange() {
        return enqueueDiskFlush(true);
    }

    private static boolean enqueueDiskFlush(boolean allowBusy) {
        // Eligibility belongs to the previous bound owner, never to a newly null/different Minecraft.level.
        if (!DISK_CACHE_ENABLED || !diskCacheLoaded || activeCellDiskPath == null
                || activeSnapshotDiskPath == null || isBlank(syncedWorldScopeId)) {
            return true;
        }
        synchronized (DISK_FLUSH_LOCK) {
            if (!diskCacheDirty && (!snapshotCacheDirty || completed == null || completed.hasUnknownTiles())) {
                return true;
            }
            if (DISK_FLUSH_IN_PROGRESS.get() && !allowBusy) {
                return false;
            }
            DiskFlushBatch previousTail = PENDING_DISK_FLUSHES.peekLast();
            boolean coalesce = previousTail != null && previousTail.matches(activeCellDiskPath,
                    activeSnapshotDiskPath);
            if (!coalesce && PENDING_DISK_FLUSHES.size() >= MAX_PENDING_DISK_FLUSHES) {
                return false;
            }
            PadMapCellDiskCodec.Snapshot cells = captureCellDiskSnapshot();
            PadMapSnapshotDiskCodec.Snapshot snapshot = captureSnapshotDiskSnapshot();
            if (cells == null && snapshot == null) {
                return true;
            }
            DiskFlushBatch batch = new DiskFlushBatch(activeCellDiskPath, activeSnapshotDiskPath, cells, snapshot);
            if (coalesce) {
                PENDING_DISK_FLUSHES.removeLast();
                batch = previousTail.merge(batch);
            }
            PENDING_DISK_FLUSHES.addLast(batch);
            if (!DISK_FLUSH_IN_PROGRESS.get()) {
                DISK_FLUSH_IN_PROGRESS.set(true);
                try {
                    DISK_FLUSH_EXECUTOR.execute(PadMapClientCache::drainDiskFlushes);
                } catch (RuntimeException error) {
                    PENDING_DISK_FLUSHES.removeLast();
                    if (coalesce) {
                        PENDING_DISK_FLUSHES.addLast(previousTail);
                    }
                    DISK_FLUSH_IN_PROGRESS.set(false);
                    diskCacheDirty |= cells != null;
                    snapshotCacheDirty |= snapshot != null;
                    throw error;
                }
            }
            return true;
        }
    }

    private static void drainDiskFlushes() {
        Throwable failure = null;
        for (;;) {
            DiskFlushBatch batch;
            synchronized (DISK_FLUSH_LOCK) {
                batch = PENDING_DISK_FLUSHES.pollFirst();
                if (batch == null) {
                    DISK_FLUSH_IN_PROGRESS.set(false);
                    break;
                }
                writingDiskFlush = batch;
            }
            // A failed worker must still retire its queued owners. Codec IOException handling is unchanged.
            try {
                PadMapCellDiskCodec.write(batch.cells);
            } catch (RuntimeException | Error error) {
                failure = collectFlushFailure(failure, error);
            }
            try {
                PadMapSnapshotDiskCodec.write(batch.snapshot);
            } catch (RuntimeException | Error error) {
                failure = collectFlushFailure(failure, error);
            } finally {
                synchronized (DISK_FLUSH_LOCK) {
                    // Explicit refresh may race an already running write; delete that old result before newer work.
                    try {
                        if (batch.discardCells) {
                            deleteDiskFile(batch.cellPath);
                        }
                        if (batch.discardSnapshot) {
                            deleteDiskFile(batch.snapshotPath);
                        }
                    } catch (RuntimeException | Error error) {
                        failure = collectFlushFailure(failure, error);
                    } finally {
                        writingDiskFlush = null;
                    }
                }
            }
        }
        if (failure instanceof RuntimeException error) {
            throw error;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static Throwable collectFlushFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static final class DiskFlushBatch {
        private final Path cellPath;
        private final Path snapshotPath;
        private final PadMapCellDiskCodec.Snapshot cells;
        private final PadMapSnapshotDiskCodec.Snapshot snapshot;
        // Only refresh cancellation mutates these flags; captured destinations and payloads never change.
        private boolean discardCells;
        private boolean discardSnapshot;

        private DiskFlushBatch(Path cellPath, Path snapshotPath, PadMapCellDiskCodec.Snapshot cells,
                PadMapSnapshotDiskCodec.Snapshot snapshot) {
            this.cellPath = cellPath;
            this.snapshotPath = snapshotPath;
            this.cells = cells;
            this.snapshot = snapshot;
        }

        private boolean matches(Path cells, Path snapshot) {
            return cellPath.equals(cells) && snapshotPath.equals(snapshot);
        }

        private DiskFlushBatch merge(DiskFlushBatch newer) {
            return new DiskFlushBatch(cellPath, snapshotPath, newer.cells == null ? cells : newer.cells,
                    newer.snapshot == null ? snapshot : newer.snapshot);
        }
    }

    private static PadMapCellDiskCodec.Snapshot captureCellDiskSnapshot() {
        if (!diskCacheDirty) {
            return null;
        }
        Path path = activeCellDiskPath;
        List<PadMapCellDiskCodec.Entry> entries = new ArrayList<>();
        for (PadMapCellMemoryCache.Entry entry : CELL_CACHE.entries()) {
            if (entry.kind() != PadMapTileKind.UNKNOWN) {
                PadMapCellMemoryCache.Key key = entry.key();
                entries.add(new PadMapCellDiskCodec.Entry(key.dimension(), key.cellSize(), key.cellX(),
                        key.cellZ(), entry.kind()));
            }
        }
        diskCacheDirty = false;
        return new PadMapCellDiskCodec.Snapshot(path, List.copyOf(entries));
    }

    private static PadMapSnapshotDiskCodec.Snapshot captureSnapshotDiskSnapshot() {
        if (!snapshotCacheDirty || completed == null || completed.tiles() == null || completed.hasUnknownTiles()) {
            return null;
        }
        Path path = activeSnapshotDiskPath;
        PadMapSnapshot snapshot = completed;
        PadMapTileKind[] tiles = java.util.Arrays.copyOf(snapshot.tiles(), snapshot.tiles().length);
        snapshotCacheDirty = false;
        return new PadMapSnapshotDiskCodec.Snapshot(path, snapshot.centerX(), snapshot.centerY(), snapshot.centerZ(),
                snapshot.cellSizeBlocks(), snapshot.width(), snapshot.height(), tiles);
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
        previousSize = CELL_CACHE.size();
        CELL_CACHE.clear();
        diskCacheLoaded = true;
        diskCacheDirty = false;
        snapshotCacheDirty = false;
        snapshotLoadedLevel = activeLevel;
        snapshotLoadedPath = activeSnapshotDiskPath;
        if (deleteDisk) {
            Path cells = diskCachePath().toAbsolutePath().normalize();
            Path snapshot = snapshotCachePath().toAbsolutePath().normalize();
            discardQueuedDiskWrites(cells, snapshot);
            try {
                Files.deleteIfExists(cells);
                Files.deleteIfExists(snapshot);
            } catch (IOException ignored) {
            }
        }
        return previousSize;
    }

    static void clearMemorySnapshots() {
        snapshotLoadedLevel = activeLevel;
        snapshotLoadedPath = activeSnapshotDiskPath;
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
        if (!DISK_CACHE_ENABLED || !hasSyncedDiskScope() || contextTransitionBlocked) {
            return;
        }
        if (!diskCacheLoaded) {
            diskCacheLoaded = true;
            // Cell population is world-owned, and dimension-keyed entries survive a level replacement.
            List<PadMapCellDiskCodec.Entry> entries = pendingCellEntries(activeCellDiskPath);
            if (entries == null && !discardedWriteInProgress(activeCellDiskPath)) {
                entries = PadMapCellDiskCodec.read(activeCellDiskPath, CELL_CACHE_LIMIT);
            }
            if (entries != null) {
                CELL_CACHE.clear();
                for (PadMapCellDiskCodec.Entry entry : entries) {
                    CELL_CACHE.put(new PadMapCellMemoryCache.Key(entry.dimension(), entry.cellSize(), entry.cellX(),
                            entry.cellZ()), entry.kind());
                }
            }
        }
        // Snapshot IO has a separate one-attempt stamp; it must not be gated by the world cell flag.
        if (snapshotLoadedLevel != activeLevel || !Objects.equals(snapshotLoadedPath, activeSnapshotDiskPath)) {
            snapshotLoadedLevel = activeLevel;
            snapshotLoadedPath = activeSnapshotDiskPath;
            loadSnapshotDiskCache();
        }
    }

    private static List<PadMapCellDiskCodec.Entry> pendingCellEntries(Path path) {
        synchronized (DISK_FLUSH_LOCK) {
            var newestFirst = PENDING_DISK_FLUSHES.descendingIterator();
            while (newestFirst.hasNext()) {
                DiskFlushBatch batch = newestFirst.next();
                if (batch.cells != null && batch.cellPath.equals(path)) {
                    return batch.cells.entries();
                }
            }
            if (writingDiskFlush != null && !writingDiskFlush.discardCells && writingDiskFlush.cells != null
                    && writingDiskFlush.cellPath.equals(path)) {
                return writingDiskFlush.cells.entries();
            }
            return null;
        }
    }

    private static void loadSnapshotDiskCache() {
        PadMapSnapshot snapshot = pendingMapSnapshot(activeSnapshotDiskPath);
        if (snapshot == null && !discardedWriteInProgress(activeSnapshotDiskPath)) {
            snapshot = PadMapSnapshotDiskCodec.read(activeSnapshotDiskPath, PadMapSampler.DEFAULT_WIDTH,
                    PadMapSampler.DEFAULT_HEIGHT, PadMapSamplingPolicy.cellSizeForZoom(OUTDOOR_ZOOM));
        }
        if (snapshot != null) {
            completed = snapshot;
            completedProfile = PadMapViewProfile.OUTDOOR;
            VIEW_SNAPSHOTS.put(PadMapViewProfile.OUTDOOR, snapshot);
        }
    }

    private static PadMapSnapshot pendingMapSnapshot(Path path) {
        synchronized (DISK_FLUSH_LOCK) {
            var newestFirst = PENDING_DISK_FLUSHES.descendingIterator();
            while (newestFirst.hasNext()) {
                DiskFlushBatch batch = newestFirst.next();
                if (batch.snapshot != null && batch.snapshotPath.equals(path)) {
                    return copyCapturedSnapshot(batch.snapshot);
                }
            }
            if (writingDiskFlush != null && !writingDiskFlush.discardSnapshot && writingDiskFlush.snapshot != null
                    && writingDiskFlush.snapshotPath.equals(path)) {
                return copyCapturedSnapshot(writingDiskFlush.snapshot);
            }
            return null;
        }
    }

    private static PadMapSnapshot copyCapturedSnapshot(PadMapSnapshotDiskCodec.Snapshot snapshot) {
        return new PadMapSnapshot(snapshot.centerX(), snapshot.centerY(), snapshot.centerZ(),
                snapshot.cellSizeBlocks(), snapshot.width(), snapshot.height(),
                java.util.Arrays.copyOf(snapshot.tiles(), snapshot.tiles().length));
    }

    private static boolean updateDiskScope(Minecraft minecraft) {
        String scope = currentDiskScope(minecraft);
        Path cells = diskCachePath().toAbsolutePath().normalize();
        Path snapshot = snapshotCachePath().toAbsolutePath().normalize();
        Path worldRoot = cells.getParent().getParent();
        boolean worldChanged = !scope.equals(activeDiskScope) || !worldRoot.equals(activeDiskWorldRoot);
        if (!worldChanged && activeLevel == minecraft.level && snapshot.equals(activeSnapshotDiskPath)) {
            contextTransitionBlocked = false;
            return true;
        }
        // All captures below still use old bound paths, even though Minecraft.level is already the new object.
        if (!flushBeforeContextChange()) {
            blockContextTransition();
            return false;
        }
        clearLevelRuntimeState();
        invalidateSnapshotLoad();
        activeLevel = minecraft.level;
        activeDiskScope = scope;
        activeDiskWorldRoot = worldRoot;
        activeCellDiskPath = cells;
        activeSnapshotDiskPath = snapshot;
        snapshotCacheDirty = false;
        if (worldChanged) {
            CELL_CACHE.clear();
            diskCacheLoaded = false;
            diskCacheDirty = false;
        }
        contextTransitionBlocked = false;
        return true;
    }

    private static void blockContextTransition() {
        contextTransitionBlocked = true;
        activeJob = null;
        placeholder = null;
    }

    private static void invalidateSnapshotLoad() {
        snapshotLoadedLevel = null;
        snapshotLoadedPath = null;
    }

    private static void discardQueuedDiskWrites(Path cells, Path snapshot) {
        synchronized (DISK_FLUSH_LOCK) {
            var batches = PENDING_DISK_FLUSHES.iterator();
            while (batches.hasNext()) {
                DiskFlushBatch batch = batches.next();
                // The two paths always identify the same dimension's refresh target.
                if (batch.matches(cells, snapshot)) {
                    batches.remove();
                }
            }
            if (writingDiskFlush != null && writingDiskFlush.matches(cells, snapshot)) {
                writingDiskFlush.discardCells = true;
                writingDiskFlush.discardSnapshot = true;
            }
        }
    }

    private static boolean discardedWriteInProgress(Path path) {
        synchronized (DISK_FLUSH_LOCK) {
            return writingDiskFlush != null
                    && ((writingDiskFlush.discardCells && writingDiskFlush.cellPath.equals(path))
                            || (writingDiskFlush.discardSnapshot && writingDiskFlush.snapshotPath.equals(path)));
        }
    }

    private static void deleteDiskFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
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
            this.cellSize = PadMapSamplingPolicy.cellSizeForZoom(zoom);
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

        PadMapJobScheduler.JobView schedulerView() {
            return new PadMapJobScheduler.JobView(centerX, centerZ, floorY, profile, zoom);
        }

        private String describe() {
            return "job=" + profile
                    + " " + progress.doneCells() + "/" + progress.totalCells() + " cells " + progress.percent() + "%"
                    + " center=(" + centerX + "," + centerZ + ")"
                    + " cell=" + cellSize
                    + " steps=" + progress.steps();
        }
    }

}
