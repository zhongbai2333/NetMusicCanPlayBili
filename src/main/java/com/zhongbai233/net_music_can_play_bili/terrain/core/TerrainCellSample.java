package com.zhongbai233.net_music_can_play_bili.terrain.core;

/** 主线程从世界提取的最小不可变单元；后台任务不得持有 Level、BlockState 或 BlockEntity。 */
public record TerrainCellSample(Availability availability, RenderCategory renderCategory, String blockId,
        String fluidId, boolean hasBlockEntity, boolean dynamicModel) {
    public TerrainCellSample {
        java.util.Objects.requireNonNull(availability, "availability");
        java.util.Objects.requireNonNull(renderCategory, "renderCategory");
        blockId = normalizedId(blockId);
        fluidId = normalizedId(fluidId);
        if (availability == Availability.UNKNOWN && renderCategory != RenderCategory.UNKNOWN) {
            throw new IllegalArgumentException("unknown cells must use UNKNOWN render category");
        }
    }

    public static TerrainCellSample unknown() {
        return new TerrainCellSample(Availability.UNKNOWN, RenderCategory.UNKNOWN, "", "", false, false);
    }

    public static TerrainCellSample air() {
        return new TerrainCellSample(Availability.LOADED, RenderCategory.AIR, "minecraft:air", "", false, false);
    }

    public boolean hasFluid() {
        return !fluidId.isEmpty();
    }

    private static String normalizedId(String value) {
        return value == null ? "" : value.trim();
    }

    public enum Availability {
        LOADED,
        UNKNOWN
    }

    /** 首版只承诺兼容分类；真实模组模型由后续可熔断的高细节适配层处理。 */
    public enum RenderCategory {
        AIR,
        MODEL,
        ENTITY_ANIMATED,
        INVISIBLE,
        UNKNOWN
    }
}