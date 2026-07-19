package com.zhongbai233.net_music_can_play_bili.server;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackReportTrackerTest {
    @Test
    void aggregatesReportsAndHonorsCooldown() {
        PlaybackReportTracker<UUID> tracker = new PlaybackReportTracker<>(900L, 5);
        UUID reporter = UUID.randomUUID();

        PlaybackReportTracker.Decision<UUID> first = tracker.record("source", reporter, 100L);
        assertTrue(first.shouldNotify());
        assertFalse(first.merged());
        tracker.markNotified("source", 100L, false);

        PlaybackReportTracker.Decision<UUID> merged = tracker.record("source", reporter, 200L);
        assertFalse(merged.shouldNotify());
        assertTrue(merged.merged());
        assertEquals(1, merged.snapshot().uniqueReporterCount());

        PlaybackReportTracker.Decision<UUID> afterCooldown = tracker.record("source", UUID.randomUUID(), 1_000L);
        assertTrue(afterCooldown.shouldNotify());
        assertEquals(3, afterCooldown.snapshot().totalReports());
        assertEquals(2, afterCooldown.snapshot().uniqueReporterCount());
    }

    @Test
    void notifiesOnceAtLimitThenSilentlyMerges() {
        PlaybackReportTracker<Integer> tracker = new PlaybackReportTracker<>(10_000L, 3);
        tracker.markNotified("missing", 0L, false);
        tracker.record("source", 1, 0L);
        tracker.markNotified("source", 0L, false);
        assertFalse(tracker.record("source", 2, 1L).shouldNotify());

        PlaybackReportTracker.Decision<Integer> limit = tracker.record("source", 3, 2L);
        assertTrue(limit.shouldNotify());
        assertTrue(limit.reachedLimit());
        PlaybackReportTracker.Snapshot<Integer> marked = tracker.markNotified("source", 2L, true);
        assertTrue(marked.reminderLimitReached());

        PlaybackReportTracker.Decision<Integer> later = tracker.record("source", 4, 20_000L);
        assertFalse(later.shouldNotify());
        assertTrue(later.snapshot().reminderLimitReached());
    }

    @Test
    void removesReportsForInactiveSources() {
        PlaybackReportTracker<Integer> tracker = new PlaybackReportTracker<>(100L, 5);
        tracker.record("keep", 1, 0L);
        tracker.record("remove", 2, 0L);

        tracker.retainSources(Set.of("keep"));

        assertEquals(java.util.List.of("keep"), tracker.snapshots().stream()
                .map(entry -> entry.sourceKey()).toList());
    }
}