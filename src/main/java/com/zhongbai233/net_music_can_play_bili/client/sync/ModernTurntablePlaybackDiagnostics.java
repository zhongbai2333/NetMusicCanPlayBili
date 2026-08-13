package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 现代化唱片机播放管线的唯一周期性关键日志。
 */
public final class ModernTurntablePlaybackDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long LOG_INTERVAL_MILLIS = 3_000L;
    private static final PlaybackRuntimeProperties.Diagnostics DIAGNOSTICS =
            PlaybackRuntimeProperties.diagnostics();
    private static final ConcurrentHashMap<PlaybackSessionId, Long> LAST_LOG_MILLIS_BY_SESSION =
            new ConcurrentHashMap<>();

    private ModernTurntablePlaybackDiagnostics() {
    }

    public static void logEveryThreeSeconds(BlockPos turntablePos, String sessionId) {
        PlaybackSessionId playbackSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (turntablePos == null || playbackSessionId == null) {
            return;
        }
        String normalizedSessionId = playbackSessionId.value();
        long now = System.currentTimeMillis();
        Long last = LAST_LOG_MILLIS_BY_SESSION.get(playbackSessionId);
        if (last != null && now - last < LOG_INTERVAL_MILLIS) {
            return;
        }
        if (last != null && !LAST_LOG_MILLIS_BY_SESSION.replace(playbackSessionId, last, now)) {
            return;
        }
        if (last == null && LAST_LOG_MILLIS_BY_SESSION.putIfAbsent(playbackSessionId, now) != null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            finish(playbackSessionId);
            return;
        }
        if (!(minecraft.level.getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity turntable)
                || !turntable.isPlaying()) {
            finish(playbackSessionId);
            return;
        }

        ModernTurntableTimeline.TimelineSnapshot timeline = PlaybackClock.snapshot(turntablePos);
        long localMillis = timeline.mediaMillis();
        long serverMillis = timeline.serverMillis();

        VideoBillboardPreview.VideoSyncStatus video = VideoBillboardPreview.getSyncStatus(normalizedSessionId);
        long videoMillis = video.mediaMillis();
        ClientAudioOutputRegistry.AudioTimeline audio = ClientAudioOutputRegistry.getAudioTimeline(turntablePos);
        long audioMillis = audio.combinedMillis();
        long subtitleMillis = subtitleMillis(turntable);

        LOGGER.debug(
                "播放管线关键时间: media={} visual={} server={} pacing={} mediaDrift={} video={} expectedVideo={} videoQueued={} audio={} audioMain={} audioMainFed={} audioRelay={} audioRelayCount={}/{} expectedAudio={} subtitle={} expectedSubtitle={} driftVideo={} driftAudio={} driftAudioFed={} session={} audioSession={}",
                formatMillis(localMillis), formatMillis(timeline.visualMillis()), formatMillis(serverMillis),
                formatMillis(timeline.pacingMillis()),
                formatDelta(localMillis, serverMillis),
                formatMillis(videoMillis), formatMillis(localMillis), formatMillis(video.queuedMediaMillis()),
                formatMillis(audioMillis), formatMillis(audio.mainMillis()), formatMillis(audio.fedMillis()),
                formatMillis(audio.relayMillis()),
                audio.relayStartedCount(), audio.relayRegisteredCount(), formatMillis(localMillis),
                formatMillis(subtitleMillis), formatMillis(localMillis),
                formatDelta(videoMillis, localMillis), formatDelta(audioMillis, localMillis),
                formatDelta(audio.fedMillis(), localMillis), normalizedSessionId, audio.audioSessionId());
        debugIfAudioSessionMoved(normalizedSessionId, audio.audioSessionId(), turntablePos, audioMillis, localMillis);
        warnIfLargeDrift(normalizedSessionId, localMillis, serverMillis, videoMillis, video.queuedMediaMillis(),
                audioMillis, audio.fedMillis(), timeline.pacingMillis());
        debugIfPerceptibleAvDrift(normalizedSessionId, videoMillis, audioMillis, audio.fedMillis());
    }

    public static void finish(String sessionId) {
        PlaybackSessionId.parse(sessionId).ifPresent(ModernTurntablePlaybackDiagnostics::finish);
    }

    private static void finish(PlaybackSessionId sessionId) {
        LAST_LOG_MILLIS_BY_SESSION.remove(sessionId);
        ModernTurntableTimeline.forgetSession(sessionId.value());
    }

    private static long subtitleMillis(ModernTurntableBlockEntity turntable) {
        int lyricTick = turntable.getClientLyricTick();
        return lyricTick >= 0 ? lyricTick * 50L : -1L;
    }

    private static String formatMillis(long millis) {
        return millis >= 0L ? millis + "ms" : "n/a";
    }

    private static String formatDelta(long actual, long expected) {
        if (actual < 0L || expected < 0L) {
            return "n/a";
        }
        long delta = actual - expected;
        return (delta >= 0L ? "+" : "") + delta + "ms";
    }

    private static void warnIfLargeDrift(String sessionId, long localMillis, long serverMillis, long videoMillis,
            long videoQueuedMillis, long audioMillis, long audioFedMillis, long pacingMillis) {
        long threshold = DIAGNOSTICS.warnDriftMillis();
        if (threshold <= 0L) {
            return;
        }
        long videoDrift = driftOrZero(videoMillis, localMillis);
        long audioDrift = driftOrZero(audioMillis, localMillis);
        long serverDrift = driftOrZero(localMillis, serverMillis);
        if (Math.abs(videoDrift) < threshold && Math.abs(audioDrift) < threshold
                && Math.abs(serverDrift) < threshold) {
            return;
        }
        LOGGER.warn(
                "播放管线秒级漂移: session={} local={} server={} pacing={} video={} videoQueued={} audio={} audioFed={} driftVideo={} driftAudio={} driftAudioFed={} driftLocalServer={}",
                sessionId, formatMillis(localMillis), formatMillis(serverMillis), formatMillis(pacingMillis),
                formatMillis(videoMillis), formatMillis(videoQueuedMillis), formatMillis(audioMillis),
                formatMillis(audioFedMillis), formatDelta(videoMillis, localMillis),
                formatDelta(audioMillis, localMillis),
                formatDelta(audioFedMillis, localMillis),
                formatDelta(localMillis, serverMillis));
    }

    private static void debugIfPerceptibleAvDrift(String sessionId, long videoMillis, long audioMillis,
            long audioFedMillis) {
        long threshold = DIAGNOSTICS.debugAvDriftMillis();
        if (threshold <= 0L || videoMillis < 0L || audioMillis < 0L) {
            return;
        }
        long avDrift = audioMillis - videoMillis;
        if (Math.abs(avDrift) < threshold) {
            return;
        }
        LOGGER.debug(
                "播放管线可感知音画漂移: session={} video={} audio={} audioFed={} driftAudioVideo={} threshold={}ms",
                sessionId, formatMillis(videoMillis), formatMillis(audioMillis), formatMillis(audioFedMillis),
                formatDelta(audioMillis, videoMillis), threshold);
    }

    private static void debugIfAudioSessionMoved(String playbackSessionId, String audioSessionId, BlockPos turntablePos,
            long audioMillis, long expectedMillis) {
        if (playbackSessionId == null || playbackSessionId.isBlank()
                || audioSessionId == null || audioSessionId.isBlank()
                || playbackSessionId.equals(audioSessionId)) {
            return;
        }
        LOGGER.warn(
                "播放管线音频会话位移: pos={} playbackSession={} audioSession={} audio={} expected={} driftAudio={}",
                turntablePos, playbackSessionId, audioSessionId, formatMillis(audioMillis),
                formatMillis(expectedMillis),
                formatDelta(audioMillis, expectedMillis));
    }

    private static long driftOrZero(long actual, long expected) {
        return actual >= 0L && expected >= 0L ? actual - expected : 0L;
    }
}
