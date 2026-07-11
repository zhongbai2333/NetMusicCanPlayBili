package com.zhongbai233.net_music_can_play_bili.client.pad;

/** Debounces Pad indoor/outdoor profile transitions and indoor floor changes. */
final class PadMapViewProfileStabilizer {
    static final int OUTDOOR_LAYER_Y = Integer.MIN_VALUE;

    private final int indoorEnterConfirmTicks;
    private final int indoorExitConfirmTicks;
    private final int indoorFloorChangeConfirmTicks;
    private final int indoorJumpToleranceBlocks;

    private PadMapViewProfile stableProfile = PadMapViewProfile.OUTDOOR;
    private int indoorCandidateTicks;
    private int outdoorCandidateTicks;
    private int stableIndoorFloorY = OUTDOOR_LAYER_Y;
    private int candidateIndoorFloorY = OUTDOOR_LAYER_Y;
    private int candidateIndoorFloorTicks;

    PadMapViewProfileStabilizer(int indoorEnterConfirmTicks, int indoorExitConfirmTicks,
            int indoorFloorChangeConfirmTicks, int indoorJumpToleranceBlocks) {
        this.indoorEnterConfirmTicks = Math.max(1, indoorEnterConfirmTicks);
        this.indoorExitConfirmTicks = Math.max(1, indoorExitConfirmTicks);
        this.indoorFloorChangeConfirmTicks = Math.max(1, indoorFloorChangeConfirmTicks);
        this.indoorJumpToleranceBlocks = Math.max(0, indoorJumpToleranceBlocks);
    }

    Result update(PadMapViewProfile rawProfile, int rawFloorY) {
        PadMapViewProfile profile = stabilize(rawProfile, rawFloorY);
        return new Result(profile, profile == PadMapViewProfile.INDOOR ? stableIndoorFloorY : OUTDOOR_LAYER_Y);
    }

    void reset() {
        stableProfile = PadMapViewProfile.OUTDOOR;
        indoorCandidateTicks = 0;
        outdoorCandidateTicks = 0;
        stableIndoorFloorY = OUTDOOR_LAYER_Y;
        candidateIndoorFloorY = OUTDOOR_LAYER_Y;
        candidateIndoorFloorTicks = 0;
    }

    private PadMapViewProfile stabilize(PadMapViewProfile rawProfile, int rawFloorY) {
        if (stableProfile == PadMapViewProfile.INDOOR) {
            if (rawProfile == PadMapViewProfile.INDOOR) {
                outdoorCandidateTicks = 0;
                stabilizeIndoorFloor(rawFloorY);
                return PadMapViewProfile.INDOOR;
            }
            indoorCandidateTicks = 0;
            if (++outdoorCandidateTicks < indoorExitConfirmTicks) {
                return PadMapViewProfile.INDOOR;
            }
            stableProfile = PadMapViewProfile.OUTDOOR;
            stableIndoorFloorY = OUTDOOR_LAYER_Y;
            candidateIndoorFloorY = OUTDOOR_LAYER_Y;
            candidateIndoorFloorTicks = 0;
            return PadMapViewProfile.OUTDOOR;
        }
        if (rawProfile == PadMapViewProfile.INDOOR) {
            outdoorCandidateTicks = 0;
            candidateIndoorFloorY = rawFloorY;
            if (++indoorCandidateTicks >= indoorEnterConfirmTicks) {
                stableProfile = PadMapViewProfile.INDOOR;
                stableIndoorFloorY = rawFloorY;
                candidateIndoorFloorTicks = 0;
                return PadMapViewProfile.INDOOR;
            }
        } else {
            indoorCandidateTicks = 0;
            outdoorCandidateTicks = 0;
        }
        return PadMapViewProfile.OUTDOOR;
    }

    private void stabilizeIndoorFloor(int rawFloorY) {
        if (stableIndoorFloorY == OUTDOOR_LAYER_Y) {
            stableIndoorFloorY = rawFloorY;
            candidateIndoorFloorY = rawFloorY;
            candidateIndoorFloorTicks = 0;
            return;
        }
        if (Math.abs(rawFloorY - stableIndoorFloorY) <= indoorJumpToleranceBlocks) {
            candidateIndoorFloorY = stableIndoorFloorY;
            candidateIndoorFloorTicks = 0;
            return;
        }
        if (candidateIndoorFloorY != rawFloorY) {
            candidateIndoorFloorY = rawFloorY;
            candidateIndoorFloorTicks = 1;
            return;
        }
        if (++candidateIndoorFloorTicks >= indoorFloorChangeConfirmTicks) {
            stableIndoorFloorY = rawFloorY;
            candidateIndoorFloorTicks = 0;
        }
    }

    record Result(PadMapViewProfile profile, int floorY) {
    }
}
