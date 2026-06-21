package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;

/** 手持音频、视频和字幕同步模型的公共入口。 */
public final class HandheldMediaSystem {
    private HandheldMediaSystem() {
    }

    public static HandheldMediaPlayback playback(UUID deviceId, HandheldMediaDeviceProfile profile) {
        return profile != null ? profile.playback(deviceId) : HandheldMediaPlayback.EMPTY;
    }

    public static HandheldMediaRenderState renderState(UUID deviceId, HandheldMediaDeviceProfile profile) {
        return profile != null ? profile.renderState(deviceId) : HandheldMediaRenderState.DISABLED;
    }

    public static HandheldMediaScreenSpec screenSpec(HandheldMediaDeviceProfile profile) {
        return profile != null ? profile.screenSpec() : new HandheldMediaScreenSpec(1, 1, 1);
    }

    public static ClientMediaTimelineView timeline(UUID deviceId, HandheldMediaDeviceProfile profile,
            long fallbackMillis, long fallbackTotalMillis) {
        return ClientMediaTimelineView.forHandheldOwner(deviceId, profile, fallbackMillis, fallbackTotalMillis);
    }

    public static boolean hasStartedSound(UUID deviceId, HandheldMediaDeviceProfile profile, String sessionId) {
        return profile != null && profile.hasStartedSound(deviceId, sessionId);
    }
}
