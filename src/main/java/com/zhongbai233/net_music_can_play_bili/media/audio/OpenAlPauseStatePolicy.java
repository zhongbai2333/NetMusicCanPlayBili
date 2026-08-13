package com.zhongbai233.net_music_can_play_bili.media.audio;

/** Pure decision policy for pausing and resuming an owned OpenAL source. */
final class OpenAlPauseStatePolicy {
    private OpenAlPauseStatePolicy() {
    }

    static Action action(boolean pause, SourceState sourceState, int queuedBuffers) {
        if (pause) {
            return sourceState == SourceState.PLAYING ? Action.PAUSE : Action.NONE;
        }
        return sourceState == SourceState.PAUSED
                || (sourceState == SourceState.STOPPED && queuedBuffers > 0) ? Action.PLAY : Action.NONE;
    }

    enum SourceState {
        PLAYING,
        PAUSED,
        STOPPED,
        OTHER
    }

    enum Action {
        NONE,
        PAUSE,
        PLAY
    }
}
