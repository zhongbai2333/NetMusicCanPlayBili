package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioEndpointSubscriptionTrackerTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000071");
    private static final PlaybackSourceId SOURCE = PlaybackSourceId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000072"));
    private static final UUID ENDPOINT = UUID.fromString("00000000-0000-0000-0000-000000000073");
    private static final long SOURCE_POS = packedPos(0, 64, 0);

    @Test
    void firstApproachReceivesOneResetThenNoRepeatedSnapshot() {
        AudioEndpointSubscriptionTracker tracker = new AudioEndpointSubscriptionTracker();
        var endpoint = endpoint(1L, 32.0F);

        var first = tracker.update(PLAYER, SOURCE, "session-a", SOURCE_POS,
                85.0D, 64.5D, 0.5D, 32, List.of(endpoint));
        var stable = tracker.update(PLAYER, SOURCE, "session-a", SOURCE_POS,
                85.0D, 64.5D, 0.5D, 32, List.of(endpoint));

        assertTrue(first.playbackRecipient());
        assertTrue(first.reset());
        assertEquals(List.of(endpoint), first.upserts());
        assertFalse(stable.packetRequired());
    }

    @Test
    void changedRevisionAndDepartureProduceSmallDeltas() {
        AudioEndpointSubscriptionTracker tracker = new AudioEndpointSubscriptionTracker();
        tracker.update(PLAYER, SOURCE, "session-a", SOURCE_POS,
                85.0D, 64.5D, 0.5D, 32, List.of(endpoint(1L, 32.0F)));

        var changed = tracker.update(PLAYER, SOURCE, "session-a", SOURCE_POS,
                85.0D, 64.5D, 0.5D, 32, List.of(endpoint(2L, 48.0F)));
        var departed = tracker.update(PLAYER, SOURCE, "session-a", SOURCE_POS,
                1_000.0D, 64.5D, 0.5D, 32, List.of(endpoint(2L, 48.0F)));

        assertFalse(changed.reset());
        assertEquals(1, changed.upserts().size());
        assertTrue(departed.reset());
        assertFalse(departed.subscribed());
        assertFalse(departed.playbackRecipient());
    }

    @Test
    void newPlaybackSessionForcesFreshSnapshotForAutomaticSongChanges() {
        AudioEndpointSubscriptionTracker tracker = new AudioEndpointSubscriptionTracker();
        tracker.update(PLAYER, SOURCE, "session-a", SOURCE_POS,
                85.0D, 64.5D, 0.5D, 32, List.of(endpoint(1L, 32.0F)));

        var nextSong = tracker.update(PLAYER, SOURCE, "session-b", SOURCE_POS,
                85.0D, 64.5D, 0.5D, 32, List.of(endpoint(1L, 32.0F)));

        assertTrue(nextSong.reset());
        assertEquals(1, nextSong.upserts().size());
    }

    @Test
    void departureAndStoppedSessionKeepGenerationMonotonicForReentry() {
        AudioEndpointSubscriptionTracker tracker = new AudioEndpointSubscriptionTracker();
        var first = tracker.update(PLAYER, SOURCE, "session-a", SOURCE_POS,
                85.0D, 64.5D, 0.5D, 32, List.of(endpoint(1L, 32.0F)));
        var departed = tracker.update(PLAYER, SOURCE, "session-a", SOURCE_POS,
                1_000.0D, 64.5D, 0.5D, 32, List.of(endpoint(1L, 32.0F)));
        var reentered = tracker.update(PLAYER, SOURCE, "session-a", SOURCE_POS,
                85.0D, 64.5D, 0.5D, 32, List.of(endpoint(1L, 32.0F)));
        tracker.forgetSource(SOURCE);
        var automaticNextSong = tracker.update(PLAYER, SOURCE, "session-b", SOURCE_POS,
                85.0D, 64.5D, 0.5D, 32, List.of(endpoint(1L, 32.0F)));

        assertTrue(departed.generation() > first.generation());
        assertTrue(reentered.generation() > departed.generation());
        assertTrue(reentered.reset());
        assertTrue(automaticNextSong.generation() > reentered.generation());
        assertTrue(automaticNextSong.reset());
    }

    @Test
    void cellBucketingWorksAcrossNegativeCoordinates() {
        AudioEndpointSubscriptionTracker tracker = new AudioEndpointSubscriptionTracker();
        var negative = new AudioEndpointSubscriptionTracker.Endpoint(ENDPOINT,
                packedPos(-33, 64, -33), 0, 1.0F, false, 8.0F, 1L);
        var update = tracker.update(PLAYER, SOURCE, "session-a", SOURCE_POS,
                -70.0D, 64.5D, -33.0D, 16, List.of(negative));
        assertTrue(update.playbackRecipient());
    }

    private static AudioEndpointSubscriptionTracker.Endpoint endpoint(long revision, float distance) {
        return new AudioEndpointSubscriptionTracker.Endpoint(ENDPOINT,
                packedPos(0, 64, 0), 0, 1.0F, false, distance, revision);
    }

    private static long packedPos(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | (long) y & 0xFFFL;
    }
}
