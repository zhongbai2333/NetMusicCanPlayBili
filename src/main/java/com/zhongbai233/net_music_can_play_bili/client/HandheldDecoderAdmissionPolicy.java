package com.zhongbai233.net_music_can_play_bili.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Pure admission decision for replacing a handheld native decoder session. */
final class HandheldDecoderAdmissionPolicy {
    enum Decision {
        OPEN,
        WAIT,
        FAIL_CLOSED
    }

    private HandheldDecoderAdmissionPolicy() {
    }

    static Decision decide(CompletableFuture<Void> decodeExit,
            CompletableFuture<Void> nativeTermination) {
        if (decodeExit == null || nativeTermination == null) {
            return Decision.FAIL_CLOSED;
        }
        CompletableFuture<Void> safeDecodeExit = nonNull(decodeExit);
        CompletableFuture<Void> safeNativeTermination = nonNull(nativeTermination);
        if (failed(safeDecodeExit) || failed(safeNativeTermination)) {
            return Decision.FAIL_CLOSED;
        }
        if (!safeDecodeExit.isDone() || !safeNativeTermination.isDone()) {
            return Decision.WAIT;
        }
        return Decision.OPEN;
    }

    static CompletableFuture<Void> convergence(CompletableFuture<Void> decodeExit,
            CompletableFuture<Void> nativeTermination) {
        if (decodeExit == null || nativeTermination == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("handheld decoder close signal is missing"));
        }
        return CompletableFuture.allOf(nonNull(decodeExit), nonNull(nativeTermination));
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

    private static boolean failed(CompletableFuture<Void> future) {
        return future.isCancelled() || future.isCompletedExceptionally();
    }

    private static CompletableFuture<Void> nonNull(CompletableFuture<Void> future) {
        return future != null ? future : CompletableFuture.completedFuture(null);
    }
}
