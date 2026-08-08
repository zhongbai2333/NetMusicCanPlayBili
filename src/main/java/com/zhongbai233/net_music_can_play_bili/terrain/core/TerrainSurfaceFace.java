package com.zhongbai233.net_music_can_play_bili.terrain.core;

/** 简化地形网格的一张方块外露面；首版以材质分类色绘制，不携带 Minecraft 渲染对象。 */
public record TerrainSurfaceFace(int localX, int localY, int localZ, Direction direction,
        TerrainCellSample.RenderCategory material) {
    public TerrainSurfaceFace {
        java.util.Objects.requireNonNull(direction, "direction");
        java.util.Objects.requireNonNull(material, "material");
    }

    public enum Direction {
        DOWN(0, -1, 0),
        UP(0, 1, 0),
        NORTH(0, 0, -1),
        SOUTH(0, 0, 1),
        WEST(-1, 0, 0),
        EAST(1, 0, 0);

        private final int dx;
        private final int dy;
        private final int dz;

        Direction(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        public int dx() {
            return dx;
        }

        public int dy() {
            return dy;
        }

        public int dz() {
            return dz;
        }
    }
}