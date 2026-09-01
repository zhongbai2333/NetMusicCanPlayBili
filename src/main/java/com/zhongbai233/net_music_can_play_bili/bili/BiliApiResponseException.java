package com.zhongbai233.net_music_can_play_bili.bili;

/** Bilibili responded, but the HTTP/JSON envelope was unusable for playback resolution. */
public final class BiliApiResponseException extends Exception {
    public BiliApiResponseException(String message) {
        super(message);
    }

    public BiliApiResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
