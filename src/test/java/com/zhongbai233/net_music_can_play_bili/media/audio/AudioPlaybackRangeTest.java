package com.zhongbai233.net_music_can_play_bili.media.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioPlaybackRangeTest {
    @Test
    void standardVolumeDefinesAudibleFadeAndDecodePrewarmBands() {
        AudioPlaybackRange.Profile profile = AudioPlaybackRange.profile(64.0F, 1.0F, 1.0F);

        assertEquals(64.0F, profile.nominalDistance(), 1.0e-5F);
        assertEquals(76.8F, profile.fadeEndDistance(), 1.0e-5F);
        assertEquals(83.2F, profile.noticeDistance(), 1.0e-5F);
        assertEquals(85.2F, profile.noticeExitDistance(), 1.0e-5F);
        assertTrue(AudioPlaybackRange.evaluateSphere(83.1F, 64.0F, 1.0F, false).noticeActive());
        assertFalse(AudioPlaybackRange.evaluateSphere(83.21F, 64.0F, 1.0F, false).noticeActive());
        assertTrue(AudioPlaybackRange.evaluateSphere(84.0F, 64.0F, 1.0F, true).noticeActive());
        assertFalse(AudioPlaybackRange.evaluateSphere(85.21F, 64.0F, 1.0F, true).noticeActive());
    }

    @Test
    void volumeScalesEveryDeviceRangeWithTheSameFormula() {
        AudioPlaybackRange.Profile half = AudioPlaybackRange.profile(64.0F, 0.5F, 0.5F);
        assertEquals(32.0F, half.nominalDistance(), 1.0e-5F);
        assertEquals(38.4F, half.fadeEndDistance(), 1.0e-5F);
        assertEquals(41.6F, half.noticeDistance(), 1.0e-5F);

        AudioPlaybackRange.Profile boostedSpeaker = AudioPlaybackRange.profile(64.0F, 2.0F, 2.0F);
        assertEquals(128.0F, boostedSpeaker.nominalDistance(), 1.0e-5F);
        assertEquals(153.6F, boostedSpeaker.fadeEndDistance(), 1.0e-5F);
        assertEquals(166.4F, boostedSpeaker.noticeDistance(), 1.0e-4F);
        assertTrue(AudioPlaybackRange.evaluateSphere(64.0F, 64.0F, 2.0F, false).gain()
                > AudioPlaybackRange.evaluateSphere(64.0F, 64.0F, 1.0F, false).gain());
    }

    @Test
    void zeroVolumeNeverDecodesOrProducesGain() {
        AudioPlaybackRange.SphereResult result = AudioPlaybackRange.evaluateSphere(0.0F, 64.0F, 0.0F, true);
        assertFalse(result.noticeActive());
        assertFalse(result.audible());
        assertEquals(0.0F, result.gain());
    }

    @Test
    void sphericalAndEllipsoidZonesUseRadialBoundaries() {
        AudioPlaybackRange.ZoneResult center = AudioPlaybackRange.evaluateEllipsoid(true, 0, 0, 0, 8, 4, 8);
        AudioPlaybackRange.ZoneResult fade = AudioPlaybackRange.evaluateEllipsoid(true, 6, 0, 0, 8, 4, 8);
        AudioPlaybackRange.ZoneResult roundedCorner = AudioPlaybackRange.evaluateEllipsoid(true, 6, 3, 0, 8, 4, 8);
        AudioPlaybackRange.ZoneResult outside = AudioPlaybackRange.evaluateEllipsoid(true, 8, 0, 0, 8, 4, 8);

        assertTrue(center.active());
        assertEquals(1.0F, center.gain());
        assertTrue(fade.active());
        assertTrue(fade.gain() > 0.0F && fade.gain() < 1.0F);
        assertFalse(roundedCorner.active());
        assertFalse(outside.active());
        assertEquals(0.0F, outside.gain());
    }
}
