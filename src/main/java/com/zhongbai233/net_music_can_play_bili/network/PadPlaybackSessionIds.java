package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.UUID;

/** Safe creation and parsing for Pad logical playback session identities. */
public final class PadPlaybackSessionIds {
    private static final String MARKER = "-pad-";
    private static final int UUID_LENGTH = 36;
    private static final int POINT_START = UUID_LENGTH + MARKER.length();
    private static final int GENERATION_SEPARATOR = POINT_START + UUID_LENGTH;

    private PadPlaybackSessionIds() {
    }

    public static PlaybackSessionId create(UUID deviceId, UUID pointId, long generation) {
        if (deviceId == null || pointId == null) {
            throw new IllegalArgumentException("Pad playback session requires device and point ids");
        }
        return PlaybackSessionId.of(deviceId + MARKER + pointId + "-" + Math.max(0L, generation));
    }

    public static boolean isPadSession(String sessionId) {
        return pointId(sessionId) != null;
    }

    public static UUID pointId(String sessionId) {
        if (!hasValidShape(sessionId)) {
            return null;
        }
        try {
            UUID.fromString(sessionId.substring(0, UUID_LENGTH));
            UUID pointId = UUID.fromString(sessionId.substring(POINT_START, GENERATION_SEPARATOR));
            long generation = Long.parseLong(sessionId.substring(GENERATION_SEPARATOR + 1));
            return generation >= 0L ? pointId : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static boolean matches(String sessionId, UUID deviceId, UUID pointId) {
        if (sessionId == null || deviceId == null || pointId == null
                || !sessionId.startsWith(deviceId + MARKER)) {
            return false;
        }
        return pointId.equals(pointId(sessionId));
    }

    private static boolean hasValidShape(String sessionId) {
        return sessionId != null
                && sessionId.length() > GENERATION_SEPARATOR + 1
                && sessionId.startsWith(MARKER, UUID_LENGTH)
                && sessionId.charAt(GENERATION_SEPARATOR) == '-';
    }
}
