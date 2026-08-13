package com.zhongbai233.net_music_can_play_bili.media.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class Fmp4NativeVideoDecoderLifecycleTest {
    @Test
    void trackedInputBarrierClosesEachIdentityExactlyOnce() {
        Fmp4NativeVideoDecoder.TrackedInputRegistry inputs = testInputRegistry();
        CountingInputStream first = new CountingInputStream(false);
        CountingInputStream second = new CountingInputStream(false);

        inputs.track(first);
        inputs.track(second);
        inputs.track(first);
        inputs.beginClose();
        inputs.beginClose();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> inputs.completionSnapshot().join());
        assertEquals(2, inputs.trackedCount());
        assertEquals(1, first.closeCount.get());
        assertEquals(1, second.closeCount.get());
    }

    @Test
    void streamRegisteredAfterCancellationStillJoinsCloseBarrier() {
        Fmp4NativeVideoDecoder.TrackedInputRegistry inputs = testInputRegistry();
        CountingInputStream late = new CountingInputStream(false);

        inputs.beginClose();
        inputs.track(late);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> inputs.completionSnapshot().join());
        assertEquals(1, late.closeCount.get());
    }

    @Test
    void trackedCompositeCloseDoesNotDoubleCloseItsTrackedChild() {
        Fmp4NativeVideoDecoder.TrackedInputRegistry inputs = testInputRegistry();
        CountingInputStream child = new CountingInputStream(false);
        InputStream trackedChild = inputs.track(child);
        inputs.track(new SequenceInputStream(trackedChild, InputStream.nullInputStream()));

        inputs.beginClose();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> inputs.completionSnapshot().join());
        assertEquals(1, child.closeCount.get());
    }

    @Test
    void trackedInputCloseFailureMakesBarrierExceptional() {
        Fmp4NativeVideoDecoder.TrackedInputRegistry inputs = testInputRegistry();
        CountingInputStream failing = new CountingInputStream(true);
        inputs.track(failing);

        inputs.beginClose();

        CompletionException error = assertThrows(CompletionException.class,
                () -> assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> inputs.completionSnapshot().join()));
        assertInstanceOf(IOException.class, rootCause(error));
        assertEquals(1, failing.closeCount.get());
    }

    @Test
    void boundedDecoderCloseRetriesWithBackoffAndThenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> delays = new ArrayList<>();
        List<Integer> failedAttempts = new ArrayList<>();

        Throwable failure = Fmp4NativeVideoDecoder.closeWithBoundedRetry(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("transient close failure");
            }
        }, 3, 5L, delays::add,
                (attempt, delay, error) -> failedAttempts.add(attempt));

        assertNull(failure);
        assertEquals(3, attempts.get());
        assertEquals(List.of(5L, 10L), delays);
        assertEquals(List.of(1, 2), failedAttempts);
    }

    @Test
    void permanentDecoderCloseFailureStopsAtConfiguredAttemptLimit() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> delays = new ArrayList<>();

        Throwable failure = Fmp4NativeVideoDecoder.closeWithBoundedRetry(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("permanent close failure");
        }, 3, 7L, delays::add, (attempt, delay, error) -> {
        });

        assertInstanceOf(IllegalStateException.class, failure);
        assertEquals(3, attempts.get());
        assertEquals(List.of(7L, 14L), delays);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Fmp4NativeVideoDecoder.TrackedInputRegistry testInputRegistry() {
        return new Fmp4NativeVideoDecoder.TrackedInputRegistry((resource, description) ->
                CompletableFuture.runAsync(() -> {
                    try {
                        resource.close();
                    } catch (Throwable error) {
                        throw new CompletionException(error);
                    }
                }));
    }

    private static final class CountingInputStream extends InputStream {
        private final boolean failClose;
        private final AtomicInteger closeCount = new AtomicInteger();

        private CountingInputStream(boolean failClose) {
            this.failClose = failClose;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            if (failClose) {
                throw new IOException("tracked close failed");
            }
        }
    }
}
