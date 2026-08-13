package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Pure fail-closed gate before a playback instance may open its next candidate. */
final class VideoCandidateClosePolicy {
    enum Decision {
        WAIT,
        OPEN_NEXT,
        FAIL_CLOSED
    }

    private VideoCandidateClosePolicy() {
    }

    static Decision decide(boolean closeReturned, boolean nativeTerminated,
            long elapsedNanos, long timeoutMillis) {
        if (closeReturned && nativeTerminated) {
            return Decision.OPEN_NEXT;
        }
        long safeTimeoutMillis = Math.max(1L, timeoutMillis);
        if (Math.max(0L, elapsedNanos) >= TimeUnit.MILLISECONDS.toNanos(safeTimeoutMillis)) {
            return Decision.FAIL_CLOSED;
        }
        return Decision.WAIT;
    }

    static boolean completedNormally(CompletableFuture<Void> future) {
        if (future == null || !future.isDone() || future.isCancelled()
                || future.isCompletedExceptionally()) {
            return false;
        }
        try {
            future.join();
            return true;
        } catch (CompletionException | java.util.concurrent.CancellationException ignored) {
            return false;
        }
    }
}
