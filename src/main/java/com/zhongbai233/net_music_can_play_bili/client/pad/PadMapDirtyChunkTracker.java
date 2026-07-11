package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Tracks dirty world chunks that may invalidate a cached Pad map snapshot. */
final class PadMapDirtyChunkTracker {
    private final int limit;
    private final LinkedHashMap<Key, Boolean> dirtyChunks = new LinkedHashMap<>(256, 0.75F, true);

    PadMapDirtyChunkTracker(int limit) {
        this.limit = Math.max(1, limit);
    }

    void mark(String dimension, int chunkX, int chunkZ) {
        if (dimension == null || dimension.isBlank()) {
            return;
        }
        synchronized (dirtyChunks) {
            dirtyChunks.put(new Key(dimension, chunkX, chunkZ), Boolean.TRUE);
            trimToLimit();
        }
    }

    List<Key> drainForDimension(String dimension, int maxCount) {
        if (dimension == null || dimension.isBlank() || maxCount <= 0) {
            return List.of();
        }
        List<Key> drained = new ArrayList<>(Math.max(1, maxCount));
        synchronized (dirtyChunks) {
            var iterator = dirtyChunks.keySet().iterator();
            while (iterator.hasNext()) {
                Key key = iterator.next();
                if (key.dimension().equals(dimension)) {
                    iterator.remove();
                    drained.add(key);
                    if (drained.size() >= maxCount) {
                        break;
                    }
                }
            }
        }
        return drained;
    }

    int size() {
        synchronized (dirtyChunks) {
            return dirtyChunks.size();
        }
    }

    void clear() {
        synchronized (dirtyChunks) {
            dirtyChunks.clear();
        }
    }

    private void trimToLimit() {
        while (dirtyChunks.size() > limit) {
            var iterator = dirtyChunks.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    record Key(String dimension, int chunkX, int chunkZ) {
    }
}
