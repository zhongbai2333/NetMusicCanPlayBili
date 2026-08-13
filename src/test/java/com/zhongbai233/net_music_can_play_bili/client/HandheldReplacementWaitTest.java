package com.zhongbai233.net_music_can_play_bili.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HandheldReplacementWaitTest {
    @Test
    void timeoutWinnerCannotBeRevivedByLatePhysicalCompletion() {
        HandheldReplacementWait wait = new HandheldReplacementWait();

        assertEquals(HandheldReplacementWait.Outcome.FAIL_CLOSED,
                wait.complete(new TimeoutException(), HandheldDecoderAdmissionPolicy.Decision.WAIT));
        assertEquals(HandheldReplacementWait.Outcome.ALREADY_DECIDED,
                wait.complete(null, HandheldDecoderAdmissionPolicy.Decision.OPEN));
    }

    @Test
    void normalCompletionOpensExactlyOnce() {
        HandheldReplacementWait wait = new HandheldReplacementWait();

        assertEquals(HandheldReplacementWait.Outcome.OPEN,
                wait.complete(null, HandheldDecoderAdmissionPolicy.Decision.OPEN));
        assertEquals(HandheldReplacementWait.Outcome.ALREADY_DECIDED,
                wait.complete(null, HandheldDecoderAdmissionPolicy.Decision.OPEN));
    }
}
