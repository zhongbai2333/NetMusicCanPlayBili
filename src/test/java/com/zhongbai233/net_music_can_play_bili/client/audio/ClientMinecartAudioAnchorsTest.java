package com.zhongbai233.net_music_can_play_bili.client.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMinecartAudioAnchorsTest {
    @AfterEach
    void tearDown() {
        ClientMinecartAudioAnchors.clear();
    }

    @Test
    void stringFacadeUsesTypedSessionKey() {
        UUID entityId = UUID.randomUUID();

        ClientMinecartAudioAnchors.register("minecart-session", 42, entityId);

        assertTrue(ClientMinecartAudioAnchors.isMoving("minecart-session"));
        assertEquals(entityId, ClientMinecartAudioAnchors.entityUuid("minecart-session"));

        ClientMinecartAudioAnchors.forget("minecart-session");
        assertFalse(ClientMinecartAudioAnchors.isMoving("minecart-session"));
        assertNull(ClientMinecartAudioAnchors.entityUuid("minecart-session"));
    }

    @Test
    void malformedSessionIsIgnored() {
        ClientMinecartAudioAnchors.register("invalid session", 42, UUID.randomUUID());

        assertFalse(ClientMinecartAudioAnchors.isMoving("invalid session"));
        assertNull(ClientMinecartAudioAnchors.entityUuid("invalid session"));
    }
}
