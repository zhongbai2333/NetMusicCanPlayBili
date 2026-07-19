package com.zhongbai233.net_music_can_play_bili.item.pad;

import java.util.List;

/** Pure lock-state transition used by Pad documents and ordinary unit tests. */
final class PadDocumentLockPolicy {
    private PadDocumentLockPolicy() {
    }

    static <M, T> LockCopy<M, T> transition(boolean currentLocked, boolean requestedLocked,
            long updatedAtMillis, long sequence, List<M> mediaEntries, List<T> triggerPoints,
            boolean bumpVersion, long nowMillis) {
        if (currentLocked == requestedLocked) {
            return null;
        }
        return new LockCopy<>(requestedLocked,
                bumpVersion ? Math.max(0L, nowMillis) : updatedAtMillis,
                bumpVersion ? sequence + 1L : sequence,
                mediaEntries, triggerPoints);
    }

    record LockCopy<M, T>(boolean locked, long updatedAtMillis, long sequence,
            List<M> mediaEntries, List<T> triggerPoints) {
    }
}