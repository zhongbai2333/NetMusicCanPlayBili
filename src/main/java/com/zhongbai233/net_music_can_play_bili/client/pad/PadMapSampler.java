package com.zhongbai233.net_music_can_play_bili.client.pad;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class PadMapSampler {
    public static final int DEFAULT_VIEW_WIDTH = Integer.getInteger("ncpb.pad.map_view_width",
            Integer.getInteger("ncpb.pad.map_size", 384));
    public static final int DEFAULT_VIEW_HEIGHT = Integer.getInteger("ncpb.pad.map_view_height",
            192);
    public static final int DEFAULT_OVERSCAN = Integer.getInteger("ncpb.pad.map_overscan", 96);
    public static final int DEFAULT_WIDTH = Integer.getInteger("ncpb.pad.map_width",
            DEFAULT_VIEW_WIDTH + DEFAULT_OVERSCAN * 2);
    public static final int DEFAULT_HEIGHT = Integer.getInteger("ncpb.pad.map_height",
            DEFAULT_VIEW_HEIGHT + DEFAULT_OVERSCAN * 2);
    public static final int DEFAULT_SIZE = DEFAULT_WIDTH;
    private static final int CELL_SAMPLES = Integer.getInteger("ncpb.pad.map_cell_samples", 5);
    /** 屋面下方向下探测室内空腔的深度。 */
    private static final int INTERIOR_SCAN_DEPTH = 12;
    /** 室内地图向上探测顶棚的高度。 */
    private static final int CEILING_SCAN_HEIGHT = 32;
    /** 室内地图寻找落脚点时尝试的楼层偏移（兼容台阶/夹层）。 */
    private static final int[] STAND_OFFSETS = { 0, 1, -1, 2, -2, 3 };

    private PadMapSampler() {
    }

    public static PadMapSnapshot sample(Level level, int centerX, int centerZ, float zoom) {
        int width = DEFAULT_WIDTH;
        int height = DEFAULT_HEIGHT;
        int cellSize = cellSizeForZoom(zoom);
        PadMapTileKind[] tiles = new PadMapTileKind[width * height];
        int halfW = width / 2;
        int halfH = height / 2;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int worldX = centerX + (x - halfW) * cellSize;
                int worldZ = centerZ + (z - halfH) * cellSize;
                tiles[z * width + x] = classifyCell(level, mutable, worldX, worldZ, cellSize);
            }
        }
        smoothTreeCanopy(width, height, tiles);
        return new PadMapSnapshot(centerX, centerZ, cellSize, width, height, tiles);
    }

    /**
     * 室内地图单元分类：基于碰撞体积的可通行性分析。
     * <ul>
     * <li>能在 floorY 附近找到落脚点（脚部+头部可通行、脚下有支撑）且头顶有顶棚 → 室内地面；</li>
     * <li>脚部与头部两格都被人工实体占据 → 墙；纯自然方块（山体/地下）不算墙；</li>
     * <li>仅单层障碍（家具、台面、台阶） → 仍算室内地面；</li>
     * <li>落脚点头顶无顶棚（露天/树冠） → 室外地面。</li>
     * </ul>
     */
    public static PadMapTileKind classifyInteriorCell(Level level, BlockPos.MutableBlockPos mutable, int worldX,
            int worldZ, int floorY, int cellSize) {
        if (!isCellReady(level, worldX, worldZ, cellSize)) {
            return PadMapTileKind.UNKNOWN;
        }
        int samples = Math.max(1, Math.min(CELL_SAMPLES, cellSize * cellSize));
        int floor = 0;
        int wall = 0;
        int water = 0;
        int outdoor = 0;
        int loadedSamples = 0;
        for (int sample = 0; sample < samples; sample++) {
            int sx = worldX + sampleOffset(sample, cellSize, true);
            int sz = worldZ + sampleOffset(sample, cellSize, false);
            if (!level.hasChunk(Math.floorDiv(sx, 16), Math.floorDiv(sz, 16))) {
                continue;
            }
            loadedSamples++;
            PadMapTileKind kind = classifyInteriorColumn(level, mutable, sx, sz, floorY);
            if (kind == PadMapTileKind.WATER) {
                water++;
            } else if (kind == PadMapTileKind.BUILDING) {
                wall++;
            } else if (kind == PadMapTileKind.INDOOR_FLOOR) {
                floor++;
            } else {
                outdoor++;
            }
        }
        if (loadedSamples == 0) {
            return PadMapTileKind.UNKNOWN;
        }
        if (water > 0 && water >= floor + wall) {
            return PadMapTileKind.WATER;
        }
        if (wall > floor && wall >= outdoor) {
            return PadMapTileKind.BUILDING;
        }
        if (floor > 0 && floor >= outdoor) {
            return PadMapTileKind.INDOOR_FLOOR;
        }
        return PadMapTileKind.GRASS;
    }

    static int cellSizeForZoom(float zoom) {
        if (zoom >= 3.0F) {
            return 1;
        }
        if (zoom >= 1.5F) {
            return 1;
        }
        if (zoom >= 0.75F) {
            return 1;
        }
        return 4;
    }

    public static PadMapTileKind classifyCell(Level level, BlockPos.MutableBlockPos mutable, int worldX,
            int worldZ, int cellSize) {
        if (!isCellReady(level, worldX, worldZ, cellSize)) {
            return PadMapTileKind.UNKNOWN;
        }
        int samples = Math.max(1, Math.min(CELL_SAMPLES, cellSize * cellSize));
        int grass = 0;
        int building = 0;
        int water = 0;
        int tree = 0;
        int farmland = 0;
        int rock = 0;
        int snow = 0;
        int loadedSamples = 0;
        for (int sample = 0; sample < samples; sample++) {
            int sx = worldX + sampleOffset(sample, cellSize, true);
            int sz = worldZ + sampleOffset(sample, cellSize, false);
            if (!level.hasChunk(Math.floorDiv(sx, 16), Math.floorDiv(sz, 16))) {
                continue;
            }
            loadedSamples++;
            PadMapTileKind kind = classifyColumn(level, mutable, sx, sz);
            if (kind == PadMapTileKind.UNKNOWN) {
                // 客户端可能已创建区块对象，但高度图/方块数据仍未同步完成。
                // 任一列不可靠时保留整个 cell 为 UNKNOWN，交给现有重试机制重新采样。
                return PadMapTileKind.UNKNOWN;
            } else if (kind == PadMapTileKind.GRASS) {
                grass++;
            } else if (kind == PadMapTileKind.BUILDING) {
                building++;
            } else if (kind == PadMapTileKind.WATER) {
                water++;
            } else if (kind == PadMapTileKind.TREE) {
                tree++;
            } else if (kind == PadMapTileKind.FARMLAND) {
                farmland++;
            } else if (kind == PadMapTileKind.SNOW) {
                snow++;
            } else {
                rock++;
            }
        }
        if (loadedSamples == 0) {
            return PadMapTileKind.UNKNOWN;
        }
        if (water > 0 && water >= Math.max(1, loadedSamples / 3) && water >= building) {
            return PadMapTileKind.WATER;
        }
        if (building >= Math.max(1, loadedSamples / 2) || building > grass + tree + farmland + rock) {
            return PadMapTileKind.BUILDING;
        }
        if (tree >= Math.max(1, loadedSamples / 3) || tree > grass + farmland) {
            return PadMapTileKind.TREE;
        }
        if (farmland >= Math.max(1, loadedSamples / 3)) {
            return PadMapTileKind.FARMLAND;
        }
        if (snow > 0) {
            return PadMapTileKind.SNOW;
        }
        if (grass > 0 && rock == 0) {
            return PadMapTileKind.GRASS;
        }
        return grass >= rock ? PadMapTileKind.GRASS : PadMapTileKind.ROCK;
    }

    static boolean isCellReady(Level level, int worldX, int worldZ, int cellSize) {
        int samples = Math.max(1, Math.min(CELL_SAMPLES, cellSize * cellSize));
        for (int sample = 0; sample < samples; sample++) {
            int sx = worldX + sampleOffset(sample, cellSize, true);
            int sz = worldZ + sampleOffset(sample, cellSize, false);
            if (!level.hasChunk(Math.floorDiv(sx, 16), Math.floorDiv(sz, 16))) {
                return false;
            }
        }
        return true;
    }

    private static int sampleOffset(int sample, int cellSize, boolean xAxis) {
        if (cellSize <= 1) {
            return 0;
        }
        int max = cellSize - 1;
        int mid = cellSize / 2;
        return switch (sample) {
            case 0 -> mid;
            case 1 -> 0;
            case 2 -> xAxis ? max : 0;
            case 3 -> xAxis ? 0 : max;
            case 4 -> xAxis ? max : max;
            default -> max;
        };
    }

    /**
     * 户外单列分类:先按材质排除水体 / 植被 / 自然地形,剩下的人工方块只凭几何证据判定建筑。
     * <ul>
     * <li>围合证据:人工顶面下方存在连续空腔(屋面、檐下、悬挑);</li>
     * <li>抬升证据:人工顶面显著高于邻近地表(墙顶、高台边缘)。</li>
     * </ul>
     * 两者皆无的人工方块是贴地铺装(道路、广场),按普通地面处理。
     */
    private static PadMapTileKind classifyColumn(Level level, BlockPos.MutableBlockPos mutable, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (!isSurfaceHeightReady(level.getMinY(), y)) {
            return PadMapTileKind.UNKNOWN;
        }
        mutable.set(x, y, z);
        BlockState top = level.getBlockState(mutable);
        // 顶面可能是草丛、花、火把、告示牌等无碰撞体积的覆盖物:下沉到第一个有实体的方块
        int descend = 0;
        while (descend < 6 && y > level.getMinY() && top.getFluidState().isEmpty()
                && top.getCollisionShape(level, mutable).isEmpty()) {
            PadMapTileKind soft = classifySoftCover(top);
            if (soft != null) {
                return soft;
            }
            y--;
            descend++;
            mutable.set(x, y, z);
            top = level.getBlockState(mutable);
        }
        if (isWaterLike(top)) {
            return PadMapTileKind.WATER;
        }
        if (top.is(BlockTags.LEAVES) || isMushroomCap(top)) {
            return PadMapTileKind.TREE;
        }
        if (top.is(BlockTags.LOGS)) {
            // 有树冠的原木是树;没有树冠的原木(木屋墙体、木结构)是建筑
            return hasLeavesNearby(level, mutable, x, y, z) ? PadMapTileKind.TREE : PadMapTileKind.BUILDING;
        }
        if (isFarmlandLike(top)) {
            return PadMapTileKind.FARMLAND;
        }
        if (isNaturalTerrain(top)) {
            return naturalGroundKind(top);
        }
        if (hasInteriorSpaceBelow(level, mutable, x, y, z)) {
            return PadMapTileKind.BUILDING;
        }
        if (isElevatedAboveSurroundings(level, mutable, x, y, z)) {
            return PadMapTileKind.BUILDING;
        }
        return PadMapTileKind.GRASS;
    }

    /** 最低高度以下没有可分类的表面，通常表示客户端列数据尚未同步完成。 */
    static boolean isSurfaceHeightReady(int minY, int surfaceY) {
        return surfaceY >= minY;
    }

    /** 无碰撞覆盖物的直接归类;返回 null 表示继续向下找实体方块。 */
    private static PadMapTileKind classifySoftCover(BlockState state) {
        if (state.is(BlockTags.CROPS)) {
            return PadMapTileKind.FARMLAND;
        }
        if (state.is(BlockTags.SNOW)) {
            return PadMapTileKind.SNOW;
        }
        return null;
    }

    /**
     * 围合证据:自顶面向下扫描,在触及成片自然地基之前发现 ≥2 格连续空腔,说明顶面覆盖着室内空间。
     */
    private static boolean hasInteriorSpaceBelow(Level level, BlockPos.MutableBlockPos mutable, int x, int y, int z) {
        int airRun = 0;
        int naturalRun = 0;
        for (int dy = 1; dy <= INTERIOR_SCAN_DEPTH; dy++) {
            int by = y - dy;
            if (by < level.getMinY()) {
                return false;
            }
            mutable.set(x, by, z);
            BlockState state = level.getBlockState(mutable);
            if (state.isAir()) {
                naturalRun = 0;
                airRun++;
                if (airRun >= 2) {
                    return true;
                }
                continue;
            }
            airRun = 0;
            if (isNaturalTerrain(state)) {
                naturalRun++;
                if (naturalRun >= 3) {
                    // 已触及自然地基:再往下的洞穴与本柱无关
                    return false;
                }
            } else {
                naturalRun = 0;
            }
        }
        return false;
    }

    /** 抬升证据:与周边 ±2 格地表相比,本柱顶面显著更高(≥3 格)的采样点不少于 2 个。 */
    private static boolean isElevatedAboveSurroundings(Level level, BlockPos.MutableBlockPos mutable,
            int x, int y, int z) {
        int drops = 0;
        for (int dz = -2; dz <= 2; dz += 2) {
            for (int dx = -2; dx <= 2; dx += 2) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int sx = x + dx;
                int sz = z + dz;
                if (!level.hasChunk(Math.floorDiv(sx, 16), Math.floorDiv(sz, 16))) {
                    continue;
                }
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz) - 1;
                if (y - surface >= 3) {
                    drops++;
                    if (drops >= 2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasLeavesNearby(Level level, BlockPos.MutableBlockPos mutable, int x, int y, int z) {
        int leaves = 0;
        for (int dy = 0; dy <= 3; dy++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    mutable.set(x + dx, y + dy, z + dz);
                    if (level.getBlockState(mutable).is(BlockTags.LEAVES)) {
                        leaves++;
                        if (leaves >= 2) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean isMushroomCap(BlockState state) {
        return state.is(Blocks.RED_MUSHROOM_BLOCK) || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
                || state.is(Blocks.MUSHROOM_STEM);
    }

    /**
     * 室内单列分类:基于碰撞体积的可通行性分析。
     */
    private static PadMapTileKind classifyInteriorColumn(Level level, BlockPos.MutableBlockPos mutable,
            int x, int z, int floorY) {
        mutable.set(x, floorY, z);
        BlockState feet = level.getBlockState(mutable);
        mutable.set(x, floorY + 1, z);
        BlockState head = level.getBlockState(mutable);
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) {
            return PadMapTileKind.WATER;
        }
        int standY = findStandableY(level, mutable, x, z, floorY);
        if (standY != Integer.MIN_VALUE) {
            // 可落脚:有人工顶棚 → 室内地面;露天或树冠/地形覆盖 → 室外
            return hasArtificialCeiling(level, mutable, x, z, standY + 2)
                    ? PadMapTileKind.INDOOR_FLOOR
                    : PadMapTileKind.GRASS;
        }
        boolean feetBlocked = hasBlockingCollision(level, mutable, x, floorY, z);
        boolean headBlocked = hasBlockingCollision(level, mutable, x, floorY + 1, z);
        if (feetBlocked && headBlocked) {
            if (isNaturalTerrain(feet) && isNaturalTerrain(head)) {
                // 山体 / 地下岩土:是地形而不是墙
                return PadMapTileKind.GRASS;
            }
            return PadMapTileKind.BUILDING;
        }
        // 仅单层障碍(家具、台面、台阶):仍是室内空间
        return PadMapTileKind.INDOOR_FLOOR;
    }

    /** 在 floorY 附近寻找可站立高度(脚部+头部可通行、脚下有支撑);找不到返回 Integer.MIN_VALUE。 */
    private static int findStandableY(Level level, BlockPos.MutableBlockPos mutable, int x, int z, int floorY) {
        for (int offset : STAND_OFFSETS) {
            int fy = floorY + offset;
            if (fy - 1 < level.getMinY()) {
                continue;
            }
            mutable.set(x, fy, z);
            BlockState feet = level.getBlockState(mutable);
            if (!isPassableForFeet(level, mutable, feet)) {
                continue;
            }
            mutable.set(x, fy + 1, z);
            BlockState head = level.getBlockState(mutable);
            if (!head.isAir() && !head.getCollisionShape(level, mutable).isEmpty()) {
                continue;
            }
            mutable.set(x, fy - 1, z);
            BlockState below = level.getBlockState(mutable);
            if (!below.getCollisionShape(level, mutable).isEmpty()) {
                return fy;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 脚部可通行:空气、无碰撞装饰,或地毯 / 压力板等低矮覆盖物。 */
    private static boolean isPassableForFeet(Level level, BlockPos.MutableBlockPos mutable, BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, mutable);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.51D;
    }

    private static boolean hasBlockingCollision(Level level, BlockPos.MutableBlockPos mutable, int x, int y, int z) {
        mutable.set(x, y, z);
        BlockState state = level.getBlockState(mutable);
        return !state.isAir() && state.getFluidState().isEmpty()
                && !state.getCollisionShape(level, mutable).isEmpty();
    }

    /** 头顶是否覆盖着人工顶棚(而不是露天、树冠或自然地形)。 */
    private static boolean hasArtificialCeiling(Level level, BlockPos.MutableBlockPos mutable, int x, int z,
            int fromY) {
        for (int dy = 0; dy <= CEILING_SCAN_HEIGHT; dy++) {
            int y = fromY + dy;
            mutable.set(x, y, z);
            BlockState state = level.getBlockState(mutable);
            if (state.isAir()) {
                continue;
            }
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
                return false;
            }
            if (isNaturalTerrain(state)) {
                return false;
            }
            if (state.getCollisionShape(level, mutable).isEmpty()) {
                // 灯具、悬挂装饰等:继续向上找真正的顶棚
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isWaterLike(BlockState state) {
        return !state.getFluidState().isEmpty() || state.is(Blocks.WATER) || state.is(BlockTags.ICE);
    }

    private static boolean isFarmlandLike(BlockState state) {
        return state.is(Blocks.FARMLAND) || state.is(BlockTags.CROPS) || state.is(Blocks.MELON)
                || state.is(Blocks.PUMPKIN) || state.is(Blocks.HAY_BLOCK);
    }

    /**
     * 自然地形方块:世界生成中大量出现的岩、土、砂、雪等。自然方块永远不构成建筑证据,
     * 这样就不需要维护"人工方块清单"——不属于自然/植被/水体的实体方块即视为人工方块。
     */
    static boolean isNaturalTerrain(BlockState state) {
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.NYLIUM)
                || state.is(BlockTags.SNOW) || state.is(BlockTags.ICE) || state.is(BlockTags.TERRACOTTA)) {
            return true;
        }
        if (state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE) || state.is(Blocks.CALCITE) || state.is(Blocks.TUFF)
                || state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.POINTED_DRIPSTONE)
                || state.is(Blocks.OBSIDIAN) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.SOUL_SAND)
                || state.is(Blocks.SOUL_SOIL) || state.is(Blocks.END_STONE) || state.is(Blocks.AMETHYST_BLOCK)
                || state.is(Blocks.BUDDING_AMETHYST) || state.is(Blocks.BEDROCK) || state.is(Blocks.SMOOTH_BASALT)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND)
                || state.is(Blocks.MYCELIUM) || state.is(Blocks.TERRACOTTA)) {
            return true;
        }
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return name.endsWith("_ore") || name.startsWith("infested_");
    }

    /** 自然地形按材质映射为地面 / 岩地 / 雪地。 */
    private static PadMapTileKind naturalGroundKind(BlockState state) {
        if (state.is(BlockTags.SNOW) || state.is(Blocks.POWDER_SNOW)) {
            return PadMapTileKind.SNOW;
        }
        if (state.is(BlockTags.DIRT) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.CLAY) || state.is(BlockTags.NYLIUM) || state.is(Blocks.FARMLAND)) {
            return PadMapTileKind.GRASS;
        }
        return PadMapTileKind.ROCK;
    }

    private static void smoothTreeCanopy(int width, int height, PadMapTileKind[] tiles) {
        PadMapTileKind[] copy = tiles.clone();
        for (int z = 1; z < height - 1; z++) {
            for (int x = 1; x < width - 1; x++) {
                int index = z * width + x;
                int trees = countNeighbors(copy, width, height, x, z, PadMapTileKind.TREE);
                if (copy[index] == PadMapTileKind.TREE) {
                    if (trees <= 1) {
                        tiles[index] = dominantNeighbor(copy, width, x, z);
                    }
                    continue;
                }
                if (copy[index] == PadMapTileKind.GRASS || copy[index] == PadMapTileKind.ROCK
                        || copy[index] == PadMapTileKind.UNKNOWN) {
                    if (trees >= 4) {
                        tiles[index] = PadMapTileKind.TREE;
                    }
                }
            }
        }
    }

    private static int countNeighbors(PadMapTileKind[] tiles, int width, int height, int x, int z,
            PadMapTileKind kind) {
        int count = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                if (nx < 0 || nx >= width || nz < 0 || nz >= height) {
                    continue;
                }
                if (tiles[nz * width + nx] == kind) {
                    count++;
                }
            }
        }
        return count;
    }

    private static PadMapTileKind dominantNeighbor(PadMapTileKind[] tiles, int size, int x, int z) {
        int grass = 0;
        int building = 0;
        int water = 0;
        int tree = 0;
        int rock = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                PadMapTileKind kind = tiles[(z + dz) * size + x + dx];
                switch (kind) {
                    case BUILDING -> building++;
                    case WATER -> water++;
                    case TREE -> tree++;
                    case ROCK -> rock++;
                    default -> grass++;
                }
            }
        }
        if (building >= grass && building >= water && building >= tree && building >= rock) {
            return PadMapTileKind.BUILDING;
        }
        if (water >= grass && water >= tree && water >= rock) {
            return PadMapTileKind.WATER;
        }
        if (tree >= grass && tree >= rock) {
            return PadMapTileKind.TREE;
        }
        return rock > grass ? PadMapTileKind.ROCK : PadMapTileKind.GRASS;
    }

}