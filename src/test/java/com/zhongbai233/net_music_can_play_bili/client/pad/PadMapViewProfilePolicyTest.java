package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadMapViewProfilePolicyTest {
    @Test
    void acceptsHighArtificialRoofWithoutNearbyWalls() {
        assertIndoor(25, 25, 0, "a high artificial roof should itself prove an indoor structure");
    }

    @Test
    void acceptsNearbyArtificialStructureUnderMixedCeiling() {
        assertIndoor(8, 2, 5, "nearby artificial blocks should support mixed ceiling evidence");
    }

    @Test
    void rejectsNaturalCaveCeiling() {
        assertOutdoor(25, 0, 0, "natural terrain above an open area must not imply a building interior");
    }

    @Test
    void rejectsSparseRoofEvidence() {
        assertOutdoor(4, 4, 5, "fewer than the minimum ceiling hits must remain outdoor");
    }

    private static void assertIndoor(int ceiling, int artificialCeiling, int nearbyArtificial, String message) {
        assertTrue(PadMapViewProfilePolicy.isIndoorEvidence(
                ceiling, artificialCeiling, nearbyArtificial, 5, 5), message);
    }

    private static void assertOutdoor(int ceiling, int artificialCeiling, int nearbyArtificial, String message) {
        assertFalse(PadMapViewProfilePolicy.isIndoorEvidence(
                ceiling, artificialCeiling, nearbyArtificial, 5, 5), message);
    }
}