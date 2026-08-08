package com.zhongbai233.net_music_can_play_bili.editor.core;

import com.zhongbai233.net_music_can_play_bili.editor.core.media.TimedTextResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimedTextResolverTest {
    @Test
    void resolvesLatestLineAtOrBeforePlaybackTick() {
        int[] ticks = {20, 60};

        assertEquals(20, TimedTextResolver.keyAt(ticks, 20));
        assertEquals(20, TimedTextResolver.keyAt(ticks, 59));
        assertEquals(60, TimedTextResolver.keyAt(ticks, 60));
        assertEquals(60, TimedTextResolver.keyAt(ticks, 200));
    }

    @Test
    void handlesUnavailableTimelineAndIntroBeforeFirstTimestamp() {
        int[] ticks = {20};

        assertEquals(Integer.MIN_VALUE, TimedTextResolver.keyAt(ticks, -1));
        assertEquals(20, TimedTextResolver.keyAt(ticks, 0));
        assertEquals(Integer.MIN_VALUE, TimedTextResolver.keyAt(null, 20));
    }

    @Test
    void scrollProgressIsCompleteBeforeFirstLineAndAtLastLine() {
        assertEquals(1.0F, TimedTextResolver.scrollProgress(20, 60, 10));
        assertEquals(0.0F, TimedTextResolver.scrollProgress(20, 60, 20));
        assertEquals(1.0F, TimedTextResolver.scrollProgress(20, 60, 60));
        assertEquals(1.0F, TimedTextResolver.scrollProgress(60, -1, 80));
    }

    @Test
    void continuousTickProducesSmoothSubTickProgress() {
        float atStart = TimedTextResolver.scrollProgress(20, 60, 20.0F);
        float afterTenMillis = TimedTextResolver.scrollProgress(20, 60, 20.2F);
        float afterTwentyMillis = TimedTextResolver.scrollProgress(20, 60, 20.4F);

        assertTrue(afterTenMillis > atStart);
        assertTrue(afterTwentyMillis > afterTenMillis);
    }
}