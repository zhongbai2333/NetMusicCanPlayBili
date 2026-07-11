package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps stable completed snapshots isolated by profile, floor and cell size. */
final class PadMapViewSnapshotCache {
    private final LinkedHashMap<Key, PadMapSnapshot> snapshots = new LinkedHashMap<>();

    PadMapSnapshot get(PadMapViewProfile profile, int floorY, int cellSize) {
        return snapshots.get(new Key(profile, floorY, cellSize));
    }

    void put(PadMapViewProfile profile, PadMapSnapshot snapshot) {
        if (snapshot != null) {
            snapshots.put(new Key(profile, snapshot.centerY(), snapshot.cellSizeBlocks()), snapshot);
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