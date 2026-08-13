package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Shared client-side media playback registry for synchronized handheld/surface
 * media devices.
 *
 * <p>
 * This is intentionally protocol-neutral at the call-site level even though it
 * still accepts the existing
 * MP4 playback sync packet while the server sync layer is being extracted. MP4
 * and Pad now share the same active
 * playback state and audio-start tracking here instead of each renderer/profile
 * reaching into MP4-specific maps.
 * </p>
 */
public final class ClientMediaPlaybackRegistry {
    private static final Map<PlaybackSourceId, ActivePlayback> ACTIVE = new ConcurrentHashMap<>();
    private static final Set<SourceSessionKey> STARTED_AUDIO_SESSIONS = ConcurrentHashMap.newKeySet();

    private ClientMediaPlaybackRegistry() {
    }

    public static ActivePlayback get(UUID sourceId) {
        return sourceId != null ? ACTIVE.get(PlaybackSourceId.of(sourceId)) : null;
    }

    public static void put(UUID sourceId, ActivePlayback playback) {
        if (sourceId != null && playback != null) {
            ACTIVE.put(PlaybackSourceId.of(sourceId), playback);
        }
    }

    public static boolean contains(UUID sourceId) {
        return sourceId != null && ACTIVE.containsKey(PlaybackSourceId.of(sourceId));
    }

    public static void computeIfPresent(UUID sourceId, BiFunction<UUID, ActivePlayback, ActivePlayback> remapper) {
        if (sourceId != null && remapper != null) {
            ACTIVE.computeIfPresent(PlaybackSourceId.of(sourceId),
                    (key, active) -> remapper.apply(key.value(), active));
        }
    }

    public static void remove(UUID sourceId) {
        if (sourceId != null) {
            ACTIVE.remove(PlaybackSourceId.of(sourceId));
            removeAudioStartedForSource(sourceId);
            ClientMediaTimelineView.forget(sourceId);
        }
    }

    public static void finish(UUID sourceId, String sessionId) {
        PlaybackSessionId.parse(sessionId).ifPresent(parsedSessionId -> finish(sourceId, parsedSessionId));
    }

    public static void finish(UUID sourceId, PlaybackSessionId sessionId) {
        if (sourceId == null || sessionId == null) {
            return;
        }
        PlaybackSourceId parsedSourceId = PlaybackSourceId.of(sourceId);
        ACTIVE.computeIfPresent(parsedSourceId,
                (ignored, active) -> active.playbackSessionId().filter(sessionId::equals).isPresent()
                        ? null
                        : active);
        STARTED_AUDIO_SESSIONS.remove(new SourceSessionKey(parsedSourceId, sessionId));
        ClientMediaTimelineView.forget(sourceId);
    }

