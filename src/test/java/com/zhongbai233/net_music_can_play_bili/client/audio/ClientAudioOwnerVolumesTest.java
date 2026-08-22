package com.zhongbai233.net_music_can_play_bili.client.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientAudioOwnerVolumesTest {
    @AfterEach
    void clear() {
        ClientAudioOwnerVolumes.clear();
    }

    @Test
    void ownerValueCanBeReleasedWithoutLoadingTheOpenAlRegistry() {
        UUID owner = UUID.randomUUID();

        ClientAudioOwnerVolumes.put(owner, 1.5F);
        assertEquals(1.5F, ClientAudioOwnerVolumes.getOrDefault(owner, 1.0F));

        ClientAudioOwnerVolumes.remove(owner);
        assertEquals(1.0F, ClientAudioOwnerVolumes.getOrDefault(owner, 1.0F));
    }

    @Test
    void nullOwnerAlwaysUsesFallback() {
        ClientAudioOwnerVolumes.put(null, 1.5F);
        ClientAudioOwnerVolumes.remove(null);
        assertEquals(0.75F, ClientAudioOwnerVolumes.getOrDefault(null, 0.75F));
    }
}
