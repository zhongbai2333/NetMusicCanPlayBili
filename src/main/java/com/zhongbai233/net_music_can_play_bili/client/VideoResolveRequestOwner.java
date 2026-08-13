package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns one asynchronous video resolve request independently from the sync facade.
 *
 * <p>The owner is deliberately small: request admission remains in
 * {@link ModernTurntableVideoClient}, while cancellation and late worker binding
 * are kept in one testable lifecycle object.</p>
 */
final class VideoResolveRequestOwner<T> {
    private final int qualityCeiling;
    private final ResolveGeneration requestGeneration;
    private final List<T> consumerPositions;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<CancellableTaskFuture<?>> task = new AtomicReference<>();

    VideoResolveRequestOwner(int qualityCeiling, ResolveGeneration requestGeneration, List<T> consumerPositions) {
        this.qualityCeiling = qualityCeiling;
        this.requestGeneration = Objects.requireNonNull(requestGeneration, "requestGeneration");
        this.consumerPositions = List.copyOf(consumerPositions);
    }

    boolean matches(long requestedElapsedMillis, int requestedQualityCeiling) {
        return qualityCeiling == requestedQualityCeiling;
    }

    ResolveGeneration requestGeneration() {
        return requestGeneration;
    }

    List<T> consumerPositions() {
        return consumerPositions;
    }

    void bind(CancellableTaskFuture<?> value) {
        if (value == null || !task.compareAndSet(null, value)) {
            if (value != null) {
                value.cancel(true);
            }
            return;
        }
        if (cancelled.get()) {
            value.cancel(true);
        }
    }

    void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        CancellableTaskFuture<?> value = task.get();
        if (value != null) {
            value.cancel(true);
        }
    }
}
