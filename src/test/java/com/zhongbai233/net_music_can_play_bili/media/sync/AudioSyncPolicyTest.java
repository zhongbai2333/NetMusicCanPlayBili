package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioSyncPolicyTest {
    private final AudioSyncPolicy policy = new AudioSyncPolicy(8L, 28L, 40L, 8L, 12L);

    @Test
    void appliesAheadAndOutputLagGuards() {
        assertFalse(policy.isAhead(10L, 9L, 15L));
        assertTrue(policy.isAhead(28L, 27L, 15L));
        assertTrue(policy.shouldFlushAhead(28L, 20L, 15L));
        assertFalse(policy.shouldFlushAhead(27L, 20L, 15L));
        assertTrue(policy.shouldFlushOutputLag(4L, 44L, 45L));
        assertFalse(policy.shouldFlushOutputLag(4L, 30L, 45L));
    }

    @Test
    void shouldDropDecodedBacklog() {
        assertTrue(policy.shouldDropDecodedBacklog(4L, 50L, 65L));
        assertFalse(policy.shouldDropDecodedBacklog(4L, 64L, 65L));
        assertFalse(policy.shouldDropDecodedBacklog(20L, 50L, 65L));
        assertTrue(policy.shouldDropDecodedBacklog(2_949L, 3_173L, 3_433L));
    }

    @Test
    void scalesCatchUpBudgetWithinMaximum() {
        int base = policy.allowedUnits(2.0D, 8, 10L, 15L);
        int catchUp = policy.allowedUnits(2.0D, 8, 10L, 30L);
        int legacyExpected = 2 + (int) Math.round((8 - 2) * Math.min(1.0D, 20.0D / 28.0D));
        assertEquals(2, base);
        assertEquals(legacyExpected, catchUp);
        assertTrue(catchUp <= 8);
    }
}