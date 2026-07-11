package com.zhongbai233.net_music_can_play_bili.client.pad;

/** Lightweight tests for indoor evidence combination. */
public final class PadMapViewProfileDetectorSelfTest {
    private PadMapViewProfileDetectorSelfTest() {
    }

    public static void main(String[] args) {
        acceptsHighArtificialRoofWithoutNearbyWalls();
        acceptsNearbyArtificialStructureUnderMixedCeiling();
        rejectsNaturalCaveCeiling();
        rejectsSparseRoofEvidence();
        System.out.println("PadMapViewProfileDetectorSelfTest passed");
    }

    private static void acceptsHighArtificialRoofWithoutNearbyWalls() {
        assertIndoor(25, 25, 0, "a high artificial roof should itself prove an indoor structure");
    }

    private static void acceptsNearbyArtificialStructureUnderMixedCeiling() {
        assertIndoor(8, 2, 5, "nearby artificial blocks should support mixed ceiling evidence");
    }

    private static void rejectsNaturalCaveCeiling() {
        assertOutdoor(25, 0, 0, "natural terrain above an open area must not imply a building interior");
    }

    private static void rejectsSparseRoofEvidence() {
        assertOutdoor(4, 4, 5, "fewer than the minimum ceiling hits must remain outdoor");
    }

    private static void assertIndoor(int ceiling, int artificialCeiling, int nearbyArtificial, String message) {
        if (!PadMapViewProfileDetector.isIndoorEvidence(ceiling, artificialCeiling, nearbyArtificial, 5, 5)) {
            throw new AssertionError(message);
        }
    }

    private static void assertOutdoor(int ceiling, int artificialCeiling, int nearbyArtificial, String message) {
        if (PadMapViewProfileDetector.isIndoorEvidence(ceiling, artificialCeiling, nearbyArtificial, 5, 5)) {
            throw new AssertionError(message);
        }
    }
}