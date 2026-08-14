package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.VideoCandidate;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** Fail-closed signal used when a startup candidate has not physically terminated. */
final class VideoCandidateCloseTimeoutException extends IOException {
    final VideoCandidate candidate;
    final CompletableFuture<Void> nativeTermination;

    VideoCandidateCloseTimeoutException(VideoCandidate candidate,
            CompletableFuture<Void> nativeTermination) {
        this(candidate, nativeTermination, "close timeout", null);
    }

    VideoCandidateCloseTimeoutException(VideoCandidate candidate,
            CompletableFuture<Void> nativeTermination, String reason) {
        this(candidate, nativeTermination, reason, null);
    }

    VideoCandidateCloseTimeoutException(VideoCandidate candidate,
            CompletableFuture<Void> nativeTermination, String reason, Throwable cause) {
        super("旧视频候选 native worker 未正常收敛: quality=" + candidate.quality()
                + ", codec=" + candidate.codecId() + ", reason=" + reason, cause);
        this.candidate = candidate;
        this.nativeTermination = nativeTermination;
    }
}
