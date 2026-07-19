package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadMapDiskCacheFormatTest {
    @Test
    void preservesCurrentHeaders() {
        assertEquals(0x4E504D43, PadMapDiskCacheFormat.CELLS.magic());
        assertEquals(18, PadMapDiskCacheFormat.CELLS.version());
        assertEquals(0x4E504D53, PadMapDiskCacheFormat.SNAPSHOT.magic());
        assertEquals(18, PadMapDiskCacheFormat.SNAPSHOT.version());
    }

    @Test
    void matchesExactHeaderOnly() {
        assertTrue(PadMapDiskCacheFormat.CELLS.matches(0x4E504D43, 18));
        assertFalse(PadMapDiskCacheFormat.CELLS.matches(0x4E504D43, 17));
        assertFalse(PadMapDiskCacheFormat.CELLS.matches(0x4E504D44, 18));
    }

    @Test
    void encodesNullTilesAsUnknown() {
        assertEquals(PadMapTileKind.UNKNOWN.ordinal(), PadMapDiskCacheFormat.encodeTile(null));
    }

    @Test
    void decodesValidAndInvalidTileOrdinals() {
        for (PadMapTileKind kind : PadMapTileKind.values()) {
            assertEquals(kind, PadMapDiskCacheFormat.decodeTile(kind.ordinal()));
        }
        assertNull(PadMapDiskCacheFormat.decodeTile(-1));
        assertNull(PadMapDiskCacheFormat.decodeTile(PadMapTileKind.values().length));
    }
}