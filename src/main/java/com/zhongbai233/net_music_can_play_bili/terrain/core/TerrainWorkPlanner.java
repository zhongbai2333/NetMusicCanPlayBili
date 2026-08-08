package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯核心工作规划器。它只安排 section key，不读取世界、不创建 future，也不持有渲染资源。
 */
public final class TerrainWorkPlanner {
    private final int maxPending;
    private final Map<TerrainSectionKey, Request> requests = new HashMap<>();
    private TerrainSectionKey cameraSection = new TerrainSectionKey(0, 0, 0);
    private TerrainSectionKey selectedSection;

    public TerrainWorkPlanner(int maxPending) {
        if (maxPending <= 0) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        this.maxPending = maxPending;
    }

    public synchronized void updateCamera(TerrainSectionKey section) {
        cameraSection = java.util.Objects.requireNonNull(section, "section");
    }

    public synchronized void updateSelected(TerrainSectionKey section) {
        selectedSection = section;
    }

    public synchronized void updateCoverage(TerrainBounds bounds, TerrainLodLevel lod) {
        java.util.Objects.requireNonNull(bounds, "bounds");
        java.util.Objects.requireNonNull(lod, "lod");
        int minX = Math.floorDiv(bounds.minX(), TerrainSectionKey.SIZE);
        int minY = Math.floorDiv(bounds.minY(), TerrainSectionKey.SIZE);
        int minZ = Math.floorDiv(bounds.minZ(), TerrainSectionKey.SIZE);
        int maxX = Math.floorDiv(bounds.maxX(), TerrainSectionKey.SIZE);
        int maxY = Math.floorDiv(bounds.maxY(), TerrainSectionKey.SIZE);
        int maxZ = Math.floorDiv(bounds.maxZ(), TerrainSectionKey.SIZE);
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    enqueue(new TerrainSectionKey(x, y, z), lod, TerrainWorkPriority.FAR);
                }
            }
        }
    }

    public synchronized void markDirty(TerrainSectionKey section, TerrainLodLevel lod) {
        enqueue(section, lod, TerrainWorkPriority.DIRTY_VISIBLE);
    }

    public synchronized List<WorkItem> nextWork(int limit) {
        if (limit <= 0 || requests.isEmpty()) {
            return List.of();
        }
        List<Request> items = new ArrayList<>(requests.values());
        items.sort(Comparator.comparingInt((Request request) -> effectivePriority(request).ordinal())
                .thenComparingInt(request -> distance(request.section())));
        int count = Math.min(limit, items.size());
        List<WorkItem> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Request request = items.get(i);
            requests.remove(request.section());
            result.add(new WorkItem(request.section(), request.lod(), effectivePriority(request)));
        }
        return List.copyOf(result);
    }

    public synchronized int pending() {
        return requests.size();
    }

    public synchronized void clear() {
        requests.clear();
    }

    private void enqueue(TerrainSectionKey section, TerrainLodLevel lod, TerrainWorkPriority priority) {
        java.util.Objects.requireNonNull(section, "section");
        java.util.Objects.requireNonNull(lod, "lod");
        Request old = requests.get(section);
        if (old != null && old.priority().ordinal() <= priority.ordinal()) {
            return;
        }
        if (old == null && requests.size() >= maxPending) {
            return;
        }
        requests.put(section, new Request(section, lod, priority));
    }

    private TerrainWorkPriority effectivePriority(Request request) {
        if (selectedSection != null && selectedSection.equals(request.section())) {
            return TerrainWorkPriority.SELECTED_NEAR;
        }
        int distance = distance(request.section());
        if (distance <= 1) {
            return TerrainWorkPriority.CAMERA_NEAR;
        }
        return request.priority();
    }

    private int distance(TerrainSectionKey section) {
        return Math.abs(section.x() - cameraSection.x()) + Math.abs(section.y() - cameraSection.y())
                + Math.abs(section.z() - cameraSection.z());
    }

    private record Request(TerrainSectionKey section, TerrainLodLevel lod, TerrainWorkPriority priority) {
    }

    public record WorkItem(TerrainSectionKey section, TerrainLodLevel lod, TerrainWorkPriority priority) {
    }
}