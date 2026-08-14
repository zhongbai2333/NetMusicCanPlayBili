package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaDeviceProfile;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoPipelineConfig;
import org.slf4j.Logger;

import java.util.UUID;

/** Pauses offscreen decode and requests a timeline resync after a long hidden interval. */
final class HandheldOffscreenVideoPolicy {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HandheldVideoPipelineConfig CONFIG = HandheldVideoPipelineConfig.fromSystemProperties(
            "ncpb.mp4.video");

    private HandheldOffscreenVideoPolicy() {
    }

    static boolean waitWhileOffscreen(UUID deviceId, HandheldDeviceVideoState state, HandheldVideoSession session) {
        if (!CONFIG.offscreenPauseDecode() || !isPauseActive(state)) {
            return !session.closed.get() && session.key.equals(state.activeKey);
        }
        long pauseStartNs = System.nanoTime();
        session.performanceMonitor.pause(pauseStartNs);
        while (!session.closed.get() && session.key.equals(state.activeKey) && isPauseActive(state)) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        long pausedNs = System.nanoTime() - pauseStartNs;
        session.performanceMonitor.resume(System.nanoTime());
        if (pausedNs > 0L) {
            LOGGER.debug("MP4 横屏视频离屏恢复取帧: device={}, session={}, paused={}ms", deviceId,
                    session.key.sessionId(), pausedNs / 1_000_000L);
        }
        return !session.closed.get() && session.key.equals(state.activeKey);
    }

    static void maybeRestartVisibleSession(UUID deviceId, HandheldDeviceVideoState state, long offscreenDurationNs) {
        HandheldVideoSession session = state.activeSession;
        if (session == null || session.closed.get() || CONFIG.offscreenResumeRestartLagNanos() <= 0L) {
            return;
        }
        HandheldMediaDeviceProfile profile = MP4HandheldVideoClient.profileFor(deviceId);
        HandheldMediaPlayback playback = profile.playback(deviceId);
        if (playback == null || !session.key.playbackSessionId().equals(playback.playbackSessionId())) {
            return;
        }
        long visualMillis = HandheldVideoFrameTimeline.anchoredVisualMillis(deviceId, profile, playback);
        long latestMillis = HandheldVideoFrameTimeline.latestFrameMillis(state, session);
        long lagNs = latestMillis >= 0L ? (visualMillis - latestMillis) * 1_000_000L : offscreenDurationNs;
        if (visualMillis < 0L || lagNs < CONFIG.offscreenResumeRestartLagNanos()) {
            return;
        }
        LOGGER.debug("MP4 横屏视频离屏恢复重定位: device={}, session={}, offscreen={}ms, visual={}ms, latest={}ms",
                deviceId, session.key.sessionId(), offscreenDurationNs / 1_000_000L, visualMillis, latestMillis);
        stopForVisibleResync(state, "视频重新同步...");
        MP4HandheldVideoClient.update(deviceId);
    }

    private static boolean isPauseActive(HandheldDeviceVideoState state) {
        long lastVisible = state.lastVisibleNanoTime;
        if (lastVisible <= 0L) {
            state.lastVisibleNanoTime = System.nanoTime();
            return false;
        }
        long nowNs = System.nanoTime();
        boolean paused = nowNs - lastVisible > Math.max(0L, CONFIG.offscreenGraceNanos());
        if (paused && state.offscreenSinceNanoTime == 0L) {
            state.offscreenSinceNanoTime = nowNs;
        }
        return paused;
    }

    private static void stopForVisibleResync(HandheldDeviceVideoState state, String reason) {
        synchronized (state.lifecycleLock) {
            state.intentGeneration++;
            MP4HandheldVideoClient.cancelResolveTaskLocked(state);
            state.activeKey = HandheldPlaybackKey.EMPTY;
            state.resolvingKey = HandheldPlaybackKey.EMPTY;
            state.failedKey = HandheldPlaybackKey.EMPTY;
            state.endedKey = HandheldPlaybackKey.EMPTY;
            HandheldVideoSession session = state.activeSession;
            state.activeSession = null;
            if (session != null) {
                session.close();
            }
            if (reason != null && !reason.isBlank()) {
                state.statusText = reason;
            }
        }
        HandheldVideoFrameTimeline.clearFrameQueue(state);
    }
}
