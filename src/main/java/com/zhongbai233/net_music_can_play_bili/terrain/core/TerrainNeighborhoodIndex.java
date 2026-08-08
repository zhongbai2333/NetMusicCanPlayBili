package com.zhongbai233.net_music_can_play_bili.terrain.core;

/** 16³ section 外扩两格后的 20³ 邻域坐标和线性索引规则，覆盖原版 AO 对角采样。 */
public final class TerrainNeighborhoodIndex {
    public static final int BORDER = 2;
    public static final int MIN_LOCAL = -BORDER;
    public static final int MAX_LOCAL = TerrainSectionKey.SIZE + BORDER - 1;
    public static final int SIZE = TerrainSectionKey.SIZE + BORDER * 2;
    public static final int CELL_COUNT = SIZE * SIZE * SIZE;

    private TerrainNeighborhoodIndex() {
    }

    public static boolean contains(int localX, int localY, int localZ) {
        return localX >= MIN_LOCAL && localX <= MAX_LOCAL
                && localY >= MIN_LOCAL && localY <= MAX_LOCAL
                && localZ >= MIN_LOCAL && localZ <= MAX_LOCAL;
    }

    public static int index(int localX, int localY, int localZ) {
        if (!contains(localX, localY, localZ)) {
            throw new IndexOutOfBoundsException("neighborhood coordinates must be within [-2, 17]");
        }
        int x = localX - MIN_LOCAL;
        int y = localY - MIN_LOCAL;
        int z = localZ - MIN_LOCAL;
        return (y * SIZE + z) * SIZE + x;
    }

    /** 返回 3×3 邻接 chunk 表中的索引；中心 chunk 为 4。 */
    public static int neighborChunkIndex(int localX, int localZ) {
        if (localX < MIN_LOCAL || localX > MAX_LOCAL
                || localZ < MIN_LOCAL || localZ > MAX_LOCAL) {
            throw new IndexOutOfBoundsException("horizontal neighborhood coordinates must be within [-2, 17]");
        }
        int offsetX = Math.floorDiv(localX, TerrainSectionKey.SIZE);
        int offsetZ = Math.floorDiv(localZ, TerrainSectionKey.SIZE);
        return (offsetZ + 1) * 3 + offsetX + 1;
    }
}