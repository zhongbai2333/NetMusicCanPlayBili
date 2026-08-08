package com.zhongbai233.net_music_can_play_bili.terrain.core;

/** Pure policy for deciding whether a captured section needs a new LOD representation. */
public final class TerrainLodReevaluationPolicy {
    private TerrainLodReevaluationPolicy() {
    }

    public static boolean shouldReplace(TerrainLodLevel current, TerrainLodLevel desired) {
        return java.util.Objects.requireNonNull(current, "current")
                != java.util.Objects.requireNonNull(desired, "desired");
    }
}