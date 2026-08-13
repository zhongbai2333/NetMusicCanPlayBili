package com.zhongbai233.net_music_can_play_bili.client;

import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot winner for a replacement wait: timeout can never be undone late. */
final class HandheldReplacementWait {
    enum Outcome {
        OPEN,
        FAIL_CLOSED,
        ALREADY_DECIDED
    }

    private final AtomicBoolean decided = new AtomicBoolean();

    Outcome complete(Throwable error, HandheldDecoderAdmissionPolicy.Decision physicalDecision) {
        if (!decided.compareAndSet(false, true)) {
            return Outcome.ALREADY_DECIDED;
        }
        return error == null && physicalDecision == HandheldDecoderAdmissionPolicy.Decision.OPEN
                ? Outcome.OPEN : Outcome.FAIL_CLOSED;
    }
}
