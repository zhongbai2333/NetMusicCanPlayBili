package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleEntryFade;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleEntryFadeTest {
    @Test
    void fadesInMonotonicallyAndReachesOneAtDeadline() {
        long start = 1_000_000_000L;
        assertEquals(0.0F, ControlConsoleEntryFade.gain(start, start));
        assertEquals(0.5F, ControlConsoleEntryFade.gain(start, start + 125_000_000L), 1.0e-6F);
        assertEquals(1.0F, ControlConsoleEntryFade.gain(start, start + 250_000_000L));
        float previous = 0.0F;
        for (int i = 0; i <= 100; i++) {
            float gain = ControlConsoleEntryFade.gain(start, start + i * 2_500_000L);
            assertTrue(gain >= previous);
            previous = gain;
        }
    }
}