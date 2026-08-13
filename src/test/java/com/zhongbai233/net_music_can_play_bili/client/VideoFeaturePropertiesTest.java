package com.zhongbai233.net_music_can_play_bili.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoFeaturePropertiesTest {
    private static final String BOOLEAN_KEY = "ncpb.test.video_feature.boolean";
    private static final String INT_KEY = "ncpb.test.video_feature.int";
    private static final String LONG_KEY = "ncpb.test.video_feature.long";
    private static final String STRING_KEY = "ncpb.test.video_feature.string";
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void gatesDefaultToDisabled() {
        assertFalse(VideoFeatureProperties.advancedFeaturesEnabled());
        assertFalse(VideoFeatureProperties.benchFeaturesEnabled());
        assertFalse(VideoFeatureProperties.realBenchEnabled());
        assertFalse(VideoFeatureProperties.realBenchManaged());
        assertEquals(new VideoFeatureProperties.RealMediaLifecycle(
                false, 100, 600, "BV1qM4y1w716", 16), VideoFeatureProperties.realMediaLifecycle());
        assertEquals("auto", VideoFeatureProperties.nativeHwaccel());
        assertEquals("", VideoFeatureProperties.ffmpegDecoder());
    }

    @Test
    void gatesAndHwaccelRemainConfigurable() {
        set(VideoFeatureProperties.ADVANCED_FEATURES, "true");
        set(VideoFeatureProperties.BENCH_FEATURES, "TRUE");
        set(VideoFeatureProperties.REAL_BENCH, "true");
        set(VideoFeatureProperties.REAL_BENCH_MANAGED, "TRUE");
        set(VideoFeatureProperties.REAL_MEDIA_LIFECYCLE, "true");
        set(VideoFeatureProperties.REAL_MEDIA_LIFECYCLE_ROUNDS, "250");
        set(VideoFeatureProperties.REAL_MEDIA_LIFECYCLE_CYCLE_TIMEOUT_TICKS, "900");
        set(VideoFeatureProperties.REAL_BENCH_VIDEO_ID, "  BV-test  ");
        set(VideoFeatureProperties.REAL_MEDIA_LIFECYCLE_QUALITY, "32");
        set(VideoFeatureProperties.NATIVE_HWACCEL, "  videotoolbox  ");
        set(VideoFeatureProperties.FFMPEG_DECODER, "  h264_videotoolbox  ");

        assertTrue(VideoFeatureProperties.advancedFeaturesEnabled());
        assertTrue(VideoFeatureProperties.benchFeaturesEnabled());
        assertTrue(VideoFeatureProperties.realBenchEnabled());
        assertTrue(VideoFeatureProperties.realBenchManaged());
        assertEquals(new VideoFeatureProperties.RealMediaLifecycle(
                true, 250, 900, "BV-test", 32), VideoFeatureProperties.realMediaLifecycle());
        assertEquals("videotoolbox", VideoFeatureProperties.nativeHwaccel());
        assertEquals("h264_videotoolbox", VideoFeatureProperties.ffmpegDecoder());
    }

    @Test
    void typedAdvancedOverridesRemainConfigurable() {
        set(BOOLEAN_KEY, "false");
        set(INT_KEY, "42");
        set(LONG_KEY, "9000");
        set(STRING_KEY, "  decoder  ");

        assertFalse(VideoFeatureProperties.booleanValue(BOOLEAN_KEY, true));
        assertEquals(42, VideoFeatureProperties.intValue(INT_KEY, 1));
        assertEquals(9_000L, VideoFeatureProperties.longValue(LONG_KEY, 1L));
        assertEquals("decoder", VideoFeatureProperties.stringValue(STRING_KEY, "fallback"));
    }

    @Test
    void invalidAndBlankValuesUseCompatibilityDefaults() {
        set(VideoFeatureProperties.ADVANCED_FEATURES, "yes");
        set(VideoFeatureProperties.BENCH_FEATURES, "1");
        set(VideoFeatureProperties.REAL_BENCH, "yes");
        set(VideoFeatureProperties.REAL_BENCH_MANAGED, "1");
        set(VideoFeatureProperties.REAL_MEDIA_LIFECYCLE, "yes");
        set(VideoFeatureProperties.REAL_MEDIA_LIFECYCLE_ROUNDS, "0");
        set(VideoFeatureProperties.REAL_MEDIA_LIFECYCLE_CYCLE_TIMEOUT_TICKS, "999999");
        set(VideoFeatureProperties.REAL_BENCH_VIDEO_ID, "   ");
        set(VideoFeatureProperties.REAL_MEDIA_LIFECYCLE_QUALITY, "0");
        set(BOOLEAN_KEY, "yes");
        set(INT_KEY, "invalid");
        set(LONG_KEY, "invalid");
        set(STRING_KEY, "   ");
        set(VideoFeatureProperties.NATIVE_HWACCEL, "   ");
        set(VideoFeatureProperties.FFMPEG_DECODER, "   ");

        assertFalse(VideoFeatureProperties.advancedFeaturesEnabled());
        assertFalse(VideoFeatureProperties.benchFeaturesEnabled());
        assertFalse(VideoFeatureProperties.realBenchEnabled());
        assertFalse(VideoFeatureProperties.realBenchManaged());
        assertEquals(new VideoFeatureProperties.RealMediaLifecycle(
                false, 1, 72_000, "BV1qM4y1w716", 1), VideoFeatureProperties.realMediaLifecycle());
        assertTrue(VideoFeatureProperties.booleanValue(BOOLEAN_KEY, true));
        assertEquals(7, VideoFeatureProperties.intValue(INT_KEY, 7));
        assertEquals(11L, VideoFeatureProperties.longValue(LONG_KEY, 11L));
        assertEquals("fallback", VideoFeatureProperties.stringValue(STRING_KEY, "fallback"));
        assertEquals("auto", VideoFeatureProperties.nativeHwaccel());
        assertEquals("", VideoFeatureProperties.ffmpegDecoder());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
