package com.zhongbai233.net_music_can_play_bili.media.audio;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaAudioIsolationTest {
    @Test
    void strictIdentitySeparatesParentsChildrenAndSiblings() {
        UUID room = UUID.randomUUID();
        AreaAudioZone output = AreaAudioZone.isolated(room);

        assertTrue(output.allows(AreaAudioZone.isolated(room)));
        assertFalse(output.allows(AreaAudioZone.isolated(UUID.randomUUID())));
        assertFalse(output.allows(AreaAudioZone.unrestricted()));
    }

    @Test
    void wildnessIsOneSharedRoomAndAbsentCompatIsUnrestricted() {
        assertTrue(AreaAudioZone.wildness().allows(AreaAudioZone.wildness()));
        assertTrue(AreaAudioZone.unrestricted().allows(AreaAudioZone.isolated(UUID.randomUUID())));
        assertTrue(AreaAudioZone.unrestricted().allows(AreaAudioZone.unrestricted()));
    }

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

    @Test
    void onePlaybackCanExposeIndependentOutputsInTwoRooms() {
        AreaAudioOutputFades<String> outputs = new AreaAudioOutputFades<>();
        UUID firstRoom = UUID.randomUUID();
        UUID secondRoom = UUID.randomUUID();
        outputs.acceptListener(AreaAudioZone.isolated(firstRoom));
        outputs.set("physical-speaker", AreaAudioZone.isolated(firstRoom));
        outputs.set("console-speaker", AreaAudioZone.isolated(secondRoom));

        assertEquals(1.0F, outputs.gain("physical-speaker", 0L));
        assertEquals(0.0F, outputs.gain("console-speaker", 0L));

        outputs.acceptListener(AreaAudioZone.isolated(secondRoom));
        outputs.gain("physical-speaker", 100L);
        outputs.gain("console-speaker", 100L);
        float leaking = outputs.gain("physical-speaker", 400_000_100L);
        assertTrue(leaking > 0.0F && leaking < 1.0F);

        long finished = 100L + Math.max(AreaAudioBoundaryEnvelope.DEFAULT_FADE_IN_NANOS,
                AreaAudioBoundaryEnvelope.DEFAULT_FADE_OUT_NANOS);
        assertEquals(0.0F, outputs.gain("physical-speaker", finished), 1.0e-6F);
        assertEquals(1.0F, outputs.gain("console-speaker", finished), 1.0e-6F);
    }

    @Test
    void removingZoneRestoresOrdinaryServerBehaviour() {
        AreaAudioOutputFades<String> outputs = new AreaAudioOutputFades<>();
        outputs.acceptListener(AreaAudioZone.isolated(UUID.randomUUID()));
        outputs.set("source", AreaAudioZone.isolated(UUID.randomUUID()));
        assertEquals(0.0F, outputs.gain("source", 0L));

        outputs.remove("source");
        assertEquals(1.0F, outputs.gain("source", 1L));
    }
}
