package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybackPresentationEnvelopeTest {
    @Test
    void lateDecoderReadinessStartsItsOwnFade() {
        PlaybackPresentationEnvelope envelope = new PlaybackPresentationEnvelope();
        long ready = 2_000_000_000L;

        assertEquals(0.0F, envelope.gain(false, ready - 1L));
        assertEquals(0.0F, envelope.gain(true, ready));
        assertEquals(0.5F, envelope.gain(true,
                ready + PlaybackPresentationEnvelope.DURATION_NANOS / 2L), 1.0e-6F);
        assertEquals(1.0F, envelope.gain(true,
                ready + PlaybackPresentationEnvelope.DURATION_NANOS));
    }

    @Test
    void reentryAlwaysGetsANewFade() {
        PlaybackPresentationEnvelope envelope = new PlaybackPresentationEnvelope();
        envelope.gain(true, 1_000L);
        envelope.gain(true, 1_000L + PlaybackPresentationEnvelope.DURATION_NANOS);
        assertEquals(0.0F, envelope.gain(false, 2_000L));
        assertEquals(0.0F, envelope.gain(true, 3_000L));
    }
}
