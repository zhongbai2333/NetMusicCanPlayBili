package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns metadata-only, starting, and playing states independently from blocks.
 * A payload can be claimed for decoding only while at least one endpoint demands it.
 */
public final class AudioPlaybackDemandIndex<T> {
    private final Map<PlaybackSourceId, Entry<T>> entries = new ConcurrentHashMap<>();

    public void announce(PlaybackSourceId sourceId, PlaybackSessionId sessionId, T payload) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(payload, "payload");
        entries.compute(sourceId, (ignored, current) -> current != null && current.sessionId().equals(sessionId)
                ? current.withPayload(payload)
                : new Entry<>(sessionId, payload, State.METADATA, Set.of(), -1L));
    }

    public boolean updateDemand(PlaybackSourceId sourceId, PlaybackSessionId sessionId,
            Set<UUID> endpointIds, long nowMillis) {
        if (sourceId == null || sessionId == null) {
            return false;
        }
        Set<UUID> demands = endpointIds != null ? Set.copyOf(endpointIds) : Set.of();
        Entry<T> updated = entries.computeIfPresent(sourceId, (ignored, current) -> {
            if (!current.sessionId().equals(sessionId)) {
                return current;
            }
            long idleSince = demands.isEmpty()
                    ? (current.endpointIds().isEmpty() ? current.idleSinceMillis() : nowMillis)
                    : -1L;
            return current.withDemand(demands, idleSince);
        });
        return updated != null && updated.sessionId().equals(sessionId) && !updated.endpointIds().isEmpty();
    }

    public Optional<T> claimStart(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
        if (sourceId == null || sessionId == null) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Entry<T>[] claimed = (Entry<T>[]) new Entry<?>[1];
        entries.computeIfPresent(sourceId, (ignored, current) -> {
            if (!current.sessionId().equals(sessionId) || current.state() != State.METADATA
                    || current.endpointIds().isEmpty()) {
                return current;
            }
            claimed[0] = current;
            return current.withState(State.STARTING);
        });
        return claimed[0] != null ? Optional.of(claimed[0].payload()) : Optional.empty();
    }

    public boolean markPlaying(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
        return transition(sourceId, sessionId, State.STARTING, State.PLAYING);
    }

    public boolean claimStopAfterIdle(PlaybackSourceId sourceId, PlaybackSessionId sessionId,
            long nowMillis, long idleGraceMillis) {
        if (sourceId == null || sessionId == null) {
            return false;
        }
        boolean[] claimed = new boolean[1];
        entries.computeIfPresent(sourceId, (ignored, current) -> {
            if (!current.sessionId().equals(sessionId) || current.state() == State.METADATA
                    || !current.endpointIds().isEmpty() || current.idleSinceMillis() < 0L
                    || nowMillis - current.idleSinceMillis() < Math.max(0L, idleGraceMillis)) {
                return current;
            }
            claimed[0] = true;
            return current.withState(State.METADATA);
        });
        return claimed[0];
    }

    public Optional<Snapshot<T>> snapshot(PlaybackSourceId sourceId) {
        Entry<T> entry = sourceId != null ? entries.get(sourceId) : null;
        return entry != null ? Optional.of(entry.snapshot()) : Optional.empty();
    }

    public List<SourceSnapshot<T>> snapshots() {
        return entries.entrySet().stream()
                .map(entry -> new SourceSnapshot<>(entry.getKey(), entry.getValue().snapshot()))
                .toList();
    }

    public void remove(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
        if (sourceId != null && sessionId != null) {
            entries.computeIfPresent(sourceId,
                    (ignored, current) -> current.sessionId().equals(sessionId) ? null : current);
        }
    }

    public void clear() {
        entries.clear();
    }

    private boolean transition(PlaybackSourceId sourceId, PlaybackSessionId sessionId, State from, State to) {
        if (sourceId == null || sessionId == null) {
            return false;
        }
        boolean[] transitioned = new boolean[1];
        entries.computeIfPresent(sourceId, (ignored, current) -> {
            if (!current.sessionId().equals(sessionId) || current.state() != from) {
                return current;
            }
            transitioned[0] = true;
            return current.withState(to);
        });
        return transitioned[0];
    }

    public enum State {
        METADATA,
        STARTING,
        PLAYING
    }

    public record Snapshot<T>(PlaybackSessionId sessionId, T payload, State state,
            Set<UUID> endpointIds, long idleSinceMillis) {
    }

    public record SourceSnapshot<T>(PlaybackSourceId sourceId, Snapshot<T> playback) {
    }

    private record Entry<T>(PlaybackSessionId sessionId, T payload, State state,
            Set<UUID> endpointIds, long idleSinceMillis) {
        Entry<T> withPayload(T value) {
            return new Entry<>(sessionId, value, state, endpointIds, idleSinceMillis);
        }

        Entry<T> withDemand(Set<UUID> value, long idleSince) {
            return new Entry<>(sessionId, payload, state, value, idleSince);
        }

        Entry<T> withState(State value) {
            return new Entry<>(sessionId, payload, value, endpointIds, idleSinceMillis);
        }

        Snapshot<T> snapshot() {
            return new Snapshot<>(sessionId, payload, state, endpointIds, idleSinceMillis);
        }
    }
}
