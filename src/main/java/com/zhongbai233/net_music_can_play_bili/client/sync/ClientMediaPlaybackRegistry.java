package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
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
    private static final Map<UUID, ActivePlayback> ACTIVE = new ConcurrentHashMap<>();
    private static final Set<String> STARTED_AUDIO_SESSIONS = ConcurrentHashMap.newKeySet();

    private ClientMediaPlaybackRegistry() {
    }

    public static ActivePlayback get(UUID sourceId) {
        return sourceId != null ? ACTIVE.get(sourceId) : null;
    }

    public static void put(UUID sourceId, ActivePlayback playback) {
        if (sourceId != null && playback != null) {
            ACTIVE.put(sourceId, playback);
        }
    }

    public static boolean contains(UUID sourceId) {
        return sourceId != null && ACTIVE.containsKey(sourceId);
    }

    public static void computeIfPresent(UUID sourceId, BiFunction<UUID, ActivePlayback, ActivePlayback> remapper) {
        if (sourceId != null && remapper != null) {
            ACTIVE.computeIfPresent(sourceId, remapper);
        }
    }

    public static void remove(UUID sourceId) {
        if (sourceId != null) {
            ACTIVE.remove(sourceId);
            removeAudioStartedForSource(sourceId);
        }
    }

    public static void finish(UUID sourceId, String sessionId) {
        if (sourceId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        ACTIVE.computeIfPresent(sourceId, (ignored, active) -> sessionId.equals(active.sessionId()) ? null : active);
        STARTED_AUDIO_SESSIONS.remove(sessionKey(sourceId, sessionId));
    }

    public static void clear() {
        ACTIVE.clear();
        STARTED_AUDIO_SESSIONS.clear();
    }

    public static boolean isCurrent(UUID sourceId, String sessionId) {
        if (sourceId == null || sessionId == null || sessionId.isBlank()) {
            return true;
        }
        ActivePlayback active = ACTIVE.get(sourceId);
        return active != null && sessionId.equals(active.sessionId());
    }

    public static boolean markAudioStarted(UUID sourceId, String sessionId, long startOffsetMillis, long totalMillis) {
        if (sourceId == null || sessionId == null || sessionId.isBlank() || !isCurrent(sourceId, sessionId)) {
            return false;
        }
        if (!STARTED_AUDIO_SESSIONS.add(sessionKey(sourceId, sessionId))) {
            return false;
        }
        ACTIVE.computeIfPresent(sourceId, (ignored, active) -> active.reanchoredAtSoundStart(
                Math.max(0L, startOffsetMillis), Math.max(0L, totalMillis)));
        return true;
    }

    public static boolean hasAudioStarted(UUID sourceId, String sessionId) {
        return sourceId != null && sessionId != null
                && STARTED_AUDIO_SESSIONS.contains(sessionKey(sourceId, sessionId));
    }

    public static void removeAudioStartedForSource(UUID sourceId) {
        if (sourceId != null) {
            STARTED_AUDIO_SESSIONS.removeIf(key -> key.startsWith(sourceId + ":"));
        }
    }

    public static ActivePlayback createFromSync(ClientMediaSyncPayload payload) {
        return new ActivePlayback(payload.sessionId(), payload.queueIndex(), payload.songName(), payload.rawUrl(),
                MediaTimelineClock.start(payload.sessionId(), Math.max(0L, payload.elapsedMillis()),
                        Math.max(0L, payload.durationSeconds()) * 1000L),
                null, "", "", payload.volumePerMille() / 1000.0F, SourceLocation.from(payload),
                payload.headphoneRouted());
    }

    private static String sessionKey(UUID sourceId, String sessionId) {
        return sourceId + ":" + sessionId;
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

    public record ActivePlayback(String sessionId, int queueIndex, String songName, String rawUrl,
            MediaTimelineClock timeline,
            LyricRecord lyricRecord, String currentLyric,
            String translatedLyric, float volume, SourceLocation sourceLocation, boolean headphoneRouted) {
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
            return new ActivePlayback(sessionId, queueIndex, songName, rawUrl, timeline, record,
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
            return new ActivePlayback(sessionId, queueIndex, songName, rawUrl, timeline, lyricRecord, currentLyric,
                    translatedLyric, newVolume, sourceLocation, headphoneRouted);
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
            return new ActivePlayback(sessionId, queueIndex, songName, rawUrl, timeline, lyricRecord, currentLyric,
                    translatedLyric, volume, newSourceLocation, headphoneRouted);
        }

        public ActivePlayback withHeadphoneRouted(boolean routed) {
            return new ActivePlayback(sessionId, queueIndex, songName, rawUrl, timeline, lyricRecord, currentLyric,
                    translatedLyric, volume, sourceLocation, routed);
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