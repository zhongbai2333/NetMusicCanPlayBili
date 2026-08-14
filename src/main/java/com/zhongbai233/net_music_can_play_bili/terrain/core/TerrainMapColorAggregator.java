package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed-size macro voxel aggregation using the arithmetic mean of source map colors. */
public final class TerrainMapColorAggregator {
    private TerrainMapColorAggregator() {
    }

    public static <T> List<Cell<T>> aggregate(List<Sample<T>> visible, int size) {
        Objects.requireNonNull(visible, "visible");
        if (size <= 0 || TerrainSectionKey.SIZE % size != 0) {
            throw new IllegalArgumentException("map-color cell size must divide section size");
        }
        int dimension = TerrainSectionKey.SIZE / size;
        Map<Integer, Group<T>> groups = new LinkedHashMap<>();
        int order = 0;
        for (Sample<T> sample : visible) {
            int gx = sample.localX() / size;
            int gy = sample.localY() / size;
            int gz = sample.localZ() / size;
            int index = (gy * dimension + gz) * dimension + gx;
            groups.computeIfAbsent(index, ignored -> new Group<>()).accept(sample, order++);
        }
        List<Cell<T>> cells = new ArrayList<>(groups.size());
        for (Map.Entry<Integer, Group<T>> entry : groups.entrySet()) {
            int index = entry.getKey();
            int gx = index % dimension;
            int gz = index / dimension % dimension;
            int gy = index / (dimension * dimension);
            Group<T> group = entry.getValue();
            cells.add(new Cell<>(gx * size, gy * size, gz * size, size,
                    group.averageColor(), group.representative()));
        }
        return List.copyOf(cells);
    }

    public record Sample<T>(int localX, int localY, int localZ, T material, int mapColor) {
        public Sample {
            if (localX < 0 || localX >= TerrainSectionKey.SIZE
                    || localY < 0 || localY >= TerrainSectionKey.SIZE
                    || localZ < 0 || localZ >= TerrainSectionKey.SIZE) {
                throw new IllegalArgumentException("terrain map-color sample coordinates must be within [0, 15]");
            }
            Objects.requireNonNull(material, "material");
            mapColor &= 0x00FFFFFF;
        }
    }

    public record Cell<T>(int localX, int localY, int localZ, int size, int color,
            Sample<T> representative) {
        public Cell {
            if (size <= 0 || localX < 0 || localY < 0 || localZ < 0
                    || localX + size > TerrainSectionKey.SIZE
                    || localY + size > TerrainSectionKey.SIZE
                    || localZ + size > TerrainSectionKey.SIZE) {
                throw new IllegalArgumentException("terrain map-color cell must fit inside its section");
            }
            if ((color & 0xFF000000) != 0xFF000000) {
                throw new IllegalArgumentException("terrain map-color cell must be opaque");
            }
            Objects.requireNonNull(representative, "representative");
        }
    }

    private static final class Group<T> {
        private final Map<T, Choice<T>> choices = new HashMap<>();
        private long red;
        private long green;
        private long blue;
        private int count;

        private void accept(Sample<T> sample, int order) {
            red += sample.mapColor() >>> 16 & 0xFF;
            green += sample.mapColor() >>> 8 & 0xFF;
            blue += sample.mapColor() & 0xFF;
            count++;
            Choice<T> choice = choices.get(sample.material());
            if (choice == null) {
                choices.put(sample.material(), new Choice<>(sample, 1, order));
            } else {
                choice.count++;
                if (sample.localY() > choice.sample.localY()) {
                    choice.sample = sample;
                }
            }
        }

        private int averageColor() {
            int half = count / 2;
            int r = (int) ((red + half) / count);
            int g = (int) ((green + half) / count);
            int b = (int) ((blue + half) / count);
            return 0xFF000000 | r << 16 | g << 8 | b;
        }

        private Sample<T> representative() {
            Choice<T> winner = null;
            for (Choice<T> choice : choices.values()) {
                if (winner == null || choice.count > winner.count
                        || choice.count == winner.count && choice.sample.localY() > winner.sample.localY()
                        || choice.count == winner.count && choice.sample.localY() == winner.sample.localY()
                            && choice.firstOrder < winner.firstOrder) {
                    winner = choice;
                }
            }
            return Objects.requireNonNull(winner, "empty map-color group").sample;
        }
    }

    private static final class Choice<T> {
        private Sample<T> sample;
        private int count;
        private final int firstOrder;

        private Choice(Sample<T> sample, int count, int firstOrder) {
            this.sample = sample;
            this.count = count;
            this.firstOrder = firstOrder;
        }
    }
}
