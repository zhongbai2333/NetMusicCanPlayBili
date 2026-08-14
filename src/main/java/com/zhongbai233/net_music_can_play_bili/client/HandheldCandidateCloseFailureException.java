package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.ResolvedVideoStream;

import java.io.IOException;

final class HandheldCandidateCloseFailureException extends IOException {
    HandheldCandidateCloseFailureException(ResolvedVideoStream stream, String reason) {
        this(stream, reason, null);
    }

    HandheldCandidateCloseFailureException(ResolvedVideoStream stream, String reason, Throwable cause) {
        super("旧手持视频候选 native 关闭失败: quality=" + stream.quality()
                + ", codec=" + stream.codecId() + ", reason=" + reason, cause);
    }
}
