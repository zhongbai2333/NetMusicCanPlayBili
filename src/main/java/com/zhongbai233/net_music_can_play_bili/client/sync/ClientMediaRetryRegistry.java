package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Owns one-shot client stream retry admission by exact source/session identity. */
final class ClientMediaRetryRegistry {
    private final java.util.Map<Key, Integer> attempts = new java.util.HashMap<>();
    enum Admission { SCHEDULE, ALREADY_PENDING, EXHAUSTED }
    synchronized Admission admitFailure(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
        Key key = new Key(sourceId, sessionId);
        if (pending.contains(key)) return Admission.ALREADY_PENDING;
        if (attempts.getOrDefault(key, 0) >= 1) return Admission.EXHAUSTED;
        attempts.put(key, 1);
        pending.add(key);
        return Admission.SCHEDULE;
    }

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
        attempts.keySet().removeIf(key -> key.sourceId().equals(sourceId));
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
        attempts.clear();
    }

    private record Key(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
        private Key {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }
}
