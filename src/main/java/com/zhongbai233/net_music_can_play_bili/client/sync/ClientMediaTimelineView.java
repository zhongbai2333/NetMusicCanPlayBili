package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;

import java.util.UUID;

/** 客户端媒体时间线只读视图，统一本地时钟、OpenAL 可听进度和包同步兜底。 */
public final class ClientMediaTimelineView {
    private final String sessionId;
    private final long mediaMillis;
    private final long visualMillis;
    private final long pacingMillis;
    private final long serverMillis;
    private final long totalMillis;
    private final long mediaDriftMillis;
    private final boolean started;
    private final boolean audibleAnchored;

    private ClientMediaTimelineView(String sessionId, long mediaMillis, long visualMillis, long pacingMillis,
            long serverMillis, long totalMillis, long mediaDriftMillis, boolean started, boolean audibleAnchored) {
        this.sessionId = sessionId != null ? sessionId : "";
        this.mediaMillis = mediaMillis;
        this.visualMillis = visualMillis;
        this.pacingMillis = pacingMillis;
        this.serverMillis = serverMillis;
        this.totalMillis = Math.max(0L, totalMillis);
        this.mediaDriftMillis = mediaDriftMillis;
        this.started = started;
        this.audibleAnchored = audibleAnchored;
    }

    public static ClientMediaTimelineView empty() {
        return new ClientMediaTimelineView("", -1L, -1L, -1L, -1L, 0L, 0L, false, false);
    }

    public static ClientMediaTimelineView forMediaOwner(UUID ownerId, String expectedSessionId, long fallbackMillis,
            long fallbackTotalMillis) {
        String expected = expectedSessionId != null ? expectedSessionId : "";
        ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(ownerId);
        MediaTimelineClock.TimelineSnapshot snapshot = active != null
                ? active.timelineSnapshot()
                : MediaTimelineClock.TimelineSnapshot.EMPTY;
        return forHandheldOwner(ownerId, expected, snapshot,
                ClientMediaPlaybackRegistry.hasAudioStarted(ownerId, expected),
                fallbackMillis, fallbackTotalMillis);
    }

    public static ClientMediaTimelineView forHandheldOwner(UUID ownerId, HandheldMediaPlayback playback,
            boolean started, long fallbackMillis, long fallbackTotalMillis) {
        String expected = playback != null ? playback.sessionId() : "";
        MediaTimelineClock.TimelineSnapshot snapshot = playback != null && playback.timeline() != null
                ? playback.timeline()
                : MediaTimelineClock.TimelineSnapshot.EMPTY;
        return forHandheldOwner(ownerId, expected, snapshot, started, fallbackMillis, fallbackTotalMillis);
    }

    public static ClientMediaTimelineView forHandheldOwner(UUID ownerId, HandheldMediaDeviceProfile profile,
            long fallbackMillis, long fallbackTotalMillis) {
        if (profile == null) {
            return empty();
        }
        HandheldMediaPlayback playback = profile.playback(ownerId);
        String sessionId = playback != null ? playback.sessionId() : "";
        return forHandheldOwner(ownerId, playback, profile.hasStartedSound(ownerId, sessionId), fallbackMillis,
                fallbackTotalMillis);
    }

    private static ClientMediaTimelineView forHandheldOwner(UUID ownerId, String expected,
            MediaTimelineClock.TimelineSnapshot snapshot, boolean started, long fallbackMillis,
            long fallbackTotalMillis) {
        long fallback = Math.max(0L, fallbackMillis);
        long fallbackTotal = Math.max(0L, fallbackTotalMillis);
        if (expected.isBlank() || !expected.equals(snapshot.sessionId()) || snapshot.mediaMillis() < 0L) {
            return new ClientMediaTimelineView(expected, fallback, fallback, fallback, -1L, fallbackTotal, 0L,
                    false, false);
        }
        long total = snapshot.totalMillis() > 0L ? snapshot.totalMillis() : fallbackTotal;
        long media = started ? snapshot.mediaMillis() : fallback;
        long visual = started ? snapshot.visualMillis() : fallback;
        long pacing = started ? snapshot.pacingMillis() : fallback;
        boolean anchored = false;
        if (started) {
            ClientAudioOutputRegistry.AudioTimeline audioTimeline = ClientAudioOutputRegistry
                    .getOwnerAudioTimeline(ownerId);
            long audibleMillis = matchingAudibleMillis(audioTimeline, expected, total);
            if (audibleMillis >= 0L) {
                media = audibleMillis;
                visual = audibleMillis;
                pacing = audibleMillis;
                anchored = true;
            }
        }
        media = clamp(media, total);
        visual = clamp(visual, total);
        pacing = clamp(pacing, total);
        return new ClientMediaTimelineView(expected, media, visual, pacing, snapshot.serverMillis(), total,
                media - snapshot.serverMillis(), started, anchored);
    }

    public String sessionId() {
        return sessionId;
    }

    public long mediaMillis() {
        return mediaMillis;
    }

    public long visualMillis() {
        return visualMillis;
    }

    public long pacingMillis() {
        return pacingMillis;
    }

    public long serverMillis() {
        return serverMillis;
    }

    public long totalMillis() {
        return totalMillis;
    }

    public long mediaDriftMillis() {
        return mediaDriftMillis;
    }

    public boolean started() {
        return started;
    }

    public boolean audibleAnchored() {
        return audibleAnchored;
    }

    public boolean hasTimeline() {
        return mediaMillis >= 0L;
    }

    public float progressOr(float fallbackProgress) {
        if (totalMillis <= 0L || mediaMillis < 0L) {
            return clamp01(fallbackProgress);
        }
        return clamp01(mediaMillis / (float) totalMillis);
    }

    public long relativeNanos(long absoluteStartMillis) {
        if (mediaMillis < 0L) {
            return -1L;
        }
        return Math.max(0L, mediaMillis - Math.max(0L, absoluteStartMillis)) * 1_000_000L;
    }

    private static long matchingAudibleMillis(ClientAudioOutputRegistry.AudioTimeline audioTimeline, String sessionId,
            long totalMillis) {
        if (audioTimeline == null) {
            return -1L;
        }
        long audibleMillis = audioTimeline.audibleMillis();
        if (audibleMillis < 0L) {
            return -1L;
        }
        String audioSessionId = audioTimeline.sessionId();
        if (audioSessionId != null && !audioSessionId.isBlank() && !audioSessionId.equals(sessionId)) {
            return -1L;
        }
        return clamp(audibleMillis, totalMillis);
    }

    private static long clamp(long millis, long totalMillis) {
        long value = Math.max(0L, millis);
        long total = Math.max(0L, totalMillis);
        return total > 0L ? Math.min(total, value) : value;
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