    public static void finishSession(UUID sourceId, String sessionId) {
        if (sourceId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        PlaybackSessionId.parse(sessionId).ifPresent(parsedSessionId -> finishSession(sourceId, parsedSessionId));
    }

    public static void finishSession(UUID sourceId, PlaybackSessionId sessionId) {
        if (sourceId == null || sessionId == null) {
            return;
        }
        finish(sourceId, sessionId);
        ClientMediaSoundRegistry.finishAndDiscard(sourceId, sessionId);
    }

    public static void updateLyric(UUID sourceId, String sessionId, LyricRecord record, int lyricTick) {
        if (sourceId == null || record == null || !isCurrent(sourceId, sessionId)) {
            return;
        }
        String current = currentLineAt(record.getLyrics(), lyricTick);
        String translated = currentLineAt(record.getTransLyrics(), lyricTick);
        ACTIVE.computeIfPresent(PlaybackSourceId.of(sourceId),
                (ignored, active) -> active.withLyrics(record, current, translated));
    }

    public static void clear() {
        ACTIVE.clear();
        STARTED_AUDIO_SESSIONS.clear();
        ClientMediaTimelineView.clearVisualStates();
    }

    public static boolean isCurrent(UUID sourceId, String sessionId) {
        if (sourceId == null || sessionId == null || sessionId.isBlank()) {
            return true;
        }
        return PlaybackSessionId.parse(sessionId).map(parsedSessionId -> isCurrent(sourceId, parsedSessionId))
                .orElse(false);
    }

    public static boolean isCurrent(UUID sourceId, PlaybackSessionId sessionId) {
        if (sourceId == null || sessionId == null) {
            return false;
        }
        ActivePlayback active = ACTIVE.get(PlaybackSourceId.of(sourceId));
        return active != null && active.playbackSessionId().filter(sessionId::equals).isPresent();
    }

    public static boolean markAudioStarted(UUID sourceId, String sessionId, long startOffsetMillis, long totalMillis) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (sourceId == null || parsedSessionId == null) {
            return false;
        }
        PlaybackSourceId parsedSourceId = PlaybackSourceId.of(sourceId);
        ActivePlayback current = ACTIVE.get(parsedSourceId);
        if (current == null || !current.playbackSessionId().filter(parsedSessionId::equals).isPresent()) {
            return false;
        }
        if (!STARTED_AUDIO_SESSIONS.add(new SourceSessionKey(parsedSourceId, parsedSessionId))) {
            return false;
        }
        ACTIVE.computeIfPresent(parsedSourceId, (ignored, active) -> active.reanchoredAtSoundStart(
                Math.max(0L, startOffsetMillis), Math.max(0L, totalMillis)));
        return true;
    }

    public static boolean hasAudioStarted(UUID sourceId, String sessionId) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        return sourceId != null && parsedSessionId != null
                && STARTED_AUDIO_SESSIONS.contains(
                        new SourceSessionKey(PlaybackSourceId.of(sourceId), parsedSessionId));
    }

    public static void removeAudioStartedForSource(UUID sourceId) {
        if (sourceId != null) {
            PlaybackSourceId parsedSourceId = PlaybackSourceId.of(sourceId);
            STARTED_AUDIO_SESSIONS.removeIf(key -> key.sourceId().equals(parsedSourceId));
        }
    }

    public static ActivePlayback createFromSync(ClientMediaSyncPayload payload) {
        Optional<PlaybackSessionId> playbackSessionId = payload.playbackSessionId();
        return new ActivePlayback(playbackSessionId, payload.queueIndex(), payload.songName(), payload.rawUrl(),
                MediaTimelineClock.start(playbackSessionId, Math.max(0L, payload.elapsedMillis()),
                        Math.max(0L, payload.durationSeconds()) * 1000L),
                null, "", "", payload.volumePerMille() / 1000.0F, SourceLocation.from(payload),
                payload.headphoneRouted());
    }

    private static String currentLineAt(it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap<String> lyrics, int tick) {
        if (lyrics == null || lyrics.isEmpty()) {
            return "";
        }
        int key = lyrics.firstIntKey();
        for (int candidate : lyrics.keySet().toIntArray()) {
            if (candidate > tick) {
                break;
            }
            key = candidate;
        }
        String line = lyrics.get(key);
        return line != null ? line : "";
    }

    private record SourceSessionKey(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
        private SourceSessionKey {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    public record ActivePlayback(Optional<PlaybackSessionId> playbackSessionId, int queueIndex, String songName,
            String rawUrl,
            MediaTimelineClock timeline,
            LyricRecord lyricRecord, String currentLyric,
            String translatedLyric, float volume, SourceLocation sourceLocation, boolean headphoneRouted) {
        public ActivePlayback {
            Objects.requireNonNull(playbackSessionId, "playbackSessionId");
        }

        public ActivePlayback(String sessionId, int queueIndex, String songName, String rawUrl,
                MediaTimelineClock timeline, LyricRecord lyricRecord, String currentLyric,
                String translatedLyric, float volume, SourceLocation sourceLocation, boolean headphoneRouted) {
            this(PlaybackSessionId.parse(sessionId), queueIndex, songName, rawUrl, timeline, lyricRecord,
                    currentLyric, translatedLyric, volume, sourceLocation, headphoneRouted);
        }

        public String sessionId() {
            return playbackSessionId.map(PlaybackSessionId::value).orElse("");
        }

        public long elapsedMillis() {
            return timeline.mediaMillis();
        }

        public long visualMillis() {
            return timeline.visualMillis();
        }

        public long pacingMillis() {
            return timeline.pacingMillis();
        }

        public long durationMillis() {
            return timeline.totalMillis();
        }

        public MediaTimelineClock.TimelineSnapshot timelineSnapshot() {
            return timeline.snapshot();
        }

        public ActivePlayback withLyrics(LyricRecord record, String current, String translated) {
            return new ActivePlayback(playbackSessionId, queueIndex, songName, rawUrl, timeline, record,
                    current != null ? current : "",
                    translated != null ? translated : "", volume, sourceLocation, headphoneRouted);
        }

        public String lyricLineAtCurrentTime(boolean translated) {
            if (lyricRecord == null) {
                return translated ? translatedLyric : currentLyric;
            }
            long mediaMillis = timeline.mediaMillis();
            if (mediaMillis < 0L) {
                return "";
            }
            int lyricTick = (int) Math.min(Integer.MAX_VALUE, mediaMillis / 50L);
            return currentLineAt(translated ? lyricRecord.getTransLyrics() : lyricRecord.getLyrics(), lyricTick);
        }

        public ActivePlayback withVolume(float newVolume) {
            return new ActivePlayback(playbackSessionId, queueIndex, songName, rawUrl, timeline, lyricRecord,
                    currentLyric, translatedLyric, newVolume, sourceLocation, headphoneRouted);
        }

        public ActivePlayback withServerElapsed(long serverElapsedMillis, long serverDurationMillis) {
            timeline.observeServer(serverElapsedMillis,
                    serverDurationMillis > 0L ? serverDurationMillis : timeline.totalMillis());
            return this;
        }

        public ActivePlayback reanchoredAtSoundStart(long startOffsetMillis, long totalMillis) {
            timeline.reanchor(startOffsetMillis, totalMillis > 0L ? totalMillis : timeline.totalMillis());
            return this;
        }

        public ActivePlayback withSourceLocation(SourceLocation newSourceLocation) {
            return new ActivePlayback(playbackSessionId, queueIndex, songName, rawUrl, timeline, lyricRecord,
                    currentLyric, translatedLyric, volume, newSourceLocation, headphoneRouted);
        }

        public ActivePlayback withHeadphoneRouted(boolean routed) {
            return new ActivePlayback(playbackSessionId, queueIndex, songName, rawUrl, timeline, lyricRecord,
                    currentLyric, translatedLyric, volume, sourceLocation, routed);
        }
    }

    public record SourceLocation(int sourceType, int sourceEntityId, double x, double y, double z) {
        public static SourceLocation from(ClientMediaSyncPayload payload) {
            return new SourceLocation(payload.sourceType(), payload.sourceEntityId(), payload.sourceX(),
                    payload.sourceY(), payload.sourceZ());
        }

        public Vec3 position() {
            Minecraft minecraft = Minecraft.getInstance();
            if (sourceType == ClientMediaSyncPayload.SOURCE_PLAYER && minecraft.level != null) {
                Entity entity = minecraft.level.getEntity(sourceEntityId);
                if (entity != null) {
                    return entity.position().add(0.0D, 1.2D, 0.0D);
                }
            }
            if (sourceType == ClientMediaSyncPayload.SOURCE_ITEM && minecraft.level != null) {
                Entity entity = minecraft.level.getEntity(sourceEntityId);
                if (entity != null) {
                    return entity.position().add(0.0D, 0.25D, 0.0D);
                }
            }
            if (sourceType == ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY && minecraft.level != null) {
                Entity entity = minecraft.level.getEntity(sourceEntityId);
                if (entity != null) {
                    return entity.position().add(0.0D, 0.5D, 0.0D);
                }
            }
            return new Vec3(x, y, z);
        }
    }
}
