package com.zhongbai233.net_music_can_play_bili.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentRouteOwnersTest {
    @Test
    void relayExitDoesNotAffectLogicalConsoleBinding() {
        PersistentRouteOwners<String> owners = new PersistentRouteOwners<>();
        owners.bind("console", "turntable");

        // Audio relays may disappear at the hard-range boundary; logical binding remains.
        assertTrue(owners.hasOwners("turntable"));

        owners.unbind("console");
        assertFalse(owners.hasOwners("turntable"));
    }

    @Test
    void rebindMovesSuppressionWithoutLeavingTheOldSourceMuted() {
        PersistentRouteOwners<String> owners = new PersistentRouteOwners<>();
        owners.bind("console", "old");
        owners.bind("console", "next");

        assertFalse(owners.hasOwners("old"));
        assertTrue(owners.hasOwners("next"));
    }

    @Test
    void oneConsoleLeavingDoesNotReleaseAnotherConsoleBinding() {
        PersistentRouteOwners<String> owners = new PersistentRouteOwners<>();
        owners.bind("left", "turntable");
        owners.bind("right", "turntable");
        owners.unbind("left");

        assertTrue(owners.hasOwners("turntable"));
        owners.unbind("right");
        assertFalse(owners.hasOwners("turntable"));
    }
}
