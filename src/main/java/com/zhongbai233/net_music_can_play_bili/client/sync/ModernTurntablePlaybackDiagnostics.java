package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.VideoBillboardPreview;
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
    private static final long WARN_DRIFT_MILLIS = Long.getLong("bili.playback.diagnostics.warn_drift_ms", 2_000L);
    private static final ConcurrentHashMap<String, Long> LAST_LOG_MILLIS_BY_SESSION = new ConcurrentHashMap<>();

    private ModernTurntablePlaybackDiagnostics() {
    }

    public static void logEveryThreeSeconds(BlockPos turntablePos, String sessionId) {
        if (turntablePos == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_LOG_MILLIS_BY_SESSION.get(sessionId);
        if (last != null && now - last < LOG_INTERVAL_MILLIS) {
            return;
        }
        if (last != null && !LAST_LOG_MILLIS_BY_SESSION.replace(sessionId, last, now)) {
            return;
        }
        if (last == null && LAST_LOG_MILLIS_BY_SESSION.putIfAbsent(sessionId, now) != null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            finish(sessionId);
            return;
        }
        if (!(minecraft.level.getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity turntable)
                || !turntable.isPlaying()) {
            finish(sessionId);
            return;
        }

        ModernTurntableTimeline.TimelineSnapshot timeline = ModernTurntableTimeline.snapshot(turntablePos);
        long localMillis = timeline.localMillis();
        long serverMillis = timeline.serverMillis();

        VideoBillboardPreview.VideoSyncStatus video = VideoBillboardPreview.getSyncStatus(sessionId);
        long videoMillis = video.mediaMillis();
        DolbyAudioRegistry.AudioTimeline audio = DolbyAudioRegistry.getAudioTimeline(turntablePos);
        long audioMillis = audio.combinedMillis();
        long subtitleMillis = subtitleMillis(turntable);

        LOGGER.info(
                "播放管线关键时间: local={} server={} pacing={} localDrift={} video={} expectedVideo={} videoQueued={} audio={} audioMain={} audioMainFed={} audioRelay={} audioRelayCount={}/{} expectedAudio={} subtitle={} expectedSubtitle={} driftVideo={} driftAudio={} driftAudioFed={} session={}",
                formatMillis(localMillis), formatMillis(serverMillis), formatMillis(timeline.pacingMillis()),
                formatDelta(localMillis, serverMillis),
                formatMillis(videoMillis), formatMillis(localMillis), formatMillis(video.queuedMediaMillis()),
                formatMillis(audioMillis), formatMillis(audio.mainMillis()), formatMillis(audio.mainFedMillis()),
                formatMillis(audio.relayMillis()),
                audio.relayStartedCount(), audio.relayRegisteredCount(), formatMillis(localMillis),
                formatMillis(subtitleMillis), formatMillis(localMillis),
                formatDelta(videoMillis, localMillis), formatDelta(audioMillis, localMillis),
                formatDelta(audio.mainFedMillis(), localMillis), sessionId);
        warnIfLargeDrift(sessionId, localMillis, serverMillis, videoMillis, video.queuedMediaMillis(), audioMillis,
                audio.mainFedMillis(), timeline.pacingMillis());
    }

    public static void finish(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            LAST_LOG_MILLIS_BY_SESSION.remove(sessionId);
            ModernTurntableTimeline.forgetSession(sessionId);
        }
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
        long threshold = Math.max(0L, WARN_DRIFT_MILLIS);
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
                "播放管线秒级漂移: session={} local={} server={} pacing={} video={} videoQueued={} audio={} audioFed={} driftVideo={} driftAudio={} driftLocalServer={}",
                sessionId, formatMillis(localMillis), formatMillis(serverMillis), formatMillis(pacingMillis),
                formatMillis(videoMillis), formatMillis(videoQueuedMillis), formatMillis(audioMillis),
                formatMillis(audioFedMillis), formatDelta(videoMillis, localMillis),
                formatDelta(audioMillis, localMillis),
                formatDelta(localMillis, serverMillis));
    }

    private static long driftOrZero(long actual, long expected) {
        return actual >= 0L && expected >= 0L ? actual - expected : 0L;
    }
}