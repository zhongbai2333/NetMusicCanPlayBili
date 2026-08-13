package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Owns loading and failure placeholders for video sessions that do not have an active instance yet. */
final class PendingVideoSessionRegistry<T> {
    enum State {
        LOADING,
        FAILURE
    }

    record Snapshot<T>(PlaybackSessionId playbackSessionId, State state, List<T> projectorPositions,
            long startedNanoTime) {
        Snapshot {
            playbackSessionId = Objects.requireNonNull(playbackSessionId, "playbackSessionId");
            state = Objects.requireNonNull(state, "state");
            projectorPositions = List.copyOf(projectorPositions);
        }

        Snapshot(String sessionId, State state, List<T> projectorPositions, long startedNanoTime) {
            this(PlaybackSessionId.of(sessionId), state, projectorPositions, startedNanoTime);
        }

        String sessionId() {
            return playbackSessionId.value();
        }

        boolean containsProjector(T projector) {
            return projectorPositions.contains(projector);
        }

        Snapshot<T> withProjectors(Collection<? extends T> projectors) {
            return new Snapshot<>(playbackSessionId, state, copyPositions(projectors), startedNanoTime);
        }

        Snapshot<T> withoutProjector(T projector) {
            return new Snapshot<>(playbackSessionId, state, projectorPositions.stream()
                    .filter(candidate -> !Objects.equals(candidate, projector))
                    .toList(), startedNanoTime);
        }
    }

    private final Map<PlaybackSessionId, Snapshot<T>> sessions = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    PendingVideoSessionRegistry() {
        this(System::nanoTime);
    }

    PendingVideoSessionRegistry(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    void beginLoading(String sessionId, Collection<? extends T> projectorPositions) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        List<T> positions = copyPositions(projectorPositions);
        if (parsedSessionId == null || positions.isEmpty()) {
            return;
        }
        long now = nanoTime.getAsLong();
        sessions.compute(parsedSessionId,
                (ignored, current) -> new Snapshot<>(parsedSessionId, State.LOADING, positions,
                        current != null && current.state() == State.LOADING ? current.startedNanoTime() : now));
    }

    void markFailure(String sessionId, Collection<? extends T> projectorPositions) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        List<T> positions = copyPositions(projectorPositions);
        if (parsedSessionId == null || positions.isEmpty()) {
            return;
        }
        sessions.put(parsedSessionId,
                new Snapshot<>(parsedSessionId, State.FAILURE, positions, nanoTime.getAsLong()));
    }

    void updateProjectors(String sessionId, Collection<? extends T> projectorPositions) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (parsedSessionId == null) {
            return;
        }
        List<T> positions = copyPositions(projectorPositions);
        sessions.computeIfPresent(parsedSessionId, (ignored, current) -> current.withProjectors(positions));
    }

    void detachProjector(T projector) {
        if (projector == null) {
            return;
        }
        for (PlaybackSessionId sessionId : List.copyOf(sessions.keySet())) {
            sessions.computeIfPresent(sessionId, (ignored, current) -> {
                Snapshot<T> updated = current.withoutProjector(projector);
                return updated.projectorPositions().isEmpty() ? null : updated;
            });
        }
    }

    Snapshot<T> findByProjector(State state, T projector) {
        if (state == null || projector == null) {
            return null;
        }
        for (Snapshot<T> snapshot : sessions.values()) {
            if (snapshot.state() == state && snapshot.containsProjector(projector)) {
                return snapshot;
            }
        }
        return null;
    }

    boolean hasFailure(String sessionId) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        Snapshot<T> snapshot = parsedSessionId != null ? sessions.get(parsedSessionId) : null;
        return snapshot != null && snapshot.state() == State.FAILURE;
    }

    void clearLoading(String sessionId) {
        PlaybackSessionId.parse(sessionId).ifPresent(parsedSessionId -> sessions.computeIfPresent(parsedSessionId,
                (ignored, current) -> current.state() == State.LOADING ? null : current));
    }

    void clearSession(String sessionId) {
        PlaybackSessionId.parse(sessionId).ifPresent(sessions::remove);
    }

    void clear(State state) {
        if (state == null) {
            return;
        }
        sessions.entrySet().removeIf(entry -> entry.getValue().state() == state);
    }

    void clear() {
        sessions.clear();
    }

    int count(State state) {
        if (state == null) {
            return 0;
        }
        return (int) sessions.values().stream()
                .filter(snapshot -> snapshot.state() == state)
                .count();
    }

    private static <T> List<T> copyPositions(Collection<? extends T> projectorPositions) {
        return projectorPositions == null ? List.of() : List.copyOf(projectorPositions);
    }
}
