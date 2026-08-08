package com.zhongbai233.net_music_can_play_bili.terrain.core;

/** 不依赖 Minecraft 类型的 16×16×16 section 坐标。 */
public record TerrainSectionKey(int x, int y, int z) {
    public static final int SIZE = 16;
    public static final int CELL_COUNT = SIZE * SIZE * SIZE;

    public static TerrainSectionKey fromBlock(int blockX, int blockY, int blockZ) {
        return new TerrainSectionKey(Math.floorDiv(blockX, SIZE), Math.floorDiv(blockY, SIZE),
                Math.floorDiv(blockZ, SIZE));
    }

    public int minBlockX() {
        return x * SIZE;
    }

    public int minBlockY() {
        return y * SIZE;
    }

    public int minBlockZ() {
        return z * SIZE;
    }
}