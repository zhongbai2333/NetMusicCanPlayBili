package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/** 每台媒体设备异步解析请求的运行期所有权与换代管理。 */
final class MP4ResolveIntentRegistry {
    private final ConcurrentMap<PlaybackSourceId, Intent> active = new ConcurrentHashMap<>();
    private final AtomicReference<ResolveGeneration> generations =
            new AtomicReference<>(ResolveGeneration.initial());

    Intent begin(UUID deviceId, int queueIndex, String sourceUrl) {
        PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
        String normalizedSource = sourceUrl != null ? sourceUrl : "";
        Intent[] started = new Intent[1];
        active.compute(sourceId, (ignored, current) -> {
            if (current != null && current.matches(queueIndex, normalizedSource)) {
                return current;
            }
            Intent replacement = new Intent(nextGeneration(), queueIndex, normalizedSource);
            started[0] = replacement;
            return replacement;
        });
        return started[0];
    }

    Intent replace(UUID deviceId, int queueIndex, String sourceUrl) {
        PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
        Intent replacement = new Intent(nextGeneration(), queueIndex,
                sourceUrl != null ? sourceUrl : "");
        active.put(sourceId, replacement);
        return replacement;
    }

    /** Starts a lower-priority refresh only when no command/discovery resolve owns the device. */
    Intent beginIfIdle(UUID deviceId, int queueIndex, String sourceUrl) {
        PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
        String normalizedSource = sourceUrl != null ? sourceUrl : "";
        Intent[] started = new Intent[1];
        active.compute(sourceId, (ignored, current) -> {
            if (current != null) {
                return current;
            }
            Intent intent = new Intent(nextGeneration(), queueIndex, normalizedSource);
            started[0] = intent;
            return intent;
        });
        return started[0];
    }

    boolean isCurrent(UUID deviceId, Intent intent) {
        return deviceId != null && intent != null && active.get(PlaybackSourceId.of(deviceId)) == intent;
    }

    void complete(UUID deviceId, Intent intent) {
        if (deviceId != null && intent != null) {
            active.remove(PlaybackSourceId.of(deviceId), intent);
        }
    }

    void invalidate(UUID deviceId) {
        if (deviceId != null) {
            active.remove(PlaybackSourceId.of(deviceId));
        }
    }

    void clear() {
        active.clear();
    }

    private ResolveGeneration nextGeneration() {
        return generations.updateAndGet(
                current -> Objects.requireNonNull(current, "current generation").next());
    }

    record Intent(ResolveGeneration generation, int queueIndex, String sourceUrl) {
        Intent {
            Objects.requireNonNull(generation, "generation");
        }

        private boolean matches(int candidateQueueIndex, String candidateSourceUrl) {
            return queueIndex == candidateQueueIndex && sourceUrl.equals(candidateSourceUrl);
        }
    }
}
