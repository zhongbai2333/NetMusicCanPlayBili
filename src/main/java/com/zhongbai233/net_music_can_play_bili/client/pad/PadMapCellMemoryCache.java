package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Thread-safe bounded LRU store for sampled map cells. */
final class PadMapCellMemoryCache {
    private final int limit;
    private final LinkedHashMap<Key, PadMapTileKind> values = new LinkedHashMap<>(1024, 0.75F, true);

    PadMapCellMemoryCache(int limit) {
        this.limit = Math.max(1, limit);
    }

    synchronized PadMapTileKind get(Key key) {
        return values.get(key);
    }

    synchronized void put(Key key, PadMapTileKind kind) {
        values.put(key, kind);
        while (values.size() > limit) {
            values.remove(values.keySet().iterator().next());
        }
    }

    synchronized PadMapTileKind remove(Key key) {
        return values.remove(key);
    }

    synchronized int size() {
        return values.size();
    }

    synchronized void clear() {
        values.clear();
    }

    synchronized List<Entry> entries() {
        List<Entry> entries = new ArrayList<>(values.size());
        values.forEach((key, kind) -> entries.add(new Entry(key, kind)));
        return entries;
    }

    record Entry(Key key, PadMapTileKind kind) {
    }

    record Key(String dimension, int cellSize, int cellX, int cellZ) {
    }
}