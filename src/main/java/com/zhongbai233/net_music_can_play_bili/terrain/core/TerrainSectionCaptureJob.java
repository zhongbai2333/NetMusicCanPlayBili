package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 单个 section 的分帧捕获任务。调用方必须在 Minecraft 主线程调用 step，读取器不得触发区块加载。
 */
public final class TerrainSectionCaptureJob {
    private static final long ESTIMATED_CELL_BYTES = 24L;

    private final TerrainSectionKey key;
    private final long generation;
    private final TerrainCellReader reader;
    private final TerrainCellSample[] cells = new TerrainCellSample[TerrainSectionKey.CELL_COUNT];
    private int cursor;

    public TerrainSectionCaptureJob(TerrainSectionKey key, long generation, TerrainCellReader reader) {
        this.key = java.util.Objects.requireNonNull(key, "key");
        if (generation < 0L) {
            throw new IllegalArgumentException("capture generation must be non-negative");
        }
        this.generation = generation;
        this.reader = java.util.Objects.requireNonNull(reader, "reader");
    }

    /** 消费当前 tick 的预算；预算耗尽或任务完成时立即返回。 */
    public StepResult step(TerrainSamplingBudget.Meter meter) {
        java.util.Objects.requireNonNull(meter, "meter");
        if (done()) {
            return new StepResult(0, true);
        }
        if (!meter.tryAcquireSection()) {
            return new StepResult(0, false);
        }
        int sampled = 0;
        while (!done() && meter.tryAcquireCell()) {
            int localX = cursor % TerrainSectionKey.SIZE;
            int localZ = (cursor / TerrainSectionKey.SIZE) % TerrainSectionKey.SIZE;
            int localY = cursor / (TerrainSectionKey.SIZE * TerrainSectionKey.SIZE);
            TerrainCellSample sample = reader.read(key.minBlockX() + localX, key.minBlockY() + localY,
                    key.minBlockZ() + localZ);
            cells[cursor] = sample != null ? sample : TerrainCellSample.unknown();
            cursor++;
            sampled++;
        }
        return new StepResult(sampled, done());
    }

    public boolean done() {
        return cursor >= cells.length;
    }

    public int sampledCells() {
        return cursor;
    }

    public Optional<TerrainSectionSnapshot> completedSnapshot() {
        if (!done()) {
            return Optional.empty();
        }
        List<TerrainCellSample> immutableCells = new ArrayList<>(cells.length);
        java.util.Collections.addAll(immutableCells, cells);
        return Optional.of(new TerrainSectionSnapshot(key, generation, immutableCells,
                ESTIMATED_CELL_BYTES * cells.length));
    }

    @FunctionalInterface
    public interface TerrainCellReader {
        TerrainCellSample read(int blockX, int blockY, int blockZ);
    }

    public record StepResult(int sampledCells, boolean completed) {
    }
}