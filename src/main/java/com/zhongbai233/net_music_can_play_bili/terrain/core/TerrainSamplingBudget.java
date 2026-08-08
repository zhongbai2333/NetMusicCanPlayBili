package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.function.LongSupplier;

/** 单 tick 采样的单元数、section 数和墙钟时间三重硬上限。 */
public record TerrainSamplingBudget(int maxCells, int maxSections, long maxNanos) {
    public TerrainSamplingBudget {
        if (maxCells <= 0 || maxSections <= 0 || maxNanos <= 0L) {
            throw new IllegalArgumentException("terrain sampling budgets must be positive");
        }
    }

    public static TerrainSamplingBudget normal() {
        return new TerrainSamplingBudget(512, 2, 1_500_000L);
    }

    public Meter start() {
        return start(System::nanoTime);
    }

    Meter start(LongSupplier clock) {
        return new Meter(this, clock);
    }

    public static final class Meter {
        private final TerrainSamplingBudget budget;
        private final LongSupplier clock;
        private final long startNanos;
        private int cells;
        private int sections;

        private Meter(TerrainSamplingBudget budget, LongSupplier clock) {
            this.budget = budget;
            this.clock = java.util.Objects.requireNonNull(clock, "clock");
            this.startNanos = clock.getAsLong();
        }

        public boolean tryAcquireSection() {
            if (sections >= budget.maxSections || expired()) {
                return false;
            }
            sections++;
            return true;
        }

        public boolean tryAcquireCell() {
            if (cells >= budget.maxCells || expired()) {
                return false;
            }
            cells++;
            return true;
        }

        public boolean exhausted() {
            return cells >= budget.maxCells || sections >= budget.maxSections || expired();
        }

        public int consumedCells() {
            return cells;
        }

        public int consumedSections() {
            return sections;
        }

        public long elapsedNanos() {
            return Math.max(0L, clock.getAsLong() - startNanos);
        }

        private boolean expired() {
            return elapsedNanos() >= budget.maxNanos;
        }
    }
}