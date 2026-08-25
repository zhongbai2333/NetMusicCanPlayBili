package com.zhongbai233.net_music_can_play_bili.media.audio;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaAudioZoneTest {
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
}
