package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/** 视频 decoder 重启期间旧 generation 的关闭状态。 */
enum VideoDecoderRestartState {
    ACTIVE,
    CLOSING,
    FAILED_CLOSE,
    STOPPED;

    boolean pinsRegistryEntry() {
        return this == CLOSING || this == FAILED_CLOSE;
    }

    boolean isTerminalFailure() {
        return this == FAILED_CLOSE;
    }
}
