package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveReconnectPolicyTest {

    @Test
    void backsOffExponentiallyUpToTheCeiling() {
        LiveReconnectPolicy policy = new LiveReconnectPolicy(10, 100L, 400L, 20_000L);

        assertEquals(100L, policy.onStreamEnded(0L));
        assertEquals(200L, policy.onStreamEnded(50L));
        assertEquals(400L, policy.onStreamEnded(50L));
        assertEquals(400L, policy.onStreamEnded(50L));
        assertEquals(4, policy.consecutiveFailures());
    }

    @Test
    void treatsLongLivedConnectionsAsHealthyAndResetsBackoff() {
        LiveReconnectPolicy policy = new LiveReconnectPolicy(3, 100L, 5_000L, 20_000L);
        policy.onStreamEnded(10L);
        policy.onStreamEnded(10L);

        assertEquals(100L, policy.onStreamEnded(30_000L));
        assertEquals(0, policy.consecutiveFailures());
        assertEquals(100L, policy.onStreamEnded(10L));
    }

    @Test
    void givesUpAfterTooManyShortFailures() {
        LiveReconnectPolicy policy = new LiveReconnectPolicy(2, 100L, 5_000L, 20_000L);

        assertEquals(100L, policy.onStreamEnded(0L));
        assertEquals(200L, policy.onStreamEnded(0L));
        assertEquals(LiveReconnectPolicy.GIVE_UP, policy.onStreamEnded(0L));
    }

    @Test
    void resetClearsFailureCount() {
        LiveReconnectPolicy policy = new LiveReconnectPolicy(2, 100L, 5_000L, 20_000L);
        policy.onStreamEnded(0L);
        policy.onStreamEnded(0L);
        policy.reset();

        assertEquals(0, policy.consecutiveFailures());
        assertEquals(100L, policy.onStreamEnded(0L));
    }
}
