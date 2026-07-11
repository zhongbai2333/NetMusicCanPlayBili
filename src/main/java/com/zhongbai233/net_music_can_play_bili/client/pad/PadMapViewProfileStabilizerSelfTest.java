package com.zhongbai233.net_music_can_play_bili.client.pad;

/** Lightweight self tests for Pad indoor/outdoor view profile stabilization. */
public final class PadMapViewProfileStabilizerSelfTest {
    private PadMapViewProfileStabilizerSelfTest() {
    }

    public static void main(String[] args) {
        debouncesIndoorEntry();
        debouncesOutdoorExit();
        toleratesSmallIndoorFloorJumps();
        confirmsLargeIndoorFloorChanges();
        survivesTransientHighCeilingMisses();
        System.out.println("PadMapViewProfileStabilizerSelfTest passed");
    }

    private static void debouncesIndoorEntry() {
        PadMapViewProfileStabilizer stabilizer = new PadMapViewProfileStabilizer(2, 10, 4, 2);
        PadMapViewProfileStabilizer.Result first = stabilizer.update(PadMapViewProfile.INDOOR, 64);
        assertResult(first, PadMapViewProfile.OUTDOOR, PadMapViewProfileStabilizer.OUTDOOR_LAYER_Y,
                "first indoor tick should not enter indoor profile yet");
        PadMapViewProfileStabilizer.Result second = stabilizer.update(PadMapViewProfile.INDOOR, 64);
        assertResult(second, PadMapViewProfile.INDOOR, 64, "second indoor tick should confirm indoor profile");
    }

    private static void debouncesOutdoorExit() {
        PadMapViewProfileStabilizer stabilizer = new PadMapViewProfileStabilizer(1, 2, 4, 2);
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 64), PadMapViewProfile.INDOOR, 64,
                "single tick should enter indoor with confirm=1");
        assertResult(stabilizer.update(PadMapViewProfile.OUTDOOR, PadMapViewProfileStabilizer.OUTDOOR_LAYER_Y),
                PadMapViewProfile.INDOOR, 64, "first outdoor tick should be held while exiting indoor");
        assertResult(stabilizer.update(PadMapViewProfile.OUTDOOR, PadMapViewProfileStabilizer.OUTDOOR_LAYER_Y),
                PadMapViewProfile.OUTDOOR, PadMapViewProfileStabilizer.OUTDOOR_LAYER_Y,
                "second outdoor tick should confirm exit");
    }

    private static void toleratesSmallIndoorFloorJumps() {
        PadMapViewProfileStabilizer stabilizer = new PadMapViewProfileStabilizer(1, 10, 2, 2);
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 64), PadMapViewProfile.INDOOR, 64,
                "should enter indoor floor 64");
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 66), PadMapViewProfile.INDOOR, 64,
                "small indoor floor jumps should keep stable floor");
    }

    private static void confirmsLargeIndoorFloorChanges() {
        PadMapViewProfileStabilizer stabilizer = new PadMapViewProfileStabilizer(1, 10, 2, 2);
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 64), PadMapViewProfile.INDOOR, 64,
                "should enter indoor floor 64");
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 70), PadMapViewProfile.INDOOR, 64,
                "first large floor jump should be a candidate only");
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 70), PadMapViewProfile.INDOOR, 70,
                "repeated large floor jump should confirm new floor");
    }

    private static void survivesTransientHighCeilingMisses() {
        PadMapViewProfileStabilizer stabilizer = new PadMapViewProfileStabilizer(1, 4, 2, 2);
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 64), PadMapViewProfile.INDOOR, 64,
                "should enter high-ceiling indoor profile");
        for (int i = 0; i < 3; i++) {
            assertResult(stabilizer.update(PadMapViewProfile.OUTDOOR, PadMapViewProfileStabilizer.OUTDOOR_LAYER_Y),
                    PadMapViewProfile.INDOOR, 64, "transient ceiling misses should not exit indoor profile");
        }
        assertResult(stabilizer.update(PadMapViewProfile.INDOOR, 64), PadMapViewProfile.INDOOR, 64,
                "renewed ceiling evidence should reset exit confirmation");
    }

    private static void assertResult(PadMapViewProfileStabilizer.Result result, PadMapViewProfile expectedProfile,
            int expectedFloorY, String message) {
        if (result.profile() != expectedProfile || result.floorY() != expectedFloorY) {
            throw new AssertionError(message + ": got profile=" + result.profile() + " floor=" + result.floorY());
        }
    }
}
