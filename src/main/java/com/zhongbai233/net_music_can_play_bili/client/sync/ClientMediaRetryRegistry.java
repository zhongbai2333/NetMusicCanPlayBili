package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Owns one-shot client stream retry admission by exact source/session identity. */
final class ClientMediaRetryRegistry {
    private final Set<Key> pending = ConcurrentHashMap.newKeySet();

    synchronized boolean tryMark(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
        return sourceId != null && sessionId != null && pending.add(new Key(sourceId, sessionId));
    }

    synchronized boolean contains(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
        return sourceId != null && sessionId != null && pending.contains(new Key(sourceId, sessionId));
    }

    synchronized boolean forget(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
        return sourceId != null && sessionId != null && pending.remove(new Key(sourceId, sessionId));
    }

    synchronized void forgetSource(PlaybackSourceId sourceId) {
        if (sourceId == null) {
            return;
        }
        pending.removeIf(key -> key.sourceId().equals(sourceId));
    }

    synchronized boolean dispatchIfPending(PlaybackSourceId sourceId, PlaybackSessionId sessionId,
            BooleanSupplier dispatch) {
        if (sourceId == null || sessionId == null || dispatch == null
                || !pending.contains(new Key(sourceId, sessionId))) {
            return false;
        }
        return dispatch.getAsBoolean();
    }

    synchronized int size() {
        return pending.size();
    }

    synchronized void clear() {
        pending.clear();
    }

    private record Key(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
        private Key {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }
}
