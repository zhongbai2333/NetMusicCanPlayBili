package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer.AudioPresence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoAudioPresenceRegistryTest {
    @Test
    void publishedPresenceSurvivesRepeatedReadinessChecksUntilExplicitForget() {
        VideoAudioPresenceRegistry registry = new VideoAudioPresenceRegistry();
        registry.publish("session", AudioPresence.PRESENT);

        assertEquals(AudioPresence.PRESENT, registry.presence("session"));
        assertEquals(AudioPresence.PRESENT, registry.presence("session"));

        registry.forget("session");
        assertEquals(AudioPresence.UNKNOWN, registry.presence("session"));
    }

    @Test
    void unknownDoesNotOverwriteAuthoritativePresence() {
        VideoAudioPresenceRegistry registry = new VideoAudioPresenceRegistry();
        registry.publish("session", AudioPresence.ABSENT);
        registry.publish("session", AudioPresence.UNKNOWN);

        assertEquals(AudioPresence.ABSENT, registry.presence("session"));
    }
}