package com.zhongbai233.net_music_can_play_bili.editor.core;

import com.zhongbai233.net_music_can_play_bili.editor.core.media.ControlConsoleExitFade;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleExitFadeTest {
    @Test
    void smoothstepEnvelopeReachesHardStopAtDeadline() {
        long start = 1_000_000_000L;
        assertEquals(1.0F, ControlConsoleExitFade.gain(start, start));
        assertEquals(0.5F, ControlConsoleExitFade.gain(start,
                start + ControlConsoleExitFade.DURATION_NANOS / 2L), 0.0001F);
        assertEquals(0.0F, ControlConsoleExitFade.gain(start,
                start + ControlConsoleExitFade.DURATION_NANOS));
        assertFalse(ControlConsoleExitFade.finished(start,
                start + ControlConsoleExitFade.DURATION_NANOS - 1L));
        assertTrue(ControlConsoleExitFade.finished(start,
                start + ControlConsoleExitFade.DURATION_NANOS));
    }

    @Test
    void clockAnomaliesCannotProduceGainOutsideUnitInterval() {
        assertEquals(1.0F, ControlConsoleExitFade.gain(100L, 99L));
        assertEquals(0.0F, ControlConsoleExitFade.gain(Long.MIN_VALUE, Long.MAX_VALUE));
    }
}