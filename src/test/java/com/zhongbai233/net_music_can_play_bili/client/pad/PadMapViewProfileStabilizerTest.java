package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PadMapViewProfileStabilizerTest {
    @Test
    void debouncesIndoorEntry() {
        PadMapViewProfileStabilizer stabilizer = new PadMapViewProfileStabilizer(2, 10, 4, 2);
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 64), PadMapViewProfile.OUTDOOR,
                PadMapViewProfileStabilizer.OUTDOOR_LAYER_Y);
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 64), PadMapViewProfile.INDOOR, 64);
    }

    @Test
    void debouncesOutdoorExit() {
        PadMapViewProfileStabilizer stabilizer = new PadMapViewProfileStabilizer(1, 2, 4, 2);
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 64), PadMapViewProfile.INDOOR, 64);
        assertResult(stabilizer.update(PadMapViewProfile.OUTDOOR, PadMapViewProfileStabilizer.OUTDOOR_LAYER_Y),
                PadMapViewProfile.INDOOR, 64);
        assertResult(stabilizer.update(PadMapViewProfile.OUTDOOR, PadMapViewProfileStabilizer.OUTDOOR_LAYER_Y),
                PadMapViewProfile.OUTDOOR, PadMapViewProfileStabilizer.OUTDOOR_LAYER_Y);
    }

    @Test
    void toleratesSmallIndoorFloorJumps() {
        PadMapViewProfileStabilizer stabilizer = new PadMapViewProfileStabilizer(1, 10, 2, 2);
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 64), PadMapViewProfile.INDOOR, 64);
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 66), PadMapViewProfile.INDOOR, 64);
    }

    @Test
    void confirmsLargeIndoorFloorChanges() {
        PadMapViewProfileStabilizer stabilizer = new PadMapViewProfileStabilizer(1, 10, 2, 2);
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 64), PadMapViewProfile.INDOOR, 64);
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 70), PadMapViewProfile.INDOOR, 64);
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 70), PadMapViewProfile.INDOOR, 70);
    }

    @Test
    void survivesTransientHighCeilingMisses() {
        PadMapViewProfileStabilizer stabilizer = new PadMapViewProfileStabilizer(1, 4, 2, 2);
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 64), PadMapViewProfile.INDOOR, 64);
        for (int i = 0; i < 3; i++) {
            assertResult(stabilizer.update(PadMapViewProfile.OUTDOOR,
                    PadMapViewProfileStabilizer.OUTDOOR_LAYER_Y), PadMapViewProfile.INDOOR, 64);
        }
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 64), PadMapViewProfile.INDOOR, 64);
    }

    private static void assertResult(PadMapViewProfileStabilizer.Result result,
            PadMapViewProfile expectedProfile, int expectedFloorY) {
        assertEquals(expectedProfile, result.profile());
        assertEquals(expectedFloorY, result.floorY());
    }
}