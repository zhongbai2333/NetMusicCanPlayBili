package com.zhongbai233.net_music_can_play_bili.terrain.core;

/** 不可变的方块群系染色快照。 */
public record TerrainTintColors(int grass, int foliage, int dryFoliage, int water) {
    public static final TerrainTintColors UNTINTED = new TerrainTintColors(-1, -1, -1, -1);

    public int color(TintType type) {
        java.util.Objects.requireNonNull(type, "type");
        return switch (type) {
            case GRASS -> grass;
            case FOLIAGE -> foliage;
            case DRY_FOLIAGE -> dryFoliage;
            case WATER -> water;
        };
    }

    public enum TintType {
        GRASS,
        FOLIAGE,
        DRY_FOLIAGE,
        WATER
    }
}