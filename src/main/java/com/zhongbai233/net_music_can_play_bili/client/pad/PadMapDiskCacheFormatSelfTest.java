package com.zhongbai233.net_music_can_play_bili.client.pad;

/** Lightweight self tests for Pad map disk cache binary format helpers. */
public final class PadMapDiskCacheFormatSelfTest {
    private PadMapDiskCacheFormatSelfTest() {
    }

    public static void main(String[] args) {
        preservesCurrentHeaders();
        matchesExactHeaderOnly();
        encodesNullTilesAsUnknown();
        decodesValidAndInvalidTileOrdinals();
        System.out.println("PadMapDiskCacheFormatSelfTest passed");
    }

    private static void preservesCurrentHeaders() {
        if (PadMapDiskCacheFormat.CELLS.magic() != 0x4E504D43 || PadMapDiskCacheFormat.CELLS.version() != 18) {
            throw new AssertionError("cells cache header changed unexpectedly");
        }
        if (PadMapDiskCacheFormat.SNAPSHOT.magic() != 0x4E504D53 || PadMapDiskCacheFormat.SNAPSHOT.version() != 18) {
            throw new AssertionError("snapshot cache header changed unexpectedly");
        }
        if (PadMapDiskCacheFormat.CHUNKS.magic() != 0x4E504B43 || PadMapDiskCacheFormat.CHUNKS.version() != 18) {
            throw new AssertionError("chunk cache header changed unexpectedly");
        }
    }

    private static void matchesExactHeaderOnly() {
        if (!PadMapDiskCacheFormat.CELLS.matches(0x4E504D43, 18)) {
            throw new AssertionError("current cells header should match");
        }
        if (PadMapDiskCacheFormat.CELLS.matches(0x4E504D43, 17)
            || PadMapDiskCacheFormat.CELLS.matches(0x4E504D44, 18)) {
            throw new AssertionError("wrong magic or version should not match");
        }
    }

    private static void encodesNullTilesAsUnknown() {
        if (PadMapDiskCacheFormat.encodeTile(null) != PadMapTileKind.UNKNOWN.ordinal()) {
            throw new AssertionError("null tile should be encoded as UNKNOWN");
        }
    }

    private static void decodesValidAndInvalidTileOrdinals() {
        for (PadMapTileKind kind : PadMapTileKind.values()) {
            if (PadMapDiskCacheFormat.decodeTile(kind.ordinal()) != kind) {
                throw new AssertionError("valid tile ordinal failed round trip: " + kind);
            }
        }
        if (PadMapDiskCacheFormat.decodeTile(-1) != null
                || PadMapDiskCacheFormat.decodeTile(PadMapTileKind.values().length) != null) {
            throw new AssertionError("invalid tile ordinals should decode to null");
        }
    }
}
