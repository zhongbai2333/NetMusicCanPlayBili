package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleExitFade;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleExitPolicy;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleRangeGate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Media entry, range gain, display state, settings and retirement boundaries. */
class ControlConsoleMediaPresentationTest {

    @Test void retiresBeforeNextObservedUpdateWouldCrossTheOriginalBudget() {
        long start = 1_000_000_000L;
        for (long cadence : new long[] {1_000_000L, 5_000_000L, 16_666_667L, 50_000_000L}) {
            long beforeLast = start + ControlConsoleExitFade.DURATION_NANOS - cadence - 1L;
            assertTrue(ControlConsoleExitFade.gainWithinUpdateBudget(start, beforeLast, cadence) > 0.0F);
            assertEquals(0.0F, ControlConsoleExitFade.gainWithinUpdateBudget(start, beforeLast + 1L, cadence));
        }
        assertEquals(250_000_000L, ControlConsoleExitFade.DURATION_NANOS);
    }
    @Test void updateBudgetKeepsTheOriginalCurveAndHandlesInvalidClockInputs() {
        long start = 1_000_000_000L;
        assertEquals(ControlConsoleExitFade.gain(start,start+125_000_000L),
                ControlConsoleExitFade.gainWithinUpdateBudget(start,start+125_000_000L,16_000_000L));
        assertEquals(1f,ControlConsoleExitFade.gainWithinUpdateBudget(start,start-1,16_000_000L));
        assertEquals(0f,ControlConsoleExitFade.gainWithinUpdateBudget(start,start+1,Long.MAX_VALUE));
        assertEquals(0f,ControlConsoleExitFade.gainWithinUpdateBudget(Long.MIN_VALUE,Long.MAX_VALUE,0));
    }
    @Test void totalBudgetIncludesTheRetirementCallbackAsWellAsNextUpdate() {
        long start=1_000_000_000L, cadence=5_000_000L;
        assertTrue(ControlConsoleExitFade.gain(start,start+242_000_000L)>0f);
        assertEquals(0f,ControlConsoleExitFade.gainWithinUpdateBudget(start,start+242_000_000L,2*cadence));
        assertEquals(250_000_000L,ControlConsoleExitFade.DURATION_NANOS);
    }

    @Test
    void firstLeaseRejectionAndNormalBoundaryExitDoNotAddAnotherFade() {
        assertFalse(ControlConsoleExitPolicy.shouldFade(false, true, false, false, 1.0F));
        assertFalse(ControlConsoleExitPolicy.shouldFade(true, true, false, false, 0.25F));
    }

    @Test
    void skippedFadeBandRangeShrinkAndSourceLossAreAbrupt() {
        assertTrue(ControlConsoleExitPolicy.shouldFade(true, true, false, false, 1.0F));
        assertTrue(ControlConsoleExitPolicy.shouldFade(true, true, true, false, 0.25F));
        assertTrue(ControlConsoleExitPolicy.shouldFade(true, false, false, false, 0.25F));
        assertTrue(ControlConsoleExitPolicy.shouldFade(true, true, false, true, 0.25F));
    }

    @Test
    void displacementOverTwoBlocksIsDiscontinuous() {
        assertFalse(ControlConsoleExitPolicy.positionDiscontinuous(0, 0, 0, 2, 0, 0));
        assertTrue(ControlConsoleExitPolicy.positionDiscontinuous(0, 0, 0, 2.01, 0, 0));
        assertFalse(ControlConsoleExitPolicy.positionDiscontinuous(Double.NaN, 0, 0, 10, 0, 0));
    }

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
        assertTrue(ControlConsoleRangeGate.evaluate(false, 5.9, 0, 0, 8, 8, 8).active());
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
