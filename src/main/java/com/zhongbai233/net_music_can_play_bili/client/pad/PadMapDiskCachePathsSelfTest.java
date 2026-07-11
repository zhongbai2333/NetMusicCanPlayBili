package com.zhongbai233.net_music_can_play_bili.client.pad;

/** Lightweight self tests for Pad map disk cache path token generation. */
public final class PadMapDiskCachePathsSelfTest {
    private PadMapDiskCachePathsSelfTest() {
    }

    public static void main(String[] args) {
        normalizesAndHashesPathTokens();
        truncatesReadableTokenPrefix();
        preservesPendingWorldScope();
        System.out.println("PadMapDiskCachePathsSelfTest passed");
    }

    private static void normalizesAndHashesPathTokens() {
        String token = PadMapDiskCachePaths.stablePathToken("Minecraft:Overworld/Save 01");
        if (!token.startsWith("minecraft_overworld_save_01-")) {
            throw new AssertionError("token should normalize separators and spaces: " + token);
        }
        if (token.length() <= "minecraft_overworld_save_01-".length()) {
            throw new AssertionError("token should include a stable hash suffix: " + token);
        }
    }

    private static void truncatesReadableTokenPrefix() {
        String token = PadMapDiskCachePaths.stablePathToken("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz");
        String prefix = token.substring(0, token.lastIndexOf('-'));
        if (prefix.length() != 48) {
            throw new AssertionError("readable token prefix should be capped at 48 chars: " + token);
        }
    }

    private static void preservesPendingWorldScope() {
        if (!"pending-world-scope".equals(PadMapDiskCachePaths.worldScope(null))) {
            throw new AssertionError("null world scope should remain pending");
        }
        if (!PadMapDiskCachePaths.worldScope("World A").startsWith("world-world_a-")) {
            throw new AssertionError("synced world scope should be namespaced and tokenized");
        }
    }
}
