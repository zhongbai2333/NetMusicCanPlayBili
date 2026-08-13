package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Legacy singleton 预览管线的会话身份、请求与投影仪绑定快照。
 *
 * <p>状态始终以单个不可变快照发布，避免 start/replace/detach 与渲染线程分别观察到
 * 不同换代中的 session、primary projector 和 projector 集合。</p>
 */
final class LegacyPreviewSessionState<T, R> {
    private volatile Snapshot<T, R> snapshot = Snapshot.empty();

    synchronized void begin(String sessionId, Collection<? extends T> projectors, R request) {
        List<T> positions = immutableDistinct(projectors);
        Optional<PlaybackSessionId> parsedSessionId = PlaybackSessionId.parse(sessionId);
        snapshot = new Snapshot<>(parsedSessionId, positions, first(positions),
                parsedSessionId.isPresent() && !positions.isEmpty(), request);
    }

    synchronized void replaceProjectors(Collection<? extends T> projectors) {
        Snapshot<T, R> current = snapshot;
        List<T> positions = immutableDistinct(projectors);
        snapshot = new Snapshot<>(current.playbackSessionId(), positions, first(positions),
                current.playbackSessionId().isPresent() && !positions.isEmpty(), current.request());
    }

    synchronized void detachProjector(T projector) {
        if (projector == null) {
            return;
        }
        Snapshot<T, R> current = snapshot;
        List<T> positions = current.projectors().stream()
                .filter(candidate -> !candidate.equals(projector))
                .toList();
        if (positions.size() == current.projectors().size()) {
            return;
        }
        T primary = projector.equals(current.primaryProjector()) ? first(positions) : current.primaryProjector();
        snapshot = new Snapshot<>(current.playbackSessionId(), positions, primary,
                current.playbackSessionId().isPresent() && !positions.isEmpty(), current.request());
    }

    synchronized void removeProjectorsIf(Predicate<? super T> predicate) {
        if (predicate == null) {
            return;
        }
        Snapshot<T, R> current = snapshot;
        List<T> positions = current.projectors().stream().filter(predicate.negate()).toList();
        if (positions.size() == current.projectors().size()) {
            return;
        }
        T primary = positions.contains(current.primaryProjector()) ? current.primaryProjector() : first(positions);
        snapshot = new Snapshot<>(current.playbackSessionId(), positions, primary,
                current.playbackSessionId().isPresent() && !positions.isEmpty(), current.request());
    }

    synchronized void setPrimaryProjector(T projector) {
        Snapshot<T, R> current = snapshot;
        snapshot = new Snapshot<>(current.playbackSessionId(), current.projectors(), projector,
                current.requiresProjector(), current.request());
    }

    synchronized void clearForReplacement() {
        Snapshot<T, R> current = snapshot;
        snapshot = new Snapshot<>(current.playbackSessionId(), List.of(), null, false, current.request());
    }

    synchronized void clear() {
        snapshot = Snapshot.empty();
    }

    Snapshot<T, R> snapshot() {
        return snapshot;
    }

    String sessionId() {
        return snapshot.sessionId();
    }

    List<T> projectors() {
        return snapshot.projectors();
    }

    T primaryProjector() {
        return snapshot.primaryProjector();
    }

    boolean requiresProjector() {
        return snapshot.requiresProjector();
    }

    R request() {
        return snapshot.request();
    }

    boolean matchesSession(String sessionId) {
        return snapshot.playbackSessionId().equals(PlaybackSessionId.parse(sessionId));
    }

    private static <T> List<T> immutableDistinct(Collection<? extends T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<T> distinct = new LinkedHashSet<>();
        for (T value : values) {
            if (value != null) {
                distinct.add(value);
            }
        }
        return List.copyOf(distinct);
    }

    private static <T> T first(List<T> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    record Snapshot<T, R>(Optional<PlaybackSessionId> playbackSessionId, List<T> projectors, T primaryProjector,
            boolean requiresProjector, R request) {
        Snapshot {
            playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
        }

        Snapshot(String sessionId, List<T> projectors, T primaryProjector, boolean requiresProjector, R request) {
            this(PlaybackSessionId.parse(sessionId), projectors, primaryProjector, requiresProjector, request);
        }

        String sessionId() {
            return playbackSessionId.map(PlaybackSessionId::value).orElse("");
        }

        private static <T, R> Snapshot<T, R> empty() {
            return new Snapshot<>(Optional.empty(), List.of(), null, false, null);
        }
    }
}
