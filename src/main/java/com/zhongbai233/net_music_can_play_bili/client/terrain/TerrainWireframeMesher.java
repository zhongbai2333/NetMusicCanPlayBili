package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 剔除聚合体素的内部面，在空气接触面保留 4/8 格网格，并叠加稳定的 16 格区块轮廓。
 * 所有共线、重合和首尾相接的边最终只输出一次。
 */
public final class TerrainWireframeMesher {
    private static final int FACE_PATCH_SIZE = 4;
    private static final int SECTION_SIZE = 16;

    private TerrainWireframeMesher() {
    }

    public static List<Segment> mesh(List<TerrainOverviewCell> cells, TerrainBounds bounds) {
        java.util.Objects.requireNonNull(cells, "cells");
        java.util.Objects.requireNonNull(bounds, "bounds");
        Map<BoxKey, BoxAccumulator> boxes = new HashMap<>();
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
            boxes.computeIfAbsent(new BoxKey(x0, y0, z0, x1, y1, z1), ignored -> new BoxAccumulator())
                    .add(cell.material(), cell.color());
        }
        Map<LineKey, List<Interval>> intervals = new HashMap<>();
        addExposedFaceEdges(boxes, intervals);
        addExposedFaceEdges(sectionBoxes(boxes, bounds), intervals);
        List<LineKey> lines = new ArrayList<>(intervals.keySet());
        lines.sort(Comparator.comparingInt((LineKey line) -> line.axis())
            .thenComparingInt(line -> line.fixedA())
            .thenComparingInt(line -> line.fixedB()));
        List<Segment> result = new ArrayList<>();
        for (LineKey line : lines) {
            List<Interval> values = intervals.get(line);
            values.sort(Comparator.comparingInt((Interval interval) -> interval.start())
                    .thenComparingInt(interval -> interval.end()));
            int start = values.getFirst().start();
            int end = values.getFirst().end();
            LineAccumulator accumulator = new LineAccumulator();
            accumulator.add(values.getFirst());
            for (int index = 1; index < values.size(); index++) {
                Interval interval = values.get(index);
                if (interval.start() <= end) {
                    end = Math.max(end, interval.end());
                    accumulator.add(interval);
                } else {
                    result.add(line.segment(start, end, accumulator.material(), accumulator.color()));
                    start = interval.start();
                    end = interval.end();
                    accumulator = new LineAccumulator();
                    accumulator.add(interval);
                }
            }
            result.add(line.segment(start, end, accumulator.material(), accumulator.color()));
        }
        return List.copyOf(result);
    }

    private static Map<BoxKey, BoxAccumulator> sectionBoxes(
            Map<BoxKey, BoxAccumulator> macroBoxes, TerrainBounds bounds) {
        Map<BoxKey, BoxAccumulator> sections = new HashMap<>();
        for (Map.Entry<BoxKey, BoxAccumulator> entry : macroBoxes.entrySet()) {
            BoxKey box = entry.getKey();
            BoxStyle style = entry.getValue().style();
            int firstX = Math.floorDiv(box.x0(), SECTION_SIZE) * SECTION_SIZE;
            int firstY = Math.floorDiv(box.y0(), SECTION_SIZE) * SECTION_SIZE;
            int firstZ = Math.floorDiv(box.z0(), SECTION_SIZE) * SECTION_SIZE;
            int lastX = Math.floorDiv(box.x1() - 1, SECTION_SIZE) * SECTION_SIZE;
            int lastY = Math.floorDiv(box.y1() - 1, SECTION_SIZE) * SECTION_SIZE;
            int lastZ = Math.floorDiv(box.z1() - 1, SECTION_SIZE) * SECTION_SIZE;
            for (int sectionY = firstY; sectionY <= lastY; sectionY += SECTION_SIZE) {
                for (int sectionZ = firstZ; sectionZ <= lastZ; sectionZ += SECTION_SIZE) {
                    for (int sectionX = firstX; sectionX <= lastX; sectionX += SECTION_SIZE) {
                        int x0 = Math.max(sectionX, bounds.minX());
                        int y0 = Math.max(sectionY, bounds.minY());
                        int z0 = Math.max(sectionZ, bounds.minZ());
                        int x1 = Math.min(sectionX + SECTION_SIZE, bounds.maxX() + 1);
                        int y1 = Math.min(sectionY + SECTION_SIZE, bounds.maxY() + 1);
                        int z1 = Math.min(sectionZ + SECTION_SIZE, bounds.maxZ() + 1);
                        if (x1 <= x0 || y1 <= y0 || z1 <= z0) {
                            continue;
                        }
                        sections.computeIfAbsent(new BoxKey(x0, y0, z0, x1, y1, z1),
                                ignored -> new BoxAccumulator()).add(style.material(), style.color());
                    }
                }
            }
        }
        return sections;
    }

    private static void addExposedFaceEdges(Map<BoxKey, BoxAccumulator> boxes,
            Map<LineKey, List<Interval>> intervals) {
        List<Face> faces = new ArrayList<>(boxes.size() * 6);
        Map<FaceKey, FaceOccupancy> patches = new HashMap<>();
        for (Map.Entry<BoxKey, BoxAccumulator> entry : boxes.entrySet()) {
            BoxKey box = entry.getKey();
            BoxStyle style = entry.getValue().style();
            addFace(faces, patches,
                    new Face(0, box.x0(), box.y0(), box.y1(), box.z0(), box.z1(), 1, style));
            addFace(faces, patches,
                    new Face(0, box.x1(), box.y0(), box.y1(), box.z0(), box.z1(), -1, style));
            addFace(faces, patches,
                    new Face(1, box.y0(), box.x0(), box.x1(), box.z0(), box.z1(), 1, style));
            addFace(faces, patches,
                    new Face(1, box.y1(), box.x0(), box.x1(), box.z0(), box.z1(), -1, style));
            addFace(faces, patches,
                    new Face(2, box.z0(), box.x0(), box.x1(), box.y0(), box.y1(), 1, style));
            addFace(faces, patches,
                    new Face(2, box.z1(), box.x0(), box.x1(), box.y0(), box.y1(), -1, style));
        }
        for (Face face : faces) {
            Set<EdgeKey> boundary = new HashSet<>();
            for (FaceKey patch : split(face)) {
                FaceOccupancy occupancy = patches.get(patch);
                if (occupancy != null && !occupancy.hasOpposite(face.side())) {
                    toggleFaceEdges(boundary, patch);
                }
            }
            for (EdgeKey edge : boundary) {
                add(intervals, edge.line(), edge.start(), edge.end(), face.style());
            }
        }
    }

    private static void addFace(List<Face> faces, Map<FaceKey, FaceOccupancy> patches, Face face) {
        faces.add(face);
        for (FaceKey patch : split(face)) {
            patches.computeIfAbsent(patch, ignored -> new FaceOccupancy()).add(face.side());
        }
    }

    private static List<FaceKey> split(Face face) {
        List<Integer> aCuts = cuts(face.a0(), face.a1());
        List<Integer> bCuts = cuts(face.b0(), face.b1());
        List<FaceKey> patches = new ArrayList<>((aCuts.size() - 1) * (bCuts.size() - 1));
        for (int a = 0; a + 1 < aCuts.size(); a++) {
            for (int b = 0; b + 1 < bCuts.size(); b++) {
                patches.add(new FaceKey(face.axis(), face.plane(),
                        aCuts.get(a), aCuts.get(a + 1), bCuts.get(b), bCuts.get(b + 1)));
            }
        }
        return patches;
    }

    private static List<Integer> cuts(int start, int end) {
        List<Integer> cuts = new ArrayList<>();
        cuts.add(start);
        int cut = Math.floorDiv(start, FACE_PATCH_SIZE) * FACE_PATCH_SIZE;
        if (cut <= start) {
            cut += FACE_PATCH_SIZE;
        }
        while (cut < end) {
            cuts.add(cut);
            cut += FACE_PATCH_SIZE;
        }
        cuts.add(end);
        return cuts;
    }

    private static void toggleFaceEdges(Set<EdgeKey> edges, FaceKey face) {
        switch (face.axis()) {
            case 0 -> {
                toggle(edges, new EdgeKey(new LineKey(1, face.plane(), face.b0()), face.a0(), face.a1()));
                toggle(edges, new EdgeKey(new LineKey(1, face.plane(), face.b1()), face.a0(), face.a1()));
                toggle(edges, new EdgeKey(new LineKey(2, face.plane(), face.a0()), face.b0(), face.b1()));
                toggle(edges, new EdgeKey(new LineKey(2, face.plane(), face.a1()), face.b0(), face.b1()));
            }
            case 1 -> {
                toggle(edges, new EdgeKey(new LineKey(0, face.plane(), face.b0()), face.a0(), face.a1()));
                toggle(edges, new EdgeKey(new LineKey(0, face.plane(), face.b1()), face.a0(), face.a1()));
                toggle(edges, new EdgeKey(new LineKey(2, face.a0(), face.plane()), face.b0(), face.b1()));
                toggle(edges, new EdgeKey(new LineKey(2, face.a1(), face.plane()), face.b0(), face.b1()));
            }
            default -> {
                toggle(edges, new EdgeKey(new LineKey(0, face.b0(), face.plane()), face.a0(), face.a1()));
                toggle(edges, new EdgeKey(new LineKey(0, face.b1(), face.plane()), face.a0(), face.a1()));
                toggle(edges, new EdgeKey(new LineKey(1, face.a0(), face.plane()), face.b0(), face.b1()));
                toggle(edges, new EdgeKey(new LineKey(1, face.a1(), face.plane()), face.b0(), face.b1()));
            }
        }
    }

    private static void toggle(Set<EdgeKey> edges, EdgeKey edge) {
        if (!edges.add(edge)) {
            edges.remove(edge);
        }
    }

    private static void add(Map<LineKey, List<Interval>> intervals, LineKey line,
            int start, int end, BoxStyle style) {
        intervals.computeIfAbsent(line, ignored -> new ArrayList<>())
                .add(new Interval(start, end, style.material(), style.color()));
    }

    private record LineKey(int axis, int fixedA, int fixedB) {
        private Segment segment(int start, int end,
                TerrainCellSample.RenderCategory material, int color) {
            return switch (axis) {
                case 0 -> new Segment(start, fixedA, fixedB, end, fixedA, fixedB, material, color);
                case 1 -> new Segment(fixedA, start, fixedB, fixedA, end, fixedB, material, color);
                default -> new Segment(fixedA, fixedB, start, fixedA, fixedB, end, material, color);
            };
        }
    }

    private record BoxKey(int x0, int y0, int z0, int x1, int y1, int z1) {
    }

    private record Face(int axis, int plane, int a0, int a1, int b0, int b1,
            int side, BoxStyle style) {
    }

    /** axis 0/1/2 对应法线 X/Y/Z；a、b 是该平面内的两个坐标区间。 */
    private record FaceKey(int axis, int plane, int a0, int a1, int b0, int b1) {
    }

    private static final class FaceOccupancy {
        private boolean negative;
        private boolean positive;

        private void add(int side) {
            if (side < 0) {
                negative = true;
            } else {
                positive = true;
            }
        }

        private boolean hasOpposite(int side) {
            return side < 0 ? positive : negative;
        }
    }

    private record EdgeKey(LineKey line, int start, int end) {
    }

    private record BoxStyle(TerrainCellSample.RenderCategory material, int color) {
    }

    private static final class BoxAccumulator {
        private long red;
        private long green;
        private long blue;
        private int colorCount;
        private TerrainCellSample.RenderCategory material = TerrainCellSample.RenderCategory.UNKNOWN;

        private void add(TerrainCellSample.RenderCategory candidate, int color) {
            if (candidate != TerrainCellSample.RenderCategory.UNKNOWN) {
                material = candidate;
            }
            if (color != 0) {
                red += color >>> 16 & 0xFF;
                green += color >>> 8 & 0xFF;
                blue += color & 0xFF;
                colorCount++;
            }
        }

        private BoxStyle style() {
            if (colorCount == 0) {
                return new BoxStyle(material, 0);
            }
            int half = colorCount / 2;
            int r = (int) ((red + half) / colorCount);
            int g = (int) ((green + half) / colorCount);
            int b = (int) ((blue + half) / colorCount);
            return new BoxStyle(material, 0xFF000000 | r << 16 | g << 8 | b);
        }
    }

    private record Interval(int start, int end,
            TerrainCellSample.RenderCategory material, int color) {
    }

    private static final class LineAccumulator {
        private long red;
        private long green;
        private long blue;
        private long weight;
        private TerrainCellSample.RenderCategory material = TerrainCellSample.RenderCategory.UNKNOWN;

        private void add(Interval interval) {
            if (interval.material() != TerrainCellSample.RenderCategory.UNKNOWN) {
                material = interval.material();
            }
            if (interval.color() == 0) {
                return;
            }
            int intervalWeight = Math.max(1, interval.end() - interval.start());
            red += (long) (interval.color() >>> 16 & 0xFF) * intervalWeight;
            green += (long) (interval.color() >>> 8 & 0xFF) * intervalWeight;
            blue += (long) (interval.color() & 0xFF) * intervalWeight;
            weight += intervalWeight;
        }

        private TerrainCellSample.RenderCategory material() {
            return material;
        }

        private int color() {
            if (weight == 0L) {
                return 0;
            }
            int half = (int) (weight / 2L);
            int r = (int) ((red + half) / weight);
            int g = (int) ((green + half) / weight);
            int b = (int) ((blue + half) / weight);
            return 0xFF000000 | r << 16 | g << 8 | b;
        }
    }

    public record Segment(int x1, int y1, int z1, int x2, int y2, int z2,
            TerrainCellSample.RenderCategory material, int color) {
        public Segment(int x1, int y1, int z1, int x2, int y2, int z2,
                TerrainCellSample.RenderCategory material) {
            this(x1, y1, z1, x2, y2, z2, material, 0);
        }

        public Segment {
            java.util.Objects.requireNonNull(material, "material");
            if (color != 0 && (color & 0xFF000000) != 0xFF000000) {
                throw new IllegalArgumentException("wire color must be zero or opaque ARGB");
            }
        }
    }
}
