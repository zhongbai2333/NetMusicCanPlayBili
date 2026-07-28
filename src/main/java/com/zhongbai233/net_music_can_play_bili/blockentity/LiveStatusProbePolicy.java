package com.zhongbai233.net_music_can_play_bili.blockentity;

/** 直播机后台开播探测的无状态调度规则。 */
final class LiveStatusProbePolicy {
    static final long PROBE_INTERVAL_TICKS = 20L * 10L;

    private LiveStatusProbePolicy() {
    }

    static boolean shouldProbe(boolean autoResumeRequested, boolean probeInFlight,
            long gameTime, long nextProbeGameTime) {
        return autoResumeRequested && !probeInFlight && gameTime >= nextProbeGameTime;
    }

    static long nextProbeGameTime(long gameTime) {
        return gameTime > Long.MAX_VALUE - PROBE_INTERVAL_TICKS
                ? Long.MAX_VALUE
                : gameTime + PROBE_INTERVAL_TICKS;
    }

    static boolean acceptsResult(boolean autoResumeRequested, long currentGeneration,
            long capturedGeneration, long currentProbeId, long capturedProbeId,
            String currentRoomId, String requestedRoomId) {
        return autoResumeRequested
                && currentGeneration == capturedGeneration
                && currentProbeId == capturedProbeId
                && requestedRoomId.equals(currentRoomId);
    }
}