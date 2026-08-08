package com.zhongbai233.net_music_can_play_bili.terrain.core;

/** 距离、内存压力和迟滞共同决定 LOD，避免相机在阈值附近反复重建。 */
public final class TerrainLodPolicy {
    private final double nearDistance;
    private final double midDistance;
    private final double hysteresis;

    public TerrainLodPolicy(double nearDistance, double midDistance, double hysteresis) {
        if (!Double.isFinite(nearDistance) || !Double.isFinite(midDistance) || !Double.isFinite(hysteresis)
                || nearDistance <= 0.0D || midDistance <= nearDistance || hysteresis < 0.0D
                || hysteresis >= nearDistance) {
            throw new IllegalArgumentException("invalid terrain LOD thresholds");
        }
        this.nearDistance = nearDistance;
        this.midDistance = midDistance;
        this.hysteresis = hysteresis;
    }

    public TerrainLodLevel choose(double distance, TerrainLodLevel previous, boolean loaded,
            boolean selectedNearby, double memoryPressure) {
        if (!loaded) {
            return TerrainLodLevel.UNKNOWN;
        }
        if (!Double.isFinite(distance) || distance < 0.0D || !Double.isFinite(memoryPressure)
                || memoryPressure < 0.0D) {
            throw new IllegalArgumentException("invalid terrain LOD inputs");
        }
        if (selectedNearby) {
            return TerrainLodLevel.NEAR;
        }
        TerrainLodLevel base = chooseWithHysteresis(distance, previous);
        int pressurePenalty = memoryPressure >= 0.95D ? 2 : memoryPressure >= 0.80D ? 1 : 0;
        int degraded = Math.min(TerrainLodLevel.FAR.ordinal(), base.ordinal() + pressurePenalty);
        return TerrainLodLevel.values()[degraded];
    }

    private TerrainLodLevel chooseWithHysteresis(double distance, TerrainLodLevel previous) {
        TerrainLodLevel stablePrevious = previous == null || previous == TerrainLodLevel.UNKNOWN
                ? raw(distance) : previous;
        return switch (stablePrevious) {
            case NEAR -> distance > nearDistance + hysteresis ? raw(distance) : TerrainLodLevel.NEAR;
            case MID -> distance < nearDistance - hysteresis ? TerrainLodLevel.NEAR
                    : distance > midDistance + hysteresis ? TerrainLodLevel.FAR : TerrainLodLevel.MID;
            case FAR -> distance < midDistance - hysteresis ? raw(distance) : TerrainLodLevel.FAR;
            case UNKNOWN -> raw(distance);
        };
    }

    private TerrainLodLevel raw(double distance) {
        if (distance <= nearDistance) return TerrainLodLevel.NEAR;
        if (distance <= midDistance) return TerrainLodLevel.MID;
        return TerrainLodLevel.FAR;
    }
}