package com.zhongbai233.net_music_can_play_bili.terrain.core;

/** 包含端点的世界方块范围，所有体积计算使用 long 防止整数溢出。 */
public record TerrainBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public TerrainBounds {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("terrain bounds are inverted");
        }
    }

    public long volume() {
        long width = (long) maxX - minX + 1L;
        long height = (long) maxY - minY + 1L;
        long depth = (long) maxZ - minZ + 1L;
        return Math.multiplyExact(Math.multiplyExact(width, height), depth);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean intersects(TerrainSectionKey section) {
        int sectionMaxX = section.minBlockX() + TerrainSectionKey.SIZE - 1;
        int sectionMaxY = section.minBlockY() + TerrainSectionKey.SIZE - 1;
        int sectionMaxZ = section.minBlockZ() + TerrainSectionKey.SIZE - 1;
        return sectionMaxX >= minX && section.minBlockX() <= maxX
                && sectionMaxY >= minY && section.minBlockY() <= maxY
                && sectionMaxZ >= minZ && section.minBlockZ() <= maxZ;
    }
}