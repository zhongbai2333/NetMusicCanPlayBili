package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadMapDiskCachePathsTest {
    @Test
    void normalizesAndHashesPathTokens() {
        String token = PadMapDiskCachePaths.stablePathToken("Minecraft:Overworld/Save 01");

        assertTrue(token.startsWith("minecraft_overworld_save_01-"),
                () -> "token should normalize separators and spaces: " + token);
        assertTrue(token.length() > "minecraft_overworld_save_01-".length(),
                () -> "token should include a stable hash suffix: " + token);
    }

    @Test
    void truncatesReadableTokenPrefix() {
        String token = PadMapDiskCachePaths.stablePathToken("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz");
        String prefix = token.substring(0, token.lastIndexOf('-'));

        assertEquals(48, prefix.length(), () -> "readable token prefix should be capped at 48 chars: " + token);
    }

    @Test
    void preservesPendingWorldScope() {
        assertEquals("pending-world-scope", PadMapDiskCachePaths.worldScope(null));
        assertTrue(PadMapDiskCachePaths.worldScope("World A").startsWith("world-world_a-"),
                "synced world scope should be namespaced and tokenized");
    }
}