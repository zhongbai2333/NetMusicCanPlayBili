package com.zhongbai233.net_music_can_play_bili.network;

import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Playback progress precedence, persistence normalization and authoritative state updates. */
class MP4PlaybackProgressStateTest {
    @Test
    void currentElapsedPrefersRuntimeThenPersistedThenFallback() {
        MP4PlaybackProgressPolicy.RuntimeProgress runtime = new MP4PlaybackProgressPolicy.RuntimeProgress(2, 900L);

        assertEquals(900L, MP4PlaybackProgressPolicy.currentElapsed(runtime, 700L, 500L));
        assertEquals(700L, MP4PlaybackProgressPolicy.currentElapsed(null, 700L, 500L));
        assertEquals(500L, MP4PlaybackProgressPolicy.currentElapsed(null, null, 500L));
        assertEquals(0L, MP4PlaybackProgressPolicy.currentElapsed(null, null, -1L));
    }

    @Test
    void queueElapsedUsesRuntimeOnlyForTheRequestedQueueIndex() {
        MP4PlaybackProgressPolicy.RuntimeProgress runtime = new MP4PlaybackProgressPolicy.RuntimeProgress(2, 900L);

        assertEquals(900L, MP4PlaybackProgressPolicy.queueElapsed(2, runtime, 500L));
        assertEquals(500L, MP4PlaybackProgressPolicy.queueElapsed(1, runtime, 500L));
        assertEquals(0L, MP4PlaybackProgressPolicy.queueElapsed(1, null, -1L));
    }

    @Test
    void progressFallbackConvertsPerMilleAgainstDuration() {
        assertEquals(30_000L, MP4PlaybackProgressPolicy.elapsedFromProgress(120, 250));
        assertEquals(0L, MP4PlaybackProgressPolicy.elapsedFromProgress(0, 500));
    }

    @Test
    void targetClampingRetainsTheFinalFiftyMillisecondGuard() {
        assertEquals(0L, MP4PlaybackProgressPolicy.clampTarget(120, -10L));
        assertEquals(50_000L, MP4PlaybackProgressPolicy.clampTarget(120, 50_000L));
        assertEquals(119_950L, MP4PlaybackProgressPolicy.clampTarget(120, 120_000L));
        assertEquals(1_000L, MP4PlaybackProgressPolicy.clampTarget(0, 1_000L));
    }

    @Test
    void progressPerMilleClampsElapsedTimeToTheMediaDuration() {
        assertEquals(0, MP4PlaybackProgressPolicy.progressPerMille(-1L, 100));
        assertEquals(250, MP4PlaybackProgressPolicy.progressPerMille(25_000L, 100));
        assertEquals(1000, MP4PlaybackProgressPolicy.progressPerMille(150_000L, 100));
    }

    @Test
    void clampsRuntimeValuesBeforePersistence() {
        MP4PlaybackRuntimeProgress progress = new MP4PlaybackRuntimeProgress(
                -1, 20_000L, 10, 2_000, Optional.empty(), true);

        assertEquals(0, progress.queueIndex());
        assertEquals(9_950L, progress.elapsedMillis());
        assertEquals(10, progress.durationSeconds());
        assertEquals(1_000, progress.volumePerMille());
    }

}
