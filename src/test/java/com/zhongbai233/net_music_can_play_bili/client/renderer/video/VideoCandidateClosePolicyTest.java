package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoCandidateClosePolicyTest {
    @Test
    void nextCandidateWaitsForBothCloseSignals() {
        assertEquals(VideoCandidateClosePolicy.Decision.WAIT,
                VideoCandidateClosePolicy.decide(false, false, 0L, 3_000L));
        assertEquals(VideoCandidateClosePolicy.Decision.WAIT,
                VideoCandidateClosePolicy.decide(true, false,
                        TimeUnit.MILLISECONDS.toNanos(2_999L), 3_000L));
        assertEquals(VideoCandidateClosePolicy.Decision.WAIT,
                VideoCandidateClosePolicy.decide(false, true,
                        TimeUnit.MILLISECONDS.toNanos(2_999L), 3_000L));
    }

    @Test
    void convergedCloseMayOpenTheNextCandidate() {
        assertEquals(VideoCandidateClosePolicy.Decision.OPEN_NEXT,
                VideoCandidateClosePolicy.decide(true, true,
                        TimeUnit.MILLISECONDS.toNanos(3_000L), 3_000L));
    }

    @Test
    void pendingCloseFailsClosedAtTheExactBoundary() {
        assertEquals(VideoCandidateClosePolicy.Decision.FAIL_CLOSED,
                VideoCandidateClosePolicy.decide(true, false,
                        TimeUnit.MILLISECONDS.toNanos(3_000L), 3_000L));
    }

    @Test
    void onlyNormalFutureCompletionCountsAsPhysicalTermination() {
        assertTrue(VideoCandidateClosePolicy.completedNormally(CompletableFuture.completedFuture(null)));

        CompletableFuture<Void> exceptional = new CompletableFuture<>();
        exceptional.completeExceptionally(new IllegalStateException("native close failed"));
        assertFalse(VideoCandidateClosePolicy.completedNormally(exceptional));

        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        assertFalse(VideoCandidateClosePolicy.completedNormally(cancelled));
    }
}
