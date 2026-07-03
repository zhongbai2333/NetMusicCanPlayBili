package com.zhongbai233.net_music_can_play_bili.client.pad;

import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 客户端 Pad 地图缓存：分帧采样，禁止在渲染帧里同步扫全图。 */
public final class PadMapClientCache {
    private static final int RESAMPLE_CHUNK_DISTANCE = Integer.getInteger("ncpb.pad.map_resample_chunks", 2);
    private static final int CHUNKS_PER_TICK = Integer.getInteger("ncpb.pad.map_chunks_per_tick", 1);
    private static final int FAST_CHUNKS_PER_TICK = Integer.getInteger("ncpb.pad.map_fast_chunks_per_tick", 3);
    private static final int MAX_JOB_LAG_CHUNKS = Integer.getInteger("ncpb.pad.map_max_job_lag_chunks", 4);
    private static final int RECENTER_BLOCKS = Integer.getInteger("ncpb.pad.map_recenter_blocks", 24);
    private static final int INDOOR_RECENTER_BLOCKS = Integer.getInteger("ncpb.pad.map_indoor_recenter_blocks", 8);
    private static final int INDOOR_CEILING_SCAN_BLOCKS = Integer.getInteger("ncpb.pad.map_indoor_ceiling_scan_blocks",
            32);
    private static final int INDOOR_CEILING_MIN_HITS = Integer.getInteger("ncpb.pad.map_indoor_ceiling_min_hits", 5);
    private static final int INDOOR_ARTIFICIAL_MIN_HITS = Integer.getInteger("ncpb.pad.map_indoor_artificial_min_hits",
            5);
    private static final int INDOOR_ENTER_CONFIRM_TICKS = Integer.getInteger("ncpb.pad.map_indoor_enter_confirm_ticks",
            2);
    private static final int INDOOR_EXIT_CONFIRM_TICKS = Integer.getInteger("ncpb.pad.map_indoor_exit_confirm_ticks",
            10);
    private static final int INDOOR_FLOOR_CHANGE_CONFIRM_TICKS = Integer
            .getInteger("ncpb.pad.map_indoor_floor_confirm_ticks", 4);
    private static final int INDOOR_JUMP_TOLERANCE_BLOCKS = Integer
            .getInteger("ncpb.pad.map_indoor_jump_tolerance_blocks", 2);
    private static final float OUTDOOR_ZOOM = Float.parseFloat(System.getProperty("ncpb.pad.map_outdoor_zoom", "1.25"));
    private static final float INDOOR_ZOOM = Float.parseFloat(System.getProperty("ncpb.pad.map_indoor_zoom", "3.0"));
    private static final float INDOOR_DISPLAY_SCALE = Float
            .parseFloat(System.getProperty("ncpb.pad.map_indoor_display_scale", "2.0"));
    private static final int PREVIEW_CHUNKS = Integer.getInteger("ncpb.pad.map_preview_chunks", 16);
    private static final int CELL_CACHE_LIMIT = Integer.getInteger("ncpb.pad.map_cell_cache_limit", 524288);
    private static final int CHUNK_CACHE_LIMIT = Integer.getInteger("ncpb.pad.map_chunk_cache_limit", 8192);
    private static final int DISK_CACHE_MAGIC = 0x4E504D43;
    private static final int DISK_CACHE_VERSION = 11;
    private static final int SNAPSHOT_CACHE_MAGIC = 0x4E504D53;
    private static final int SNAPSHOT_CACHE_VERSION = 12;
    private static final int CHUNK_CACHE_MAGIC = 0x4E504B43;
    private static final int CHUNK_CACHE_VERSION = 12;
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
    private static PadMapSnapshot completed;
    private static PadMapSnapshot placeholder;
    private static Job activeJob;
    private static ViewProfile completedProfile = ViewProfile.OUTDOOR;
    private static boolean manualViewActive;
    private static int manualCenterX;
    private static int manualCenterZ;
    private static float manualZoom = OUTDOOR_ZOOM;
    private static String activeDiskScope;
    private static String syncedWorldScopeId;
    private static boolean diskCacheLoaded;
    private static boolean diskCacheDirty;
    private static boolean snapshotCacheDirty;
    private static boolean chunkCacheDirty;
    private static int diskFlushTicker;
    private static ViewProfile stableProfile = ViewProfile.OUTDOOR;
    private static int indoorCandidateTicks;
    private static int outdoorCandidateTicks;
    private static int stableIndoorFloorY = outdoorLayerY();
    private static int candidateIndoorFloorY = outdoorLayerY();
    private static int candidateIndoorFloorTicks;

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
            activeJob = null;
            completed = null;
            placeholder = null;
            resetProfileStability();
            flushDiskCache();
            return;
        }
        updateDiskScope(minecraft);
        loadDiskCacheIfNeeded();
        if (!isPadRelevant(minecraft)) {
            activeJob = null;
            maybeFlushDiskCache();
            return;
        }
        int playerX = minecraft.player.blockPosition().getX();
        int playerY = minecraft.player.blockPosition().getY();
        int playerZ = minecraft.player.blockPosition().getZ();
        int targetX = manualViewActive ? manualCenterX : playerX;
        int targetZ = manualViewActive ? manualCenterZ : playerZ;
        float targetZoom = manualViewActive ? manualZoom : OUTDOOR_ZOOM;
        BlockPos profilePos = manualViewActive
                ? new BlockPos(targetX, playerY, targetZ)
                : minecraft.player.blockPosition();
        ViewProfile rawProfile = detectViewProfile(minecraft.level, profilePos);
        int rawFloorY = rawProfile == ViewProfile.INDOOR ? normalizeIndoorFloorY(minecraft.level, profilePos)
                : outdoorLayerY();
        ViewProfile profile = stabilizeViewProfile(rawProfile, rawFloorY);
        int targetY = profile == ViewProfile.INDOOR ? stableIndoorFloorY
                : outdoorLayerY();
        cancelStaleJob(targetX, targetY, targetZ, profile, targetZoom);
        maybeStartJob(minecraft.level, targetX, targetY, targetZ, profile, targetZoom);
        publishProfileTransition(targetX, targetY, targetZ, profile);
        if (activeJob != null) {
            long started = System.nanoTime();
            boolean done = activeJob.step(chunksPerTickFor(playerX, playerZ));
            PadPerfLogger.recordSampleStep(System.nanoTime() - started);
            if (done) {
                completed = activeJob.finish();
                completedProfile = activeJob.profile;
                if (completedProfile == ViewProfile.OUTDOOR) {
                    snapshotCacheDirty = true;
                    flushDiskCache();
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

    private static void publishProfileTransition(int playerX, int playerY, int playerZ, ViewProfile profile) {
        if (completed == null || (completedProfile == profile && completed.centerY() == playerY)) {
            return;
        }
        completed = transitionSnapshot(playerX, playerY, playerZ, profile);
        completedProfile = profile;
    }

    private static void cancelStaleJob(int playerX, int playerY, int playerZ, ViewProfile profile, float zoom) {
        if (activeJob == null) {
            return;
        }
        if (activeJob.profile != profile || activeJob.zoom != zoom || activeJob.floorY != playerY) {
            activeJob = null;
            return;
        }
        int lagX = chunkDistance(playerX, activeJob.centerX);
        int lagZ = chunkDistance(playerZ, activeJob.centerZ);
        if (Math.max(lagX, lagZ) > MAX_JOB_LAG_CHUNKS) {
            activeJob = null;
        }
    }

    private static int chunksPerTickFor(int playerX, int playerZ) {
        if (activeJob == null) {
            return CHUNKS_PER_TICK;
        }
        int lagX = chunkDistance(playerX, activeJob.centerX);
        int lagZ = chunkDistance(playerZ, activeJob.centerZ);
        int lag = Math.max(lagX, lagZ);
        if (lag >= RESAMPLE_CHUNK_DISTANCE * 2) {
            return FAST_CHUNKS_PER_TICK;
        }
        if (lag >= RESAMPLE_CHUNK_DISTANCE) {
            return Math.max(CHUNKS_PER_TICK, FAST_CHUNKS_PER_TICK - 1);
        }
        return CHUNKS_PER_TICK;
    }

    private static void maybeStartJob(ClientLevel level, int playerX, int playerY, int playerZ, ViewProfile profile,
            float zoom) {
        if (activeJob != null) {
            return;
        }
        int recenterBlocks = profile == ViewProfile.INDOOR ? INDOOR_RECENTER_BLOCKS : RECENTER_BLOCKS;
        boolean hasNoMap = completed == null && activeJob == null;
        boolean profileChanged = completed != null && completedProfile != profile;
        boolean floorChanged = completed != null && completed.centerY() != playerY;
        boolean moved = completed != null && (Math.abs(playerX - completed.centerX()) >= recenterBlocks
                || Math.abs(playerZ - completed.centerZ()) >= recenterBlocks);
        if (!hasNoMap && !profileChanged && !floorChanged && !moved) {
            return;
        }
        activeJob = new Job(level, playerX, playerZ, playerY, profile, zoom,
                profile == completedProfile && completed != null
                        && completed.cellSizeBlocks() == PadMapSampler.cellSizeForZoom(zoom)
                                ? completed
                                : null);
    }

    private static ViewProfile detectViewProfile(ClientLevel level, BlockPos playerPos) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int ceiling = 0;
        int artificial = 0;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = playerPos.getX() + dx;
                int z = playerPos.getZ() + dz;
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (surfaceY >= playerPos.getY() + 2 && surfaceY <= playerPos.getY() + INDOOR_CEILING_SCAN_BLOCKS) {
                    ceiling++;
                }
                for (int dy = -1; dy <= 5; dy++) {
                    mutable.set(x, playerPos.getY() + dy, z);
                    BlockState state = level.getBlockState(mutable);
                    if (isArtificialProfileBlock(level, mutable, state)) {
                        artificial++;
                        break;
                    }
                }
            }
        }
        return ceiling >= INDOOR_CEILING_MIN_HITS && artificial >= INDOOR_ARTIFICIAL_MIN_HITS
                ? ViewProfile.INDOOR
                : ViewProfile.OUTDOOR;
    }

    private static boolean isArtificialProfileBlock(ClientLevel level, BlockPos.MutableBlockPos mutable,
            BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() || state.getCollisionShape(level, mutable).isEmpty()) {
            return false;
        }
        return !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS) && !PadMapSampler.isNaturalTerrain(state);
    }

    private static ViewProfile stabilizeViewProfile(ViewProfile rawProfile, int rawFloorY) {
        if (stableProfile == ViewProfile.INDOOR) {
            if (rawProfile == ViewProfile.INDOOR) {
                outdoorCandidateTicks = 0;
                stabilizeIndoorFloor(rawFloorY);
                return ViewProfile.INDOOR;
            }
            indoorCandidateTicks = 0;
            if (++outdoorCandidateTicks < INDOOR_EXIT_CONFIRM_TICKS) {
                return ViewProfile.INDOOR;
            }
            stableProfile = ViewProfile.OUTDOOR;
            stableIndoorFloorY = outdoorLayerY();
            candidateIndoorFloorY = outdoorLayerY();
            candidateIndoorFloorTicks = 0;
            return ViewProfile.OUTDOOR;
        }
        if (rawProfile == ViewProfile.INDOOR) {
            outdoorCandidateTicks = 0;
            candidateIndoorFloorY = rawFloorY;
            if (++indoorCandidateTicks >= INDOOR_ENTER_CONFIRM_TICKS) {
                stableProfile = ViewProfile.INDOOR;
                stableIndoorFloorY = rawFloorY;
                candidateIndoorFloorTicks = 0;
                return ViewProfile.INDOOR;
            }
        } else {
            indoorCandidateTicks = 0;
            outdoorCandidateTicks = 0;
        }
        return ViewProfile.OUTDOOR;
    }

    private static void stabilizeIndoorFloor(int rawFloorY) {
        if (stableIndoorFloorY == outdoorLayerY()) {
            stableIndoorFloorY = rawFloorY;
            candidateIndoorFloorY = rawFloorY;
            candidateIndoorFloorTicks = 0;
            return;
        }
        if (Math.abs(rawFloorY - stableIndoorFloorY) <= INDOOR_JUMP_TOLERANCE_BLOCKS) {
            candidateIndoorFloorY = stableIndoorFloorY;
            candidateIndoorFloorTicks = 0;
            return;
        }
        if (candidateIndoorFloorY != rawFloorY) {
            candidateIndoorFloorY = rawFloorY;
            candidateIndoorFloorTicks = 1;
            return;
        }
        if (++candidateIndoorFloorTicks >= INDOOR_FLOOR_CHANGE_CONFIRM_TICKS) {
            stableIndoorFloorY = rawFloorY;
            candidateIndoorFloorTicks = 0;
        }
    }

    private static void resetProfileStability() {
        stableProfile = ViewProfile.OUTDOOR;
        indoorCandidateTicks = 0;
        outdoorCandidateTicks = 0;
        stableIndoorFloorY = outdoorLayerY();
        candidateIndoorFloorY = outdoorLayerY();
        candidateIndoorFloorTicks = 0;
    }

    private static int normalizeIndoorFloorY(ClientLevel level, BlockPos playerPos) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy >= -2; dy--) {
            int y = playerPos.getY() + dy;
            if (y - 1 < level.getMinY()) {
                continue;
            }
            mutable.set(playerPos.getX(), y, playerPos.getZ());
            BlockState feet = level.getBlockState(mutable);
            boolean feetOpen = feet.isAir() || feet.getCollisionShape(level, mutable).isEmpty();
            mutable.set(playerPos.getX(), y + 1, playerPos.getZ());
            BlockState head = level.getBlockState(mutable);
            boolean headOpen = head.isAir() || head.getCollisionShape(level, mutable).isEmpty();
            mutable.set(playerPos.getX(), y - 1, playerPos.getZ());
            BlockState below = level.getBlockState(mutable);
            if (feetOpen && headOpen && !below.getCollisionShape(level, mutable).isEmpty()) {
                return y;
            }
        }
        return playerPos.getY();
    }

    private static int outdoorLayerY() {
        return Integer.MIN_VALUE;
    }

    private static int chunkDistance(int a, int b) {
        return Math.abs(a - b) / 16;
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
        placeholder = new PadMapSnapshot(Math.floorDiv(playerX, 16) * 16, outdoorLayerY(),
                Math.floorDiv(playerZ, 16) * 16, 1, size, height, tiles);
        return placeholder;
    }

    private static PadMapSnapshot transitionSnapshot(int playerX, int playerY, int playerZ, ViewProfile profile) {
        int width = PadMapSampler.DEFAULT_WIDTH;
        int height = PadMapSampler.DEFAULT_HEIGHT;
        int cellSize = PadMapSampler.cellSizeForZoom(profile == ViewProfile.INDOOR ? INDOOR_ZOOM : OUTDOOR_ZOOM);
        PadMapTileKind[] tiles = new PadMapTileKind[width * height];
        java.util.Arrays.fill(tiles, PadMapTileKind.UNKNOWN);
        float displayScale = profile == ViewProfile.INDOOR ? INDOOR_DISPLAY_SCALE : 1.0F;
        return new PadMapSnapshot(playerX, playerY, playerZ, cellSize, width, height, tiles, displayScale);
    }

    private static PadMapTileKind cachedClassify(ClientLevel level, BlockPos.MutableBlockPos mutable, int worldX,
            int worldZ, int cellSize) {
        int cellX = Math.floorDiv(worldX, cellSize);
        int cellZ = Math.floorDiv(worldZ, cellSize);
        CellKey key = new CellKey(level.dimension().identifier().toString(), cellSize, cellX, cellZ);
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
        return cacheRoot().resolve("cells.bin");
    }

    public static Path snapshotCachePath() {
        return cacheRoot().resolve("snapshot.bin");
    }

    public static void flushDiskCache() {
        if (!DISK_CACHE_ENABLED || !diskCacheLoaded || !hasSyncedDiskScope()) {
            return;
        }
        CellDiskSnapshot cells = captureCellDiskSnapshot();
        ChunkDiskSnapshot chunks = captureChunkDiskSnapshot();
        SnapshotDiskSnapshot snapshot = captureSnapshotDiskSnapshot();
        if (cells == null && chunks == null && snapshot == null) {
            return;
        }
        DISK_FLUSH_EXECUTOR.execute(() -> {
            writeCellDiskCache(cells);
            writeChunkDiskCache(chunks);
            writeSnapshotDiskCache(snapshot);
        });
    }

    private static CellDiskSnapshot captureCellDiskSnapshot() {
        if (!diskCacheDirty) {
            return null;
        }
        Path path = diskCachePath();
        List<CellDiskEntry> entries = new ArrayList<>();
        synchronized (CELL_CACHE) {
            for (Map.Entry<CellKey, PadMapTileKind> entry : CELL_CACHE.entrySet()) {
                if (entry.getValue() != PadMapTileKind.UNKNOWN) {
                    entries.add(new CellDiskEntry(entry.getKey(), entry.getValue()));
                }
            }
        }
        diskCacheDirty = false;
        return new CellDiskSnapshot(path, entries);
    }

    private static SnapshotDiskSnapshot captureSnapshotDiskSnapshot() {
        if (!snapshotCacheDirty || completed == null || completed.tiles() == null) {
            return null;
        }
        Path path = snapshotCachePath();
        PadMapSnapshot snapshot = completed;
        PadMapTileKind[] tiles = java.util.Arrays.copyOf(snapshot.tiles(), snapshot.tiles().length);
        snapshotCacheDirty = false;
        return new SnapshotDiskSnapshot(path, snapshot.centerX(), snapshot.centerY(), snapshot.centerZ(),
                snapshot.cellSizeBlocks(), snapshot.width(), snapshot.height(), tiles);
    }

    private static ChunkDiskSnapshot captureChunkDiskSnapshot() {
        if (!chunkCacheDirty) {
            return null;
        }
        Path path = chunkCachePath();
        List<ChunkDiskEntry> entries = new ArrayList<>();
        synchronized (CHUNK_CACHE) {
            for (Map.Entry<ChunkKey, ChunkTile> entry : CHUNK_CACHE.entrySet()) {
                ChunkKey key = entry.getKey();
                if (key.profile() == ViewProfile.OUTDOOR) {
                    entries.add(new ChunkDiskEntry(key, java.util.Arrays.copyOf(entry.getValue().tiles,
                            entry.getValue().tiles.length)));
                }
            }
        }
        chunkCacheDirty = false;
        return new ChunkDiskSnapshot(path, entries);
    }

    private static void writeCellDiskCache(CellDiskSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.createDirectories(snapshot.path().getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(snapshot.path())))) {
                out.writeInt(DISK_CACHE_MAGIC);
                out.writeInt(DISK_CACHE_VERSION);
                out.writeInt(snapshot.entries().size());
                for (CellDiskEntry entry : snapshot.entries()) {
                    CellKey key = entry.key();
                    out.writeUTF(key.dimension());
                    out.writeInt(key.cellSize());
                    out.writeInt(key.cellX());
                    out.writeInt(key.cellZ());
                    out.writeByte(entry.kind().ordinal());
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void writeSnapshotDiskCache(SnapshotDiskSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.createDirectories(snapshot.path().getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(snapshot.path())))) {
                out.writeInt(SNAPSHOT_CACHE_MAGIC);
                out.writeInt(SNAPSHOT_CACHE_VERSION);
                out.writeInt(snapshot.centerX());
                out.writeInt(snapshot.centerY());
                out.writeInt(snapshot.centerZ());
                out.writeInt(snapshot.cellSizeBlocks());
                out.writeInt(snapshot.width());
                out.writeInt(snapshot.height());
                out.writeInt(snapshot.tiles().length);
                for (PadMapTileKind tile : snapshot.tiles()) {
                    out.writeByte(tile == null ? PadMapTileKind.UNKNOWN.ordinal() : tile.ordinal());
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void writeChunkDiskCache(ChunkDiskSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.createDirectories(snapshot.path().getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(snapshot.path())))) {
                out.writeInt(CHUNK_CACHE_MAGIC);
                out.writeInt(CHUNK_CACHE_VERSION);
                out.writeInt(snapshot.entries().size());
                for (ChunkDiskEntry entry : snapshot.entries()) {
                    ChunkKey key = entry.key();
                    out.writeUTF(key.dimension());
                    out.writeInt(key.chunkX());
                    out.writeInt(key.chunkZ());
                    out.writeInt(key.cellSize());
                    out.writeInt(key.floorY());
                    for (PadMapTileKind tile : entry.tiles()) {
                        out.writeByte(tile == null ? PadMapTileKind.UNKNOWN.ordinal() : tile.ordinal());
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    public static int clearAllCaches(boolean deleteDisk) {
        activeJob = null;
        completed = null;
        placeholder = null;
        resetProfileStability();
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
        chunkCacheDirty = false;
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
        resetProfileStability();
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
        loadChunkDiskCache();
        Path path = diskCachePath();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != DISK_CACHE_MAGIC || in.readInt() != DISK_CACHE_VERSION) {
                return;
            }
            int count = Math.max(0, Math.min(in.readInt(), CELL_CACHE_LIMIT));
            synchronized (CELL_CACHE) {
                CELL_CACHE.clear();
                for (int i = 0; i < count; i++) {
                    CellKey key = new CellKey(in.readUTF(), in.readInt(), in.readInt(), in.readInt());
                    int ordinal = in.readUnsignedByte();
                    PadMapTileKind kind = tileKindByOrdinal(ordinal);
                    if (kind == null) {
                        CELL_CACHE.clear();
                        return;
                    }
                    if (kind != PadMapTileKind.UNKNOWN) {
                        CELL_CACHE.put(key, kind);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void loadSnapshotDiskCache() {
        Path path = snapshotCachePath();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != SNAPSHOT_CACHE_MAGIC || in.readInt() != SNAPSHOT_CACHE_VERSION) {
                return;
            }
            int centerX = in.readInt();
            int centerY = in.readInt();
            int centerZ = in.readInt();
            int cellSize = in.readInt();
            int width = in.readInt();
            int height = in.readInt();
            int length = in.readInt();
            if (width != PadMapSampler.DEFAULT_WIDTH || height != PadMapSampler.DEFAULT_HEIGHT
                    || cellSize != PadMapSampler.cellSizeForZoom(1.25F) || length != width * height) {
                return;
            }
            PadMapTileKind[] tiles = new PadMapTileKind[length];
            for (int i = 0; i < length; i++) {
                int ordinal = in.readUnsignedByte();
                PadMapTileKind kind = tileKindByOrdinal(ordinal);
                if (kind == null) {
                    return;
                }
                tiles[i] = kind;
            }
            completed = new PadMapSnapshot(centerX, centerY, centerZ, cellSize, width, height, tiles);
        } catch (IOException ignored) {
        }
    }

    public static Path chunkCachePath() {
        return cacheRoot().resolve("chunks.bin");
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
        resetProfileStability();
        synchronized (CELL_CACHE) {
            CELL_CACHE.clear();
        }
        synchronized (CHUNK_CACHE) {
            CHUNK_CACHE.clear();
        }
        diskCacheLoaded = false;
        diskCacheDirty = false;
        snapshotCacheDirty = false;
        chunkCacheDirty = false;
    }

    private static Path cacheRoot() {
        Minecraft minecraft = Minecraft.getInstance();
        String scope = currentDiskScope(minecraft);
        return minecraft.gameDirectory.toPath()
                .resolve("netmusic-pad-map-cache")
                .resolve(scope)
                .resolve(currentDimensionScope(minecraft));
    }

    private static boolean hasSyncedDiskScope() {
        return !isBlank(syncedWorldScopeId);
    }

    private static String currentDiskScope(Minecraft minecraft) {
        if (minecraft == null) {
            return "unknown";
        }
        if (!isBlank(syncedWorldScopeId)) {
            return "world-" + stablePathToken(syncedWorldScopeId);
        }
        return "pending-world-scope";
    }

    private static String currentDimensionScope(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) {
            return "unknown-dimension";
        }
        return stablePathToken(minecraft.level.dimension().identifier().toString());
    }

    private static String stablePathToken(String value) {
        String normalized = isBlank(value) ? "unknown" : value.trim().toLowerCase(java.util.Locale.ROOT);
        String readable = normalized.replace('\\', '_').replace('/', '_').replace(':', '_')
                .replace('|', '_').replace(' ', '_');
        readable = readable.replaceAll("[^a-z0-9._-]", "_");
        if (readable.length() > 48) {
            readable = readable.substring(0, 48);
        }
        return readable + "-" + shortHash(normalized);
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 5);
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static PadMapTileKind tileKindByOrdinal(int ordinal) {
        PadMapTileKind[] values = PadMapTileKind.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    private static void loadChunkDiskCache() {
        Path path = chunkCachePath();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != CHUNK_CACHE_MAGIC || in.readInt() != CHUNK_CACHE_VERSION) {
                return;
            }
            int count = Math.max(0, Math.min(in.readInt(), CHUNK_CACHE_LIMIT));
            synchronized (CHUNK_CACHE) {
                CHUNK_CACHE.clear();
                for (int i = 0; i < count; i++) {
                    ChunkKey key = new ChunkKey(in.readUTF(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                            ViewProfile.OUTDOOR);
                    ChunkTile tile = new ChunkTile();
                    for (int j = 0; j < tile.tiles.length; j++) {
                        int ordinal = in.readUnsignedByte();
                        PadMapTileKind kind = tileKindByOrdinal(ordinal);
                        if (kind == null) {
                            CHUNK_CACHE.clear();
                            return;
                        }
                        tile.tiles[j] = kind;
                    }
                    CHUNK_CACHE.put(key, tile);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static final class Job {
        private final ClientLevel level;
        private final int centerX;
        private final int centerZ;
        private final int floorY;
        private final ViewProfile profile;
        private final int size;
        private final int height;
        private final int cellSize;
        private final float zoom;
        private final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        private final List<ChunkKey> missingChunks = new ArrayList<>();
        private int chunkCursor;
        private int lastPreviewChunkCursor;
        private int steps;

        private Job(ClientLevel level, int centerX, int centerZ, int playerY, ViewProfile profile, float zoom,
                PadMapSnapshot previous) {
            this.level = level;
            this.zoom = zoom;
            this.cellSize = PadMapSampler.cellSizeForZoom(zoom);
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.floorY = playerY;
            this.profile = profile;
            this.size = PadMapSampler.DEFAULT_SIZE;
            this.height = PadMapSampler.DEFAULT_HEIGHT;
            seedPreviousSnapshot(previous);
            collectMissingChunks();
        }

        private boolean step(int chunkBudget) {
            steps++;
            int chunksPerStep = Math.max(1, chunkBudget);
            for (int i = 0; i < chunksPerStep && chunkCursor < missingChunks.size(); i++, chunkCursor++) {
                ChunkKey key = missingChunks.get(chunkCursor);
                ChunkTile tile = sampleChunk(key);
                synchronized (CHUNK_CACHE) {
                    CHUNK_CACHE.put(key, tile);
                }
                chunkCacheDirty = true;
            }
            return chunkCursor >= missingChunks.size();
        }

        private boolean shouldPublishPreview() {
            if (chunkCursor <= 0 || chunkCursor == lastPreviewChunkCursor) {
                return false;
            }
            if (lastPreviewChunkCursor == 0) {
                return true;
            }
            return chunkCursor - lastPreviewChunkCursor >= Math.max(1, PREVIEW_CHUNKS);
        }

        private PadMapSnapshot preview() {
            lastPreviewChunkCursor = chunkCursor;
            return composeSnapshot(false);
        }

        private void seedPreviousSnapshot(PadMapSnapshot previous) {
            if (previous == null || previous.width() != size || previous.height() != height
                    || previous.cellSizeBlocks() != cellSize || previous.centerY() != floorY
                    || previous.tiles() == null) {
                return;
            }
            int halfW = size / 2;
            int halfH = height / 2;
            synchronized (CHUNK_CACHE) {
                for (int z = 0; z < height; z++) {
                    for (int x = 0; x < size; x++) {
                        PadMapTileKind kind = previous.tile(x, z);
                        if (kind == PadMapTileKind.UNKNOWN) {
                            continue;
                        }
                        int worldX = previous.centerX() + (x - halfW) * cellSize;
                        int worldZ = previous.centerZ() + (z - halfH) * cellSize;
                        putCachedChunkCell(worldX, worldZ, kind);
                    }
                }
            }
        }

        private void collectMissingChunks() {
            int halfW = size / 2;
            int halfH = height / 2;
            int minWorldX = centerX - halfW * cellSize;
            int maxWorldX = centerX + (size - halfW - 1) * cellSize;
            int minWorldZ = centerZ - halfH * cellSize;
            int maxWorldZ = centerZ + (height - halfH - 1) * cellSize;
            String dimension = level.dimension().identifier().toString();
            int centerChunkX = Math.floorDiv(centerX, 16);
            int centerChunkZ = Math.floorDiv(centerZ, 16);
            List<ChunkKey> keys = new ArrayList<>();
            synchronized (CHUNK_CACHE) {
                for (int chunkZ = Math.floorDiv(minWorldZ, 16); chunkZ <= Math.floorDiv(maxWorldZ, 16); chunkZ++) {
                    for (int chunkX = Math.floorDiv(minWorldX, 16); chunkX <= Math.floorDiv(maxWorldX, 16); chunkX++) {
                        ChunkKey key = new ChunkKey(dimension, chunkX, chunkZ, cellSize, floorY, profile);
                        if (!CHUNK_CACHE.containsKey(key)) {
                            keys.add(key);
                        }
                    }
                }
            }
            keys.sort(java.util.Comparator.comparingInt(key -> Math.abs(key.chunkX() - centerChunkX)
                    + Math.abs(key.chunkZ() - centerChunkZ)));
            missingChunks.addAll(keys);
        }

        private ChunkTile sampleChunk(ChunkKey key) {
            ChunkTile tile = new ChunkTile();
            int baseX = key.chunkX() * 16;
            int baseZ = key.chunkZ() * 16;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int worldX = baseX + x * key.cellSize();
                    int worldZ = baseZ + z * key.cellSize();
                    tile.tiles[z * 16 + x] = profile == ViewProfile.INDOOR
                            ? PadMapSampler.classifyInteriorCell(level, mutable, worldX, worldZ, floorY, key.cellSize())
                            : cachedClassify(level, mutable, worldX, worldZ, key.cellSize());
                }
            }
            return tile;
        }

        private void putCachedChunkCell(int worldX, int worldZ, PadMapTileKind kind) {
            ChunkKey key = new ChunkKey(level.dimension().identifier().toString(), Math.floorDiv(worldX, 16),
                    Math.floorDiv(worldZ, 16), cellSize, floorY, profile);
            ChunkTile tile = CHUNK_CACHE.computeIfAbsent(key, ignored -> new ChunkTile());
            tile.tiles[Math.floorMod(worldZ, 16) * 16 + Math.floorMod(worldX, 16)] = kind;
        }

        private PadMapSnapshot finish() {
            return composeSnapshot(true);
        }

        private PadMapSnapshot composeSnapshot(boolean finalSnapshot) {
            PadMapTileKind[] tiles = new PadMapTileKind[size * height];
            int halfW = size / 2;
            int halfH = height / 2;
            String dimension = level.dimension().identifier().toString();
            synchronized (CHUNK_CACHE) {
                for (int z = 0; z < height; z++) {
                    int lastChunkX = Integer.MIN_VALUE;
                    int lastChunkZ = Integer.MIN_VALUE;
                    ChunkTile lastTile = null;
                    for (int x = 0; x < size; x++) {
                        int worldX = centerX + (x - halfW) * cellSize;
                        int worldZ = centerZ + (z - halfH) * cellSize;
                        int chunkX = Math.floorDiv(worldX, 16);
                        int chunkZ = Math.floorDiv(worldZ, 16);
                        if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
                            lastChunkX = chunkX;
                            lastChunkZ = chunkZ;
                            lastTile = CHUNK_CACHE
                                    .get(new ChunkKey(dimension, chunkX, chunkZ, cellSize, floorY, profile));
                        }
                        tiles[z * size + x] = lastTile == null ? PadMapTileKind.UNKNOWN
                                : lastTile.tiles[Math.floorMod(worldZ, 16) * 16 + Math.floorMod(worldX, 16)];
                    }
                }
            }
            float displayScale = profile == ViewProfile.INDOOR ? INDOOR_DISPLAY_SCALE : 1.0F;
            return new PadMapSnapshot(centerX, floorY, centerZ, cellSize, size, height, tiles, displayScale);
        }

        private int steps() {
            return steps;
        }
    }

    private static final class ChunkTile {
        private final PadMapTileKind[] tiles = new PadMapTileKind[16 * 16];

        private ChunkTile() {
            java.util.Arrays.fill(tiles, PadMapTileKind.UNKNOWN);
        }
    }

    private record CellKey(String dimension, int cellSize, int cellX, int cellZ) {
    }

    private record ChunkKey(String dimension, int chunkX, int chunkZ, int cellSize, int floorY, ViewProfile profile) {
    }

    private record CellDiskEntry(CellKey key, PadMapTileKind kind) {
    }

    private record CellDiskSnapshot(Path path, List<CellDiskEntry> entries) {
    }

    private record SnapshotDiskSnapshot(Path path, int centerX, int centerY, int centerZ, int cellSizeBlocks,
            int width, int height, PadMapTileKind[] tiles) {
    }

    private record ChunkDiskEntry(ChunkKey key, PadMapTileKind[] tiles) {
    }

    private record ChunkDiskSnapshot(Path path, List<ChunkDiskEntry> entries) {
    }

    private enum ViewProfile {
        OUTDOOR,
        INDOOR
    }
}