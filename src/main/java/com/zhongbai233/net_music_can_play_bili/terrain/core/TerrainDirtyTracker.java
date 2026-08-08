package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 去重且有硬上限的 section dirty 队列；溢出时淘汰最早标记，禁止无限增长。 */
public final class TerrainDirtyTracker {
    private final int limit;
    private final Set<TerrainSectionKey> dirty;

    public TerrainDirtyTracker(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("dirty section limit must be positive");
        }
        this.limit = limit;
        this.dirty = new LinkedHashSet<>(Math.min(limit, 256));
    }

    public synchronized void markSection(TerrainSectionKey section) {
        java.util.Objects.requireNonNull(section, "section");
        dirty.remove(section);
        dirty.add(section);
        trim();
    }

    /** 方块变化会使自身 section 及位于 section 边界上的相邻 section 失效。 */
    public synchronized void markBlockAndBoundaryNeighbors(int blockX, int blockY, int blockZ) {
        TerrainSectionKey center = TerrainSectionKey.fromBlock(blockX, blockY, blockZ);
        markSection(center);
        int localX = Math.floorMod(blockX, TerrainSectionKey.SIZE);
        int localY = Math.floorMod(blockY, TerrainSectionKey.SIZE);
        int localZ = Math.floorMod(blockZ, TerrainSectionKey.SIZE);
        if (localX == 0) markSection(new TerrainSectionKey(center.x() - 1, center.y(), center.z()));
        if (localX == TerrainSectionKey.SIZE - 1) markSection(new TerrainSectionKey(center.x() + 1, center.y(), center.z()));
        if (localY == 0) markSection(new TerrainSectionKey(center.x(), center.y() - 1, center.z()));
        if (localY == TerrainSectionKey.SIZE - 1) markSection(new TerrainSectionKey(center.x(), center.y() + 1, center.z()));
        if (localZ == 0) markSection(new TerrainSectionKey(center.x(), center.y(), center.z() - 1));
        if (localZ == TerrainSectionKey.SIZE - 1) markSection(new TerrainSectionKey(center.x(), center.y(), center.z() + 1));
    }

    public synchronized List<TerrainSectionKey> drain(int maxCount) {
        if (maxCount <= 0 || dirty.isEmpty()) {
            return List.of();
        }
        List<TerrainSectionKey> result = new ArrayList<>(Math.min(maxCount, dirty.size()));
        var iterator = dirty.iterator();
        while (iterator.hasNext() && result.size() < maxCount) {
            result.add(iterator.next());
            iterator.remove();
        }
        return List.copyOf(result);
    }

    public synchronized int size() {
        return dirty.size();
    }

    public synchronized void clear() {
        dirty.clear();
    }

    private void trim() {
        while (dirty.size() > limit) {
            var iterator = dirty.iterator();
            iterator.next();
            iterator.remove();
        }
    }
}