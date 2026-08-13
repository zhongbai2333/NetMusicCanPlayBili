package com.zhongbai233.net_music_can_play_bili.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoClientPropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        assertEquals(new VideoClientProperties.Turntable(true, 2), VideoClientProperties.turntable());
        assertEquals(new VideoClientProperties.Live(30, 116), VideoClientProperties.live());
        assertEquals(new VideoClientProperties.Handheld(4, "auto", "none", 2),
                VideoClientProperties.handheld());
    }

    @Test
    void explicitValuesRemainConfigurable() {
        set(VideoClientProperties.TURNTABLE_ENABLED, "false");
        set(VideoClientProperties.TURNTABLE_RESOLVE_THREADS, "3");
        set(VideoClientProperties.LIVE_FPS, "45");
        set(VideoClientProperties.LIVE_QUALITY_CEILING, "120");
        set(VideoClientProperties.HANDHELD_MAX_THREADS, "6");
        set(VideoClientProperties.HANDHELD_NATIVE_HWACCEL, "  videotoolbox  ");
        set(VideoClientProperties.PAD_NATIVE_HWACCEL, "  auto  ");
        set(VideoClientProperties.MP4_OFFSCREEN_SCALE, "3");

        assertEquals(new VideoClientProperties.Turntable(false, 3), VideoClientProperties.turntable());
        assertEquals(new VideoClientProperties.Live(45, 120), VideoClientProperties.live());
        assertEquals(new VideoClientProperties.Handheld(6, "videotoolbox", "auto", 3),
                VideoClientProperties.handheld());
    }

    @Test
    void canonicalKeysTakePriorityOverLegacyFallbacks() {
        set(VideoClientProperties.LEGACY_TURNTABLE_RESOLVE_THREADS, "4");
        set(VideoClientProperties.LEGACY_LIVE_QUALITY_CEILING, "80");
        assertEquals(4, VideoClientProperties.turntable().resolveThreads());
        assertEquals(80, VideoClientProperties.live().qualityCeiling());

        set(VideoClientProperties.TURNTABLE_RESOLVE_THREADS, "5");
        set(VideoClientProperties.LIVE_QUALITY_CEILING, "112");
        assertEquals(5, VideoClientProperties.turntable().resolveThreads());
        assertEquals(112, VideoClientProperties.live().qualityCeiling());
    }

    @Test
    void invalidAndBlankValuesUseDefaultsOrValidLegacyFallbacks() {
        set(VideoClientProperties.TURNTABLE_ENABLED, "yes");
        set(VideoClientProperties.TURNTABLE_RESOLVE_THREADS, "invalid");
        set(VideoClientProperties.LEGACY_TURNTABLE_RESOLVE_THREADS, "3");
        set(VideoClientProperties.LIVE_FPS, "invalid");
        set(VideoClientProperties.LIVE_QUALITY_CEILING, "invalid");
        set(VideoClientProperties.LEGACY_LIVE_QUALITY_CEILING, "100");
        set(VideoClientProperties.HANDHELD_MAX_THREADS, "invalid");
        set(VideoClientProperties.HANDHELD_NATIVE_HWACCEL, "   ");
        set(VideoClientProperties.PAD_NATIVE_HWACCEL, "   ");
        set(VideoClientProperties.MP4_OFFSCREEN_SCALE, "invalid");

        assertEquals(new VideoClientProperties.Turntable(true, 3), VideoClientProperties.turntable());
        assertEquals(new VideoClientProperties.Live(30, 100), VideoClientProperties.live());
        assertEquals(new VideoClientProperties.Handheld(4, "auto", "none", 2),
                VideoClientProperties.handheld());
    }

    @Test
    void unsafeValuesAreClamped() {
        set(VideoClientProperties.TURNTABLE_RESOLVE_THREADS, "0");
        set(VideoClientProperties.LIVE_FPS, "0");
        set(VideoClientProperties.LIVE_QUALITY_CEILING, "-1");
        set(VideoClientProperties.HANDHELD_MAX_THREADS, "1");
        set(VideoClientProperties.MP4_OFFSCREEN_SCALE, "0");

        assertEquals(1, VideoClientProperties.turntable().resolveThreads());
        assertEquals(new VideoClientProperties.Live(15, 1), VideoClientProperties.live());
        assertEquals(2, VideoClientProperties.handheld().maxThreads());
        assertEquals(1, VideoClientProperties.handheld().mp4OffscreenScale());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
