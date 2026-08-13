package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleExitPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleExitPolicyTest {
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
}