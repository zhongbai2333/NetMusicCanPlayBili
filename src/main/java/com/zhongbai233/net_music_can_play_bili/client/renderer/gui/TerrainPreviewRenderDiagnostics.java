package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-scalar terrain PIP evidence.  Counters never retain frames, meshes, render states or GPU resources and are
 * therefore safe to sample from diagnostics and integrated-client workloads.
 */
public final class TerrainPreviewRenderDiagnostics {
    private static final AtomicLong MATERIAL_SECTION_UPLOADS = new AtomicLong();
    private static final AtomicLong TRANSLUCENT_SECTION_UPLOADS = new AtomicLong();
    private static final AtomicLong TRANSLUCENT_RESORTS = new AtomicLong();
    private static final AtomicLong BLOCK_ENTITY_SUBMISSIONS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();

    private TerrainPreviewRenderDiagnostics() {
    }

    static void recordSectionUpload(boolean materialLod, boolean translucent) {
        if (materialLod) {
            MATERIAL_SECTION_UPLOADS.incrementAndGet();
        }
        if (translucent) {
            TRANSLUCENT_SECTION_UPLOADS.incrementAndGet();
        }
    }

    static void recordTranslucentResort() {
        TRANSLUCENT_RESORTS.incrementAndGet();
    }

    static void recordBlockEntitySubmission() {
        BLOCK_ENTITY_SUBMISSIONS.incrementAndGet();
    }

    static void recordFailure() {
        FAILURES.incrementAndGet();
    }

    public static Snapshot snapshot() {
        return new Snapshot(MATERIAL_SECTION_UPLOADS.get(), TRANSLUCENT_SECTION_UPLOADS.get(),
                TRANSLUCENT_RESORTS.get(), BLOCK_ENTITY_SUBMISSIONS.get(), FAILURES.get());
    }

    public record Snapshot(long materialSectionUploads, long translucentSectionUploads,
            long translucentResorts, long blockEntitySubmissions, long failures) {
        public Snapshot {
            if (materialSectionUploads < 0L || translucentSectionUploads < 0L
                    || translucentResorts < 0L || blockEntitySubmissions < 0L || failures < 0L) {
                throw new IllegalArgumentException("terrain render diagnostic counters must be non-negative");
            }
        }

        public Snapshot deltaFrom(Snapshot baseline) {
            java.util.Objects.requireNonNull(baseline, "baseline");
            return new Snapshot(delta(materialSectionUploads, baseline.materialSectionUploads),
                    delta(translucentSectionUploads, baseline.translucentSectionUploads),
                    delta(translucentResorts, baseline.translucentResorts),
                    delta(blockEntitySubmissions, baseline.blockEntitySubmissions),
                    delta(failures, baseline.failures));
        }

        private static long delta(long current, long baseline) {
            return Math.max(0L, current - baseline);
        }
    }
}
