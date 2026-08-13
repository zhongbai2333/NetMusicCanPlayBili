package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Exact generation ownership for Pad start/seek/restart and transport retry resolves. */
final class PadResolveIntentRegistry {
    private final ConcurrentMap<PlaybackSourceId, Intent> active = new ConcurrentHashMap<>();
    private final AtomicReference<ResolveGeneration> generations =
            new AtomicReference<>(ResolveGeneration.initial());

    synchronized Intent replaceCommand(UUID ownerId, UUID deviceId, UUID pointId, int mediaId, String sourceUrl) {
        Intent replacement = new Intent(nextGeneration(), ownerId, pointId, mediaId, normalize(sourceUrl), null);
        active.put(PlaybackSourceId.of(deviceId), replacement);
        return replacement;
    }

    synchronized Intent beginRetryIfIdle(UUID ownerId, UUID deviceId, UUID pointId, int mediaId, String sourceUrl,
            PlaybackSessionId expectedSessionId) {
        if (ownerId == null || deviceId == null || pointId == null || expectedSessionId == null
                || !PadPlaybackSessionIds.matches(expectedSessionId.value(), deviceId, pointId)) {
            return null;
        }
        PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
        Intent[] started = new Intent[1];
        active.compute(sourceId, (ignored, current) -> {
            if (current != null) {
                return current;
            }
            Intent intent = new Intent(nextGeneration(), ownerId, pointId, mediaId, normalize(sourceUrl),
                    expectedSessionId);
            started[0] = intent;
            return intent;
        });
        return started[0];
    }

    synchronized boolean isCurrent(UUID deviceId, Intent intent) {
        return deviceId != null && intent != null && active.get(PlaybackSourceId.of(deviceId)) == intent;
    }

    synchronized void complete(UUID deviceId, Intent intent) {
        if (deviceId != null && intent != null) {
            active.remove(PlaybackSourceId.of(deviceId), intent);
        }
    }

    synchronized void invalidate(UUID deviceId) {
        if (deviceId != null) {
            active.remove(PlaybackSourceId.of(deviceId));
        }
    }

    synchronized void invalidateOwner(UUID ownerId) {
        if (ownerId != null) {
            active.entrySet().removeIf(entry -> ownerId.equals(entry.getValue().ownerId()));
        }
    }

    synchronized void clear() {
        active.clear();
    }

    /** Serializes final commit against command replacement and stop invalidation. */
    synchronized boolean commitIfCurrent(UUID deviceId, Intent intent, BooleanSupplier commit) {
        if (!isCurrent(deviceId, intent) || commit == null) {
            return false;
        }
        try {
            return commit.getAsBoolean();
        } finally {
            active.remove(PlaybackSourceId.of(deviceId), intent);
        }
    }

    private ResolveGeneration nextGeneration() {
        return generations.updateAndGet(ResolveGeneration::next);
    }

    private static String normalize(String value) {
        return value != null ? value : "";
    }

    record Intent(ResolveGeneration generation, UUID ownerId, UUID pointId, int mediaId, String sourceUrl,
            PlaybackSessionId expectedSessionId) {
        Intent {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(pointId, "pointId");
            sourceUrl = normalize(sourceUrl);
        }

        boolean retry() {
            return expectedSessionId != null;
        }
    }
}
