package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioPlaybackDemandIndexTest {
    @Test
    void metadataDoesNotStartUntilAnEndpointActuallyDemandsAudio() {
        AudioPlaybackDemandIndex<String> index = new AudioPlaybackDemandIndex<>();
        PlaybackSourceId source = PlaybackSourceId.of(UUID.randomUUID());
        PlaybackSessionId session = PlaybackSessionId.of("indexed-session");
        index.announce(source, session, "payload");

        assertTrue(index.claimStart(source, session).isEmpty());
        index.updateDemand(source, session, Set.of(UUID.randomUUID()), 1_000L);
        assertEquals("payload", index.claimStart(source, session).orElseThrow());
        assertTrue(index.markPlaying(source, session));
    }

    @Test
    void multipleEndpointsShareOneStartAndLastExitUsesGrace() {
        AudioPlaybackDemandIndex<String> index = new AudioPlaybackDemandIndex<>();
        PlaybackSourceId source = PlaybackSourceId.of(UUID.randomUUID());
        PlaybackSessionId session = PlaybackSessionId.of("shared-decoder-session");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        index.announce(source, session, "payload");
        index.updateDemand(source, session, Set.of(first, second), 0L);

        assertTrue(index.claimStart(source, session).isPresent());
        assertTrue(index.markPlaying(source, session));
        assertTrue(index.updateDemand(source, session, Set.of(second), 1_000L));
        assertFalse(index.claimStopAfterIdle(source, session, 10_000L, 1_500L));
        index.updateDemand(source, session, Set.of(), 10_000L);
        assertFalse(index.claimStopAfterIdle(source, session, 11_499L, 1_500L));
        assertTrue(index.claimStopAfterIdle(source, session, 11_500L, 1_500L));
        assertEquals(AudioPlaybackDemandIndex.State.METADATA,
                index.snapshot(source).orElseThrow().state());
    }

    @Test
    void lateReadyCannotPromoteAStartRetiredWhileOutsideRange() {
        AudioPlaybackDemandIndex<String> index = new AudioPlaybackDemandIndex<>();
        PlaybackSourceId source = PlaybackSourceId.of(UUID.randomUUID());
        PlaybackSessionId session = PlaybackSessionId.of("leave-before-ready");
        UUID endpoint = UUID.randomUUID();
        index.announce(source, session, "payload");
        index.updateDemand(source, session, Set.of(endpoint), 0L);
        assertTrue(index.claimStart(source, session).isPresent());

        index.updateDemand(source, session, Set.of(), 100L);
        assertTrue(index.claimStopAfterIdle(source, session, 1_600L, 1_500L));
        assertFalse(index.markPlaying(source, session));

        index.updateDemand(source, session, Set.of(endpoint), 2_000L);
        assertTrue(index.claimStart(source, session).isPresent());
        assertTrue(index.markPlaying(source, session));
    }
}
