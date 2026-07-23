package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerChannelMixerTest {
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