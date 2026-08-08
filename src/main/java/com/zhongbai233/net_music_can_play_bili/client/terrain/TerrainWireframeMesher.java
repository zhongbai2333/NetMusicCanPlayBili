package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 抵消相邻聚合盒的重合接缝，并把剩余共线区间合并为长线段。 */
public final class TerrainWireframeMesher {
    private TerrainWireframeMesher() {
    }

    public static List<Segment> mesh(List<TerrainOverviewCell> cells, TerrainBounds bounds) {
        java.util.Objects.requireNonNull(cells, "cells");
        java.util.Objects.requireNonNull(bounds, "bounds");
        Map<LineKey, TreeSet<Integer>> endpoints = new HashMap<>();
        for (TerrainOverviewCell cell : cells) {
            int x0 = Math.max(cell.worldX(), bounds.minX());
            int y0 = Math.max(cell.worldY(), bounds.minY());
            int z0 = Math.max(cell.worldZ(), bounds.minZ());
            int x1 = Math.min(cell.worldX() + cell.size(), bounds.maxX() + 1);
            int y1 = Math.min(cell.worldY() + cell.size(), bounds.maxY() + 1);
            int z1 = Math.min(cell.worldZ() + cell.size(), bounds.maxZ() + 1);
            if (x1 <= x0 || y1 <= y0 || z1 <= z0) {
                continue;
            }
            addBox(endpoints, x0, y0, z0, x1, y1, z1, cell.material());
        }
        List<LineKey> lines = new ArrayList<>(endpoints.keySet());
        lines.sort(Comparator.comparingInt((LineKey line) -> line.axis())
            .thenComparing(line -> line.material())
            .thenComparingInt(line -> line.fixedA())
            .thenComparingInt(line -> line.fixedB()));
        List<Segment> result = new ArrayList<>();
        for (LineKey line : lines) {
            Integer start = null;
            for (int endpoint : endpoints.get(line)) {
                if (start == null) {
                    start = endpoint;
                } else {
                    result.add(line.segment(start, endpoint));
                    start = null;
                }
            }
        }
        return List.copyOf(result);
    }

    private static void addBox(Map<LineKey, TreeSet<Integer>> endpoints, int x0, int y0, int z0,
            int x1, int y1, int z1, TerrainCellSample.RenderCategory material) {
        add(endpoints, new LineKey(0, y0, z0, material), x0, x1);
        add(endpoints, new LineKey(0, y0, z1, material), x0, x1);
        add(endpoints, new LineKey(0, y1, z0, material), x0, x1);
        add(endpoints, new LineKey(0, y1, z1, material), x0, x1);
        add(endpoints, new LineKey(1, x0, z0, material), y0, y1);
        add(endpoints, new LineKey(1, x0, z1, material), y0, y1);
        add(endpoints, new LineKey(1, x1, z0, material), y0, y1);
        add(endpoints, new LineKey(1, x1, z1, material), y0, y1);
        add(endpoints, new LineKey(2, x0, y0, material), z0, z1);
        add(endpoints, new LineKey(2, x0, y1, material), z0, z1);
        add(endpoints, new LineKey(2, x1, y0, material), z0, z1);
        add(endpoints, new LineKey(2, x1, y1, material), z0, z1);
    }

    private static void add(Map<LineKey, TreeSet<Integer>> endpoints, LineKey line, int start, int end) {
        TreeSet<Integer> values = endpoints.computeIfAbsent(line, ignored -> new TreeSet<>());
        toggle(values, start);
        toggle(values, end);
        if (values.isEmpty()) {
            endpoints.remove(line);
        }
    }

    private static void toggle(TreeSet<Integer> values, int endpoint) {
        if (!values.add(endpoint)) {
            values.remove(endpoint);
        }
    }

    private record LineKey(int axis, int fixedA, int fixedB,
            TerrainCellSample.RenderCategory material) {
        private Segment segment(int start, int end) {
            return switch (axis) {
                case 0 -> new Segment(start, fixedA, fixedB, end, fixedA, fixedB, material);
                case 1 -> new Segment(fixedA, start, fixedB, fixedA, end, fixedB, material);
                default -> new Segment(fixedA, fixedB, start, fixedA, fixedB, end, material);
            };
        }
    }

    public record Segment(int x1, int y1, int z1, int x2, int y2, int z2,
            TerrainCellSample.RenderCategory material) {
        public Segment {
            java.util.Objects.requireNonNull(material, "material");
        }
    }
}
