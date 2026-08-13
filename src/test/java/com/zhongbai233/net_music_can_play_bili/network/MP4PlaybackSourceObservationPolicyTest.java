package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MP4PlaybackSourceObservationPolicyTest {
    @Test
    void aPreviouslyUnknownSourceStartsDiscovery() {
        assertEquals(MP4PlaybackSourceObservationPolicy.Action.START,
                MP4PlaybackSourceObservationPolicy.action(null,
                        observation(ClientMediaSyncPayload.SOURCE_ITEM, 12, "ignored", -1)));
    }

    @Test
    void playerObservationPreservesTheExistingPlayerSourcePolicy() {
        MP4PlaybackSourceObservationPolicy.Observation player = observation(
                ClientMediaSyncPayload.SOURCE_PLAYER, 10, "old-position", -1);
        MP4PlaybackSourceObservationPolicy.Observation item = observation(
                ClientMediaSyncPayload.SOURCE_ITEM, 10, "old-position", -1);
        MP4PlaybackSourceObservationPolicy.Observation observedPlayer = observation(
                ClientMediaSyncPayload.SOURCE_PLAYER, 99, "new-position", -1);

        assertEquals(MP4PlaybackSourceObservationPolicy.Action.KEEP,
                MP4PlaybackSourceObservationPolicy.action(player, observedPlayer));
        assertEquals(MP4PlaybackSourceObservationPolicy.Action.MIGRATE,
                MP4PlaybackSourceObservationPolicy.action(item, observedPlayer));
    }

    @Test
    void itemObservationUsesTheEntityIdentity() {
        MP4PlaybackSourceObservationPolicy.Observation item = observation(
                ClientMediaSyncPayload.SOURCE_ITEM, 42, "old-position", -1);

        assertEquals(MP4PlaybackSourceObservationPolicy.Action.KEEP,
                MP4PlaybackSourceObservationPolicy.action(item,
                        observation(ClientMediaSyncPayload.SOURCE_ITEM, 42, "new-position", -1)));
        assertEquals(MP4PlaybackSourceObservationPolicy.Action.MIGRATE,
                MP4PlaybackSourceObservationPolicy.action(item,
                        observation(ClientMediaSyncPayload.SOURCE_ITEM, 43, "old-position", -1)));
    }

    @Test
    void containerObservationsUseTheirStableLocationFields() {
        MP4PlaybackSourceObservationPolicy.Observation block = observation(
                ClientMediaSyncPayload.SOURCE_BLOCK, -1, "4,5,6", 3);
        MP4PlaybackSourceObservationPolicy.Observation entity = observation(
                ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY, 77, "ignored", 5);

        assertEquals(MP4PlaybackSourceObservationPolicy.Action.KEEP,
                MP4PlaybackSourceObservationPolicy.action(block,
                        observation(ClientMediaSyncPayload.SOURCE_BLOCK, -1, "4,5,6", 3)));
        assertEquals(MP4PlaybackSourceObservationPolicy.Action.MIGRATE,
                MP4PlaybackSourceObservationPolicy.action(block,
                        observation(ClientMediaSyncPayload.SOURCE_BLOCK, -1, "4,5,6", 4)));
        assertEquals(MP4PlaybackSourceObservationPolicy.Action.KEEP,
                MP4PlaybackSourceObservationPolicy.action(entity,
                        observation(ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY, 77, "changed", 5)));
        assertEquals(MP4PlaybackSourceObservationPolicy.Action.MIGRATE,
                MP4PlaybackSourceObservationPolicy.action(entity,
                        observation(ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY, 78, "ignored", 5)));
    }

    @Test
    void physicalIdentityIgnoresTimelineDataButDetectsLocationChanges() {
        UUID ownerId = UUID.randomUUID();
        MP4PlaybackSourceObservationPolicy.PhysicalIdentity source = identity(ownerId, "2,3,4");
        MP4PlaybackSourceObservationPolicy.PhysicalIdentity sameSource = identity(ownerId, "2,3,4");
        MP4PlaybackSourceObservationPolicy.PhysicalIdentity moved = identity(ownerId, "8,3,4");

        assertTrue(MP4PlaybackSourceObservationPolicy.samePhysicalSource(source, sameSource));
        assertFalse(MP4PlaybackSourceObservationPolicy.samePhysicalSource(source, moved));
        assertFalse(MP4PlaybackSourceObservationPolicy.samePhysicalSource(source, null));
    }

    private static MP4PlaybackSourceObservationPolicy.Observation observation(int type, int entityId,
            Object position, int slot) {
        return new MP4PlaybackSourceObservationPolicy.Observation(type, entityId, position, slot);
    }

    private static MP4PlaybackSourceObservationPolicy.PhysicalIdentity identity(UUID ownerId, Object position) {
        return new MP4PlaybackSourceObservationPolicy.PhysicalIdentity("overworld", ownerId,
                ClientMediaSyncPayload.SOURCE_BLOCK, -1, position, 1);
    }
}
