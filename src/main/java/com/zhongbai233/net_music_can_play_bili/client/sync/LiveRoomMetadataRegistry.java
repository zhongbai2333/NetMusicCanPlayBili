package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** One metadata snapshot per live source/session; subtitle elements share this owner. */
public final class LiveRoomMetadataRegistry {
    private static final ConcurrentHashMap<SourceKey, Snapshot> ACTIVE = new ConcurrentHashMap<>();

    private LiveRoomMetadataRegistry() {
    }

    public static void publish(SourceKey source, PlaybackSessionId sessionId, String roomId, String title,
            String parentAreaName, String areaName, int liveStatus) {
        ACTIVE.put(Objects.requireNonNull(source, "source"), new Snapshot(sessionId, roomId, title,
                parentAreaName, areaName, liveStatus));
    }

    public static Optional<Snapshot> snapshot(SourceKey source, String expectedRoomId) {
        if (source == null) {
            return Optional.empty();
        }
        Snapshot snapshot = ACTIVE.get(source);
        String expected = normalize(expectedRoomId, 32);
        return snapshot != null && (expected.isEmpty() || expected.equals(snapshot.roomId()))
                ? Optional.of(snapshot) : Optional.empty();
    }

    public static boolean remove(SourceKey source, PlaybackSessionId expectedSessionId) {
        if (source == null || expectedSessionId == null) {
            return false;
        }
        Snapshot current = ACTIVE.get(source);
        return current != null && current.sessionId().equals(expectedSessionId) && ACTIVE.remove(source, current);
    }

    public static void clear() {
        ACTIVE.clear();
    }

    public static int size() {
        return ACTIVE.size();
    }

    public record SourceKey(int x, int y, int z) {
    }

    public record Snapshot(PlaybackSessionId sessionId, String roomId, String title, String parentAreaName,
            String areaName, int liveStatus) {
        public Snapshot {
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            roomId = normalize(roomId, 32);
            title = normalize(title, 256);
            parentAreaName = normalize(parentAreaName, 64);
            areaName = normalize(areaName, 64);
        }
    }

    private static String normalize(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
