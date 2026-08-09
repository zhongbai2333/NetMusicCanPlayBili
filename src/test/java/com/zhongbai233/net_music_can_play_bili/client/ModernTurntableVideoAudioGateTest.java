package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer.AudioPresence;
import com.zhongbai233.net_music_can_play_bili.client.sync.VideoAudioReadinessPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernTurntableVideoAudioGateTest {
    @Test
    void authoritativeAudioAbsenceAllowsVideoWithoutOpenAlTimeline() {
        assertTrue(VideoAudioReadinessPolicy.allowsVideo(AudioPresence.ABSENT, false));
    }

    @Test
    void unknownOrFailedAudioDoesNotBecomeVideoOnlyByInference() {
        assertFalse(VideoAudioReadinessPolicy.allowsVideo(AudioPresence.UNKNOWN, false));
        assertFalse(VideoAudioReadinessPolicy.allowsVideo(AudioPresence.FAILED, false));
    }

    @Test
    void presentAudioStillRequiresMatchingOutputTimeline() {
        assertFalse(VideoAudioReadinessPolicy.allowsVideo(AudioPresence.PRESENT, false));
        assertTrue(VideoAudioReadinessPolicy.allowsVideo(AudioPresence.PRESENT, true));
    }
}