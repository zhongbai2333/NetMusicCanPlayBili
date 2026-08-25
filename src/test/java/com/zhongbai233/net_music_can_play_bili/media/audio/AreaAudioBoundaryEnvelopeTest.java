package com.zhongbai233.net_music_can_play_bili.media.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaAudioBoundaryEnvelopeTest {
    @Test
    void initialStateDoesNotLeakFromAnAlreadyBlockedRoom() {
        AreaAudioBoundaryEnvelope envelope = new AreaAudioBoundaryEnvelope(1_000L, 1_000L);

        assertEquals(0.0F, envelope.gain(false, 10_000L));
        assertEquals(1.0F, new AreaAudioBoundaryEnvelope(1_000L, 1_000L).gain(true, 10_000L));
    }

    @Test
    void fadesBothDirectionsWithSmoothContinuousRetargeting() {
        AreaAudioBoundaryEnvelope envelope = new AreaAudioBoundaryEnvelope(1_000L, 1_000L);
        envelope.gain(true, 0L);
        assertEquals(1.0F, envelope.gain(false, 100L));
        assertEquals(0.5F, envelope.gain(false, 600L), 1.0e-6F);

        float atRetarget = envelope.gain(true, 600L);
        assertEquals(0.5F, atRetarget, 1.0e-6F);
        float recovering = envelope.gain(true, 850L);
        assertTrue(recovering > atRetarget && recovering < 1.0F);
        assertEquals(1.0F, envelope.gain(true, 1_600L), 1.0e-6F);
    }
}
