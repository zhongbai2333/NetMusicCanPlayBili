package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioEndpointIndexTest {
    @Test
    void endpointsRemainAddressableWithoutBlockReferences() {
        PlaybackSourceId source = PlaybackSourceId.of(UUID.randomUUID());
        IndexedAudioEndpoint endpoint = new IndexedAudioEndpoint(UUID.randomUUID(), source,
                "minecraft:overworld", 200.5, 64.5, -40.5, 64.0F, 1.0F, 1.0F,
                IndexedAudioEndpoint.Kind.SPEAKER, 1L);
        AudioEndpointIndex index = new AudioEndpointIndex();
        index.upsert(endpoint);

        assertEquals(1, index.endpointsFor(source).size());
        assertTrue(index.audibleDemands(source, "minecraft:overworld", 264.0, 64.5, -40.5)
                .contains(endpoint.endpointId()));
        assertTrue(index.audibleDemands(source, "minecraft:overworld", 280.0, 64.5, -40.5).isEmpty());
    }

    @Test
    void staleEndpointRevisionCannotOverwriteNewConfiguration() {
        PlaybackSourceId source = PlaybackSourceId.of(UUID.randomUUID());
        UUID endpointId = UUID.randomUUID();
        AudioEndpointIndex index = new AudioEndpointIndex();
        index.upsert(new IndexedAudioEndpoint(endpointId, source, "minecraft:overworld",
                0, 0, 0, 32, 1, 1, IndexedAudioEndpoint.Kind.SPEAKER, 2));
        index.upsert(new IndexedAudioEndpoint(endpointId, source, "minecraft:overworld",
                100, 0, 0, 32, 1, 1, IndexedAudioEndpoint.Kind.SPEAKER, 1));

        assertEquals(0.0D, index.endpointsFor(source).getFirst().x());
        assertFalse(index.audibleDemands(source, "minecraft:overworld", 100, 0, 0).contains(endpointId));
    }

    @Test
    void movingTowardAnEndpointCreatesOnlyPredictiveDemand() {
        PlaybackSourceId source = PlaybackSourceId.of(UUID.randomUUID());
        UUID endpointId = UUID.randomUUID();
        AudioEndpointIndex index = new AudioEndpointIndex();
        index.upsert(new IndexedAudioEndpoint(endpointId, source, "minecraft:overworld",
                0, 0, 0, 16, 1, 1, IndexedAudioEndpoint.Kind.SPEAKER, 1));

        assertTrue(index.audibleDemands(source, "minecraft:overworld", 50, 0, 0).isEmpty());
        assertTrue(index.anticipatedDemands(source, "minecraft:overworld",
                50, 0, 0, -1, 0, 0).contains(endpointId));
        assertFalse(index.anticipatedDemands(source, "minecraft:overworld",
                50, 0, 0, 1, 0, 0).contains(endpointId));
    }
}
