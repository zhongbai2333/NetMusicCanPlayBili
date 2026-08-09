package com.zhongbai233.net_music_can_play_bili.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioRelayRoutingPolicyTest {
    @Test
    void publicRouteKeepsPhysicalAndConsoleRelaysAudible() {
        assertFalse(AudioRelayRoutingPolicy.muteWorldRelays(false, false, false));
    }

    @Test
    void headphoneMutesRelaysForItsSourceAndOtherSuppressedSources() {
        assertTrue(AudioRelayRoutingPolicy.muteWorldRelays(true, false, false));
        assertTrue(AudioRelayRoutingPolicy.muteWorldRelays(false, true, false));
    }

    @Test
    void privateOwnerRouteMutesWorldRelays() {
        assertTrue(AudioRelayRoutingPolicy.muteWorldRelays(false, false, true));
    }
}