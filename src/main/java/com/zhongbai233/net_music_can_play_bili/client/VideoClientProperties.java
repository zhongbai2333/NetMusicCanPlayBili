package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

/** JVM property boundary for turntable, live, and handheld video clients. */
final class VideoClientProperties {
    static final String TURNTABLE_ENABLED = "ncpb.video.turntable.enabled";
    static final String TURNTABLE_RESOLVE_THREADS = "ncpb.video.turntable.resolve_threads";
    static final String LEGACY_TURNTABLE_RESOLVE_THREADS = "bili.video.turntable.resolve_threads";

    static final String LIVE_FPS = "ncpb.video.live.fps";
    static final String LIVE_QUALITY_CEILING = "ncpb.video.live.quality_ceiling";
    static final String LEGACY_LIVE_QUALITY_CEILING = "bili.video.turntable.quality";

    static final String HANDHELD_MAX_THREADS = "ncpb.mp4.video.max_threads";
    static final String HANDHELD_NATIVE_HWACCEL = "ncpb.mp4.video.native.hwaccel";
    static final String PAD_NATIVE_HWACCEL = "ncpb.pad.video.native.hwaccel";
    static final String MP4_OFFSCREEN_SCALE = "ncpb.mp4.offscreen_scale";

    private VideoClientProperties() {
    }

    static Turntable turntable() {
        return new Turntable(
                NcpbSystemProperties.booleanValue(TURNTABLE_ENABLED, true),
                NcpbSystemProperties.intValue(
                        TURNTABLE_RESOLVE_THREADS, LEGACY_TURNTABLE_RESOLVE_THREADS, 2));
    }

    static Live live() {
        return new Live(
                NcpbSystemProperties.intValue(LIVE_FPS, 30),
                NcpbSystemProperties.intValue(
                        LIVE_QUALITY_CEILING, LEGACY_LIVE_QUALITY_CEILING, 116));
    }

    static Handheld handheld() {
        return new Handheld(
                NcpbSystemProperties.intValue(HANDHELD_MAX_THREADS, 4),
                NcpbSystemProperties.stringValue(HANDHELD_NATIVE_HWACCEL, "auto"),
                NcpbSystemProperties.stringValue(PAD_NATIVE_HWACCEL, "none"),
                NcpbSystemProperties.intValue(MP4_OFFSCREEN_SCALE, 2));
    }

    record Turntable(boolean enabled, int resolveThreads) {
        Turntable {
            resolveThreads = Math.max(1, resolveThreads);
        }
    }

    record Live(int fps, int qualityCeiling) {
        Live {
            fps = Math.max(15, fps);
            qualityCeiling = Math.max(1, qualityCeiling);
        }
    }

    record Handheld(int maxThreads, String nativeHwaccel, String padNativeHwaccel,
            int mp4OffscreenScale) {
        Handheld {
            maxThreads = Math.max(2, maxThreads);
            mp4OffscreenScale = Math.max(1, mp4OffscreenScale);
        }
    }
}
