package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;

import java.util.Set;

/** Computes which persistent full-detail GPU sections are no longer authoritative. */
public final class TerrainResidentSectionPolicy {
    private TerrainResidentSectionPolicy() {
    }

    public static Set<TerrainSectionKey> staleSections(Set<TerrainSectionKey> resident,
            TerrainPreviewFrame frame) {
        java.util.Objects.requireNonNull(resident, "resident");
        java.util.Objects.requireNonNull(frame, "frame");
        Set<TerrainSectionKey> stale = new java.util.HashSet<>();
        // 固定核心的边界 section 可同时包含实景与线框；overview 不再代表整段降级。
        for (TerrainSectionKey key : resident) {
            if (!frame.fullDetailSectionKeys().contains(key)) {
                stale.add(key);
            }
        }
        stale.addAll(frame.removedSections());
        return Set.copyOf(stale);
    }
}