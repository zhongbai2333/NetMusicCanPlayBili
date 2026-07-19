package com.zhongbai233.net_music_can_play_bili.media.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioUtilsTest {
    @Test
    void spatialVolumeShrinksAudibleDistance() {
        float fullAtEdge = AudioUtils.spatialGainForDistance(64.0f, 1.0f);
        assertEquals(AudioUtils.gainForDistance(64.0f), fullAtEdge, 1.0e-6f);
        float fullFadeStart = AudioUtils.spatialGainForDistance(65.0f, 1.0f);
        float fullFadeMiddle = AudioUtils.spatialGainForDistance(70.0f, 1.0f);
        float fullFadeEnd = AudioUtils.spatialGainForDistance(76.0f, 1.0f);
        assertTrue(fullAtEdge > fullFadeStart && fullFadeStart > fullFadeMiddle
                && fullFadeMiddle > fullFadeEnd && fullFadeEnd > 0.0f);
        assertEquals(0.0f, AudioUtils.spatialGainForDistance(76.8f, 1.0f));
        assertEquals(0.0f, AudioUtils.spatialGainForDistance(77.0f, 1.0f));

        float halfAtEdge = AudioUtils.spatialGainForDistance(32.0f, 0.5f);
        assertEquals(AudioUtils.gainForDistance(32.0f) * 0.5f, halfAtEdge, 1.0e-6f);
        float halfFade = AudioUtils.spatialGainForDistance(35.0f, 0.5f);
        assertTrue(halfFade > 0.0f && halfFade < AudioUtils.gainForDistance(35.0f) * 0.5f);
        assertEquals(0.0f, AudioUtils.spatialGainForDistance(38.4f, 0.5f));

        assertTrue(AudioUtils.spatialGainForDistance(17.0f, 0.25f) > 0.0f);
        assertEquals(0.0f, AudioUtils.spatialGainForDistance(19.2f, 0.25f));
        assertEquals(0.0f, AudioUtils.spatialGainForDistance(1.5f, 0.0f));
    }
}