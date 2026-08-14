package com.zhongbai233.net_music_can_play_bili.client;

import java.io.IOException;

final class SustainedPerformanceFallbackException extends IOException {
    final String reason;

    SustainedPerformanceFallbackException(String reason) {
        this(reason, null);
    }

    SustainedPerformanceFallbackException(String reason, Throwable cause) {
        super("AV1 sustained performance fallback: " + reason, cause);
        this.reason = reason == null || reason.isBlank() ? "performance" : reason;
    }
}
