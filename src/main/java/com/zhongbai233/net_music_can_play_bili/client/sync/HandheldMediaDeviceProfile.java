package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;

/** 手持媒体设备差异入口；MP4 和未来 Pad 只需各自提供 profile。 */
public interface HandheldMediaDeviceProfile {
    HandheldMediaScreenSpec screenSpec();

    HandheldMediaPlayback playback(UUID deviceId);

    HandheldMediaRenderState renderState(UUID deviceId);

    boolean hasStartedSound(UUID deviceId, String sessionId);

    boolean isDeviceAvailable(UUID deviceId);

    String subtitleMode(UUID deviceId);
}
