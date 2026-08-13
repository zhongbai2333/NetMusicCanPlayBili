package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleRangeGate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleRangeGateTest {
    @Test
    void activeConsumerFadesTowardNearestFaceAndStopsOnBoundary() {
        var center = ControlConsoleRangeGate.evaluate(true, 0, 0, 0, 8, 8, 8);
        var band = ControlConsoleRangeGate.evaluate(true, 6, 0, 0, 8, 8, 8);
        var boundary = ControlConsoleRangeGate.evaluate(true, 8, 0, 0, 8, 8, 8);

        assertTrue(center.active());
        assertEquals(1.0F, center.gain());
        assertEquals(0.5F, band.gain(), 0.0001F);
        assertFalse(boundary.active());
        assertEquals(0.0F, boundary.gain());
    }

    @Test
    void inactiveConsumerRequiresAllAxesInsideReentryBox() {
        assertFalse(ControlConsoleRangeGate.evaluate(false, 6.1, 0, 0, 8, 8, 8).active());
        assertFalse(ControlConsoleRangeGate.evaluate(false, 0, 6.1, 0, 8, 8, 8).active());
        assertTrue(ControlConsoleRangeGate.evaluate(false, 5.9, 5.9, 5.9, 8, 8, 8).active());
    }

    @Test
    void smallRangesRetainNonEmptyReentryInterior() {
        assertTrue(ControlConsoleRangeGate.evaluate(false, 0, 0, 0, 0.5, 0.5, 0.5).active());
        assertFalse(ControlConsoleRangeGate.evaluate(false, 0.0001, 0, 0, 0.5, 0.5, 0.5).active());
        assertTrue(ControlConsoleRangeGate.evaluate(false, 0.9, 0, 0, 3, 3, 3).active());
        assertFalse(ControlConsoleRangeGate.evaluate(false, 1.1, 0, 0, 3, 3, 3).active());
    }
}