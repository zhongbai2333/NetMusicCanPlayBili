package com.zhongbai233.net_music_can_play_bili.editor.core;

import com.zhongbai233.net_music_can_play_bili.editor.core.media.ControlConsoleVideoStatePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlConsoleVideoStatePolicyTest {
    @Test
    void stoppedOrAudioOnlySourcesAreIdleEvenWithStaleRuntimeState() {
        assertEquals(ControlConsoleVideoStatePolicy.State.IDLE,
                ControlConsoleVideoStatePolicy.resolve(false, true, true, true));
        assertEquals(ControlConsoleVideoStatePolicy.State.IDLE,
                ControlConsoleVideoStatePolicy.resolve(true, false, false, false));
    }

    @Test
    void playingVideoWaitsUntilARealFrameExists() {
        assertEquals(ControlConsoleVideoStatePolicy.State.BUFFERING,
                ControlConsoleVideoStatePolicy.resolve(true, true, false, false));
        assertEquals(ControlConsoleVideoStatePolicy.State.ACTIVE,
                ControlConsoleVideoStatePolicy.resolve(true, true, false, true));
    }

    @Test
        void failureTakesPriorityOverAStaleVisibleFrame() {
        assertEquals(ControlConsoleVideoStatePolicy.State.ERROR,
                ControlConsoleVideoStatePolicy.resolve(true, true, true, true));
    }

        @Test
        void stoppedSourceTakesPriorityOverOldFailureAndFrame() {
                assertEquals(ControlConsoleVideoStatePolicy.State.IDLE,
                                ControlConsoleVideoStatePolicy.resolve(false, true, true, true));
        }
}