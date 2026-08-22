package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerAudioRelayPreparationDemandTest {
    @Test
    void mutedConsoleRelayCanPrepareWithoutBecomingAudible() {
        SpeakerAudioRelay relay = new SpeakerAudioRelay();
        relay.setChannelIndex(0);
        relay.setUserVolume(1.0F);
        relay.setSpeakerPos(new float[] { 0.0F, 0.0F, 0.0F });
        relay.setRangeGain(0.0F);

        assertFalse(relay.isAudibleAt(new float[] { 0.0F, 0.0F, 0.0F }));
        assertFalse(relay.hasAnticipatedDemand(
                new float[] { 100.0F, 0.0F, 0.0F }, new float[] { 0.0F, 0.0F, 0.0F }));

        relay.setPreparationDemand(true);

        assertTrue(relay.hasAnticipatedDemand(
                new float[] { 100.0F, 0.0F, 0.0F }, new float[] { 0.0F, 0.0F, 0.0F }));
        assertFalse(relay.isAudibleAt(new float[] { 0.0F, 0.0F, 0.0F }));
    }
}
