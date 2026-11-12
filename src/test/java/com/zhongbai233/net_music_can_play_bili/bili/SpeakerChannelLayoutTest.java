package com.zhongbai233.net_music_can_play_bili.bili;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Logical speaker layout and stereo or multichannel mixing assignments. */
class SpeakerChannelLayoutTest {
    @Test
    void mapsFivePointOneBedIntoJocOrderWithoutLfe() {
        float[][] pcm = new float[6][300];
        for (int channel = 0; channel < pcm.length; channel++) {
            Arrays.fill(pcm[channel], channel + 1.0f);
        }

        float[][] block = DolbySpatialLayout.buildJocDownmixBlock(pcm, 6, 5, 32);

        assertEquals(5, block.length);
        assertEquals(1.0f, block[0][0]);
        assertEquals(3.0f, block[1][0]);
        assertEquals(2.0f, block[2][0]);
        assertEquals(5.0f, block[3][0]);
        assertEquals(6.0f, block[4][0]);
    }

    @Test
    void preservesKnownBedLayoutAndObjectStatistics() {
        float[][] positions = DolbySpatialLayout.computeBedPositions(6, 1.5f);
        assertArrayEquals(new float[] { 0.0f, 0.0f, 0.0f }, positions[3], 0.00001f);
        assertEquals(1.5f, positions[2][2], 0.00001f);
        assertArrayEquals(new String[] { "FL", "FR", "FC", "LFE", "SL", "SR" },
                DolbySpatialLayout.bedChannelNames(6));
        assertEquals(2, DolbySpatialLayout.centerChannelIndex(6));

        float[][] objects = { { 1.0f, -1.0f }, { 0.0f, 0.0f }, { 0.25f, 0.25f } };
        float[] rms = DolbySpatialLayout.rmsByObject(objects, 3);
        float[] peaks = DolbySpatialLayout.peakByObject(objects, 3);
        assertArrayEquals(new float[] { 1.0f, 0.0f, 0.25f }, rms, 0.00001f);
        assertArrayEquals(new float[] { 1.0f, 0.0f, 0.25f }, peaks, 0.00001f);
        assertEquals(2, DolbySpatialLayout.countActiveObjects(rms));
        assertEquals(1.0f, DolbySpatialLayout.max(peaks));
    }

    @Test
    void mapsEveryLogicalSpeakerPositionToStereo() {
        float[][] stereo = { { 0.25f, 0.50f }, { -0.25f, -0.50f } };

        assertArrayEquals(stereo[0], SpeakerChannelMixer.baseMix(stereo, 0));
        assertArrayEquals(stereo[1], SpeakerChannelMixer.baseMix(stereo, 1));
        assertArrayEquals(stereo[0], SpeakerChannelMixer.baseMix(stereo, 8));
        assertArrayEquals(stereo[1], SpeakerChannelMixer.baseMix(stereo, 11));
        assertArrayEquals(new float[] { 0.0f, 0.0f }, SpeakerChannelMixer.baseMix(stereo, 2), 0.00001f);
    }

    @Test
    void mapsLogicalSideAndRearChannelsToFfmpegSevenOneLayout() {
        assertArrayEquals(new int[] { 6 }, SpeakerChannelMixer.sourceChannels(4, 8));
        assertArrayEquals(new int[] { 7 }, SpeakerChannelMixer.sourceChannels(5, 8));
        assertArrayEquals(new int[] { 4 }, SpeakerChannelMixer.sourceChannels(6, 8));
        assertArrayEquals(new int[] { 5 }, SpeakerChannelMixer.sourceChannels(7, 8));
    }

    @Test
    void oneAutoMixSpeakerDoesNotTakeChannelsClaimedByOtherSpeakers() {
        SpeakerAudioRelay leftMix = relay(0, true);
        SpeakerAudioRelay right = relay(1, false);
        List<SpeakerAudioRelay> relays = List.of(leftMix, right);

        assertTrue(SpeakerChannelMixer.isSourceClaimed(0, 2, relays));
        assertTrue(SpeakerChannelMixer.isSourceClaimed(1, 2, relays));
        assertArrayEquals(new float[] { 0.4f },
                SpeakerChannelMixer.baseMix(new float[][] { { 0.4f }, { 0.7f } }, leftMix.getChannelIndex()));
        assertArrayEquals(new float[] { 0.7f },
                SpeakerChannelMixer.baseMix(new float[][] { { 0.4f }, { 0.7f } }, right.getChannelIndex()));
    }

    @Test
    void topSpeakerParticipatesInClaimPlanningInsteadOfBeingDropped() {
        SpeakerAudioRelay topLeftMix = relay(8, true);
        List<SpeakerAudioRelay> relays = List.of(topLeftMix);

        assertTrue(SpeakerChannelMixer.isSourceClaimed(0, 2, relays));
        assertFalse(SpeakerChannelMixer.isSourceClaimed(1, 2, relays));
        assertArrayEquals(new float[] { 0.6f },
                SpeakerChannelMixer.baseMix(new float[][] { { 0.6f }, { 0.2f } }, 8));
    }

    @Test
    void multipleAutoMixSpeakersKeepIndependentBaseAudio() {
        float[][] stereo = { { 0.35f }, { 0.75f } };
        SpeakerAudioRelay topLeftMix = relay(8, true);
        SpeakerAudioRelay topRightMix = relay(9, true);
        List<SpeakerAudioRelay> relays = List.of(topLeftMix, topRightMix);

        assertTrue(SpeakerChannelMixer.isSourceClaimed(0, 2, relays));
        assertTrue(SpeakerChannelMixer.isSourceClaimed(1, 2, relays));
        assertArrayEquals(new float[] { 0.35f },
                SpeakerChannelMixer.baseMix(stereo, topLeftMix.getChannelIndex()));
        assertArrayEquals(new float[] { 0.75f },
                SpeakerChannelMixer.baseMix(stereo, topRightMix.getChannelIndex()));
    }

    private static SpeakerAudioRelay relay(int channelIndex, boolean autoMix) {
        SpeakerAudioRelay relay = new SpeakerAudioRelay();
        relay.setChannelIndex(channelIndex);
        relay.setAutoMixJoc(autoMix);
        return relay;
    }
}
