package com.zhongbai233.net_music_can_play_bili.client.pad;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PadMapCellMemoryCacheTest {
    @Test
    void evictsLeastRecentlyUsedEntry() {
        PadMapCellMemoryCache cache = new PadMapCellMemoryCache(2);
        PadMapCellMemoryCache.Key first = key(1);
        PadMapCellMemoryCache.Key second = key(2);
        PadMapCellMemoryCache.Key third = key(3);

        cache.put(first, PadMapTileKind.GRASS);
        cache.put(second, PadMapTileKind.WATER);
        assertEquals(PadMapTileKind.GRASS, cache.get(first));
        cache.put(third, PadMapTileKind.ROCK);

        assertNull(cache.get(second));
        assertEquals(PadMapTileKind.GRASS, cache.get(first));
        assertEquals(PadMapTileKind.ROCK, cache.get(third));
    }

    @Test
    void entriesAreDetachedFromInternalMap() {
        PadMapCellMemoryCache cache = new PadMapCellMemoryCache(4);
        cache.put(key(1), PadMapTileKind.GRASS);

        List<PadMapCellMemoryCache.Entry> entries = cache.entries();
        cache.clear();

        assertEquals(1, entries.size());
        assertEquals(PadMapTileKind.GRASS, entries.get(0).kind());
        assertEquals(0, cache.size());
    }

    private static PadMapCellMemoryCache.Key key(int cellX) {
        return new PadMapCellMemoryCache.Key("overworld", 1, cellX, 0);
    }
}