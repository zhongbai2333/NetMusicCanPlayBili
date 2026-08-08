package com.zhongbai233.net_music_can_play_bili.editor.core.media;

/** 中控台屏幕状态决策；真实视频帧与占位纹理由客户端适配层提供。 */
public final class ControlConsoleVideoStatePolicy {
    public enum State {
        IDLE,
        BUFFERING,
        ERROR,
        ACTIVE
    }

    private ControlConsoleVideoStatePolicy() {
    }

    public static State resolve(boolean sourcePlaying, boolean videoExpected, boolean failed,
            boolean realFrameAvailable) {
        if (!sourcePlaying || !videoExpected) {
            return State.IDLE;
        }
        if (failed) {
            return State.ERROR;
        }
        if (realFrameAvailable) {
            return State.ACTIVE;
        }
        return State.BUFFERING;
    }
}