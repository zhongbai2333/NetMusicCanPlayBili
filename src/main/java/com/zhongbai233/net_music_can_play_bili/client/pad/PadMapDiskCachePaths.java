package com.zhongbai233.net_music_can_play_bili.client.pad;

import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Builds stable Pad map disk cache paths without touching cache serialization
 * format.
 */
final class PadMapDiskCachePaths {
    private static final String CACHE_ROOT_FOLDER = "netmusic-pad-map-cache";

    private PadMapDiskCachePaths() {
    }

    static Path cells(Minecraft minecraft, String syncedWorldScopeId) {
        return cacheRoot(minecraft, syncedWorldScopeId).resolve("cells.bin");
    }

    static Path snapshot(Minecraft minecraft, String syncedWorldScopeId) {
        return cacheRoot(minecraft, syncedWorldScopeId).resolve("snapshot.bin");
    }

    static Path cacheRoot(Minecraft minecraft, String syncedWorldScopeId) {
        return minecraft.gameDirectory.toPath()
                .resolve(CACHE_ROOT_FOLDER)
                .resolve(worldScope(syncedWorldScopeId))
                .resolve(dimensionScope(minecraft));
    }

    static String worldScope(String syncedWorldScopeId) {
        if (!isBlank(syncedWorldScopeId)) {
            return "world-" + stablePathToken(syncedWorldScopeId);
        }
        return "pending-world-scope";
    }

    static String dimensionScope(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) {
            return "unknown-dimension";
        }
        return stablePathToken(minecraft.level.dimension().identifier().toString());
    }

    static String stablePathToken(String value) {
        String normalized = isBlank(value) ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
        String readable = normalized.replace('\\', '_').replace('/', '_').replace(':', '_')
                .replace('|', '_').replace(' ', '_');
        readable = readable.replaceAll("[^a-z0-9._-]", "_");
        if (readable.length() > 48) {
            readable = readable.substring(0, 48);
        }
        return readable + "-" + shortHash(normalized);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 5);
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
