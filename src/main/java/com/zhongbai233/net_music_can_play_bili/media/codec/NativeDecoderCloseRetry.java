package com.zhongbai233.net_music_can_play_bili.media.codec;

/** Bounded retry policy for physical native decoder teardown. */
final class NativeDecoderCloseRetry {
    private NativeDecoderCloseRetry() {
    }

    static Throwable close(CloseOperation operation, int maxAttempts, long initialBackoffMillis,
            RetrySleeper sleeper, CloseRetryListener retryListener) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        long delayMillis = Math.max(0L, initialBackoffMillis);
        Throwable previousFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                operation.close();
                return null;
            } catch (Throwable failure) {
                if (previousFailure != null && previousFailure != failure) {
                    failure.addSuppressed(previousFailure);
                }
                previousFailure = failure;
                if (attempt >= maxAttempts) {
                    return failure;
                }
                try {
                    retryListener.onRetry(attempt, delayMillis, failure);
                } catch (Throwable listenerFailure) {
                    failure.addSuppressed(listenerFailure);
                }
                try {
                    sleeper.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    interrupted.addSuppressed(failure);
                    return interrupted;
                }
                delayMillis = delayMillis > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : delayMillis * 2L;
            }
        }
        throw new AssertionError("unreachable bounded close state");
    }

    @FunctionalInterface
    interface CloseOperation {
        void close() throws Throwable;
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface CloseRetryListener {
        void onRetry(int failedAttempt, long delayMillis, Throwable failure);
    }
}
