package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic representative-material aggregation for textured MID/FAR terrain cells.
 *
 * <p>The most frequent material wins inside each cell. Ties prefer the highest source voxel so exposed surfaces
 * retain the material a player would see from above, then the earliest input for stable rebuilds.</p>
 */
public final class TerrainMaterialAggregator {
    private TerrainMaterialAggregator() {
    }

    public static <T> List<Cell<T>> aggregate(List<Sample<T>> visible, int size) {
        Objects.requireNonNull(visible, "visible");
        if (size <= 0 || TerrainSectionKey.SIZE % size != 0) {
            throw new IllegalArgumentException("material cell size must divide section size");
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
            cells.add(new Cell<>(gx * size, gy * size, gz * size, size,
                    entry.getValue().representative()));
        }
        return List.copyOf(cells);
    }

    public record Sample<T>(int localX, int localY, int localZ, T material) {
        public Sample {
            if (localX < 0 || localX >= TerrainSectionKey.SIZE
                    || localY < 0 || localY >= TerrainSectionKey.SIZE
                    || localZ < 0 || localZ >= TerrainSectionKey.SIZE) {
                throw new IllegalArgumentException("terrain material sample coordinates must be within [0, 15]");
            }
            Objects.requireNonNull(material, "material");
        }
    }

    public record Cell<T>(int localX, int localY, int localZ, int size, Sample<T> representative) {
        public Cell {
            if (size <= 0 || localX < 0 || localY < 0 || localZ < 0
                    || localX + size > TerrainSectionKey.SIZE
                    || localY + size > TerrainSectionKey.SIZE
                    || localZ + size > TerrainSectionKey.SIZE) {
                throw new IllegalArgumentException("terrain material cell must fit inside its section");
            }
            Objects.requireNonNull(representative, "representative");
        }
    }

    private static final class Group<T> {
        private final Map<T, Choice<T>> choices = new HashMap<>();

        private void accept(Sample<T> sample, int order) {
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
            return Objects.requireNonNull(winner, "empty material group").sample;
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
