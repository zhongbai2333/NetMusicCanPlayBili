package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioStartupSyncTest {
    @Test
    void compensatesAudioStartupLatency() {
        long capturedNanos = 10_000_000_000L;
        long nowNanos = capturedNanos + 17_431_999_999L;

        assertEquals(190_495L, AudioStartupSync.compensatedElapsedMillis(
                173_064L, 300_000L, capturedNanos, nowNanos));
        assertEquals(300_000L, AudioStartupSync.compensatedElapsedMillis(
                295_000L, 300_000L, capturedNanos, nowNanos));
        assertEquals(190_495L, AudioStartupSync.compensatedOffsetMillis(173_064L, 300_000L, 17_431L));
        assertEquals(0L, AudioStartupSync.elapsedSinceCaptureMillis(capturedNanos, capturedNanos - 1L));
    }
}