package com.zhongbai233.net_music_can_play_bili.blockentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurntableRedstoneModeTest {
    @Test
    void mapsPlaybackModesAndCycleOrder() {
        assertTrue(TurntableRedstoneMode.HIGH_SIGNAL.shouldPlay(true));
        assertFalse(TurntableRedstoneMode.HIGH_SIGNAL.shouldPlay(false));
        assertFalse(TurntableRedstoneMode.LOW_SIGNAL.shouldPlay(true));
        assertTrue(TurntableRedstoneMode.LOW_SIGNAL.shouldPlay(false));
        assertTrue(TurntableRedstoneMode.IGNORE.shouldPlay(true));
        assertTrue(TurntableRedstoneMode.IGNORE.shouldPlay(false));
        assertTrue(TurntableRedstoneMode.PULSE_TOGGLE.shouldPlay(true));
        assertTrue(TurntableRedstoneMode.PULSE_TOGGLE.shouldPlay(false));

        assertSame(TurntableRedstoneMode.HIGH_SIGNAL, TurntableRedstoneMode.IGNORE.next());
        assertSame(TurntableRedstoneMode.LOW_SIGNAL, TurntableRedstoneMode.HIGH_SIGNAL.next());
        assertSame(TurntableRedstoneMode.PULSE_TOGGLE, TurntableRedstoneMode.LOW_SIGNAL.next());
        assertSame(TurntableRedstoneMode.IGNORE, TurntableRedstoneMode.PULSE_TOGGLE.next());
    }

    @Test
    void mapsPersistedNames() {
        assertSame(TurntableRedstoneMode.HIGH_SIGNAL, TurntableRedstoneMode.byName("high_signal"));
        assertSame(TurntableRedstoneMode.LOW_SIGNAL, TurntableRedstoneMode.byName("low_signal"));
        assertSame(TurntableRedstoneMode.PULSE_TOGGLE, TurntableRedstoneMode.byName("pulse_toggle"));
        assertSame(TurntableRedstoneMode.IGNORE, TurntableRedstoneMode.byName("unknown"));
    }
}