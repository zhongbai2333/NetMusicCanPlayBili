package com.zhongbai233.net_music_can_play_bili.media.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAlPauseStatePolicyTest {
    @Test
    void pausesOnlyPlayingSources() {
        assertEquals(OpenAlPauseStatePolicy.Action.PAUSE,
                OpenAlPauseStatePolicy.action(true, OpenAlPauseStatePolicy.SourceState.PLAYING, 4));
        assertEquals(OpenAlPauseStatePolicy.Action.NONE,
                OpenAlPauseStatePolicy.action(true, OpenAlPauseStatePolicy.SourceState.PAUSED, 4));
        assertEquals(OpenAlPauseStatePolicy.Action.NONE,
                OpenAlPauseStatePolicy.action(true, OpenAlPauseStatePolicy.SourceState.STOPPED, 4));
        assertEquals(OpenAlPauseStatePolicy.Action.NONE,
                OpenAlPauseStatePolicy.action(true, OpenAlPauseStatePolicy.SourceState.OTHER, 4));
    }

    @Test
    void resumesPausedOrQueuedStoppedSources() {
        assertEquals(OpenAlPauseStatePolicy.Action.PLAY,
                OpenAlPauseStatePolicy.action(false, OpenAlPauseStatePolicy.SourceState.PAUSED, 4));
        assertEquals(OpenAlPauseStatePolicy.Action.PLAY,
                OpenAlPauseStatePolicy.action(false, OpenAlPauseStatePolicy.SourceState.STOPPED, 4));
        assertEquals(OpenAlPauseStatePolicy.Action.NONE,
                OpenAlPauseStatePolicy.action(false, OpenAlPauseStatePolicy.SourceState.STOPPED, 0));
        assertEquals(OpenAlPauseStatePolicy.Action.NONE,
                OpenAlPauseStatePolicy.action(false, OpenAlPauseStatePolicy.SourceState.PLAYING, 4));
        assertEquals(OpenAlPauseStatePolicy.Action.NONE,
                OpenAlPauseStatePolicy.action(false, OpenAlPauseStatePolicy.SourceState.OTHER, 4));
    }
}
