package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps stable completed snapshots isolated by profile, floor and cell size.
 */
final class PadMapViewSnapshotCache {
    private static final int DEFAULT_MAX_ENTRIES = 16;
    private final int maxEntries;
    private final LinkedHashMap<Key, PadMapSnapshot> snapshots = new LinkedHashMap<>(16, 0.75F, true);

    PadMapViewSnapshotCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    PadMapViewSnapshotCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    PadMapSnapshot get(PadMapViewProfile profile, int floorY, int cellSize) {
        return snapshots.get(new Key(profile, floorY, cellSize));
    }

    void put(PadMapViewProfile profile, PadMapSnapshot snapshot) {
        if (snapshot != null) {
            snapshots.put(new Key(profile, snapshot.centerY(), snapshot.cellSizeBlocks()), snapshot);
            while (snapshots.size() > maxEntries) {
                var iterator = snapshots.entrySet().iterator();
                iterator.next();
                iterator.remove();
            }
        }
    }

    void invalidate(List<PadMapDirtyChunkTracker.Key> dirtyChunks) {
        for (Map.Entry<Key, PadMapSnapshot> entry : snapshots.entrySet()) {
            PadMapSnapshot invalidated = PadMapDirtyInvalidation.invalidateSnapshot(entry.getValue(),
                    entry.getKey().cellSize(), dirtyChunks);
            if (invalidated != null) {
                entry.setValue(invalidated);
            }
        }
    }

    void clear() {
        snapshots.clear();
    }

    private record Key(PadMapViewProfile profile, int floorY, int cellSize) {
    }
}