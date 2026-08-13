package com.zhongbai233.net_music_can_play_bili.blockentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModernTurntableVolumePolicyTest {
    @Test
    void playingMuteStopsAndUnmuteResynchronizes() {
        assertEquals(ModernTurntableVolumePolicy.Action.STOP_MUTED,
                ModernTurntableVolumePolicy.decide(700, 0, true));
        assertEquals(ModernTurntableVolumePolicy.Action.RESYNC_UNMUTED,
                ModernTurntableVolumePolicy.decide(0, 700, true));
    }

    @Test
    void ordinaryAndStoppedVolumeChangesDoNotRestartPlayback() {
        assertEquals(ModernTurntableVolumePolicy.Action.APPLY_ONLY,
                ModernTurntableVolumePolicy.decide(700, 400, true));
        assertEquals(ModernTurntableVolumePolicy.Action.APPLY_ONLY,
                ModernTurntableVolumePolicy.decide(700, 0, false));
        assertEquals(ModernTurntableVolumePolicy.Action.NONE,
                ModernTurntableVolumePolicy.decide(400, 400, true));
    }

    @Test
    void valuesAreClampedBeforeTransitionClassification() {
        assertEquals(0, ModernTurntableVolumePolicy.clamp(-1));
        assertEquals(1000, ModernTurntableVolumePolicy.clamp(1200));
        assertEquals(ModernTurntableVolumePolicy.Action.NONE,
                ModernTurntableVolumePolicy.decide(-1, 0, true));
    }
}
