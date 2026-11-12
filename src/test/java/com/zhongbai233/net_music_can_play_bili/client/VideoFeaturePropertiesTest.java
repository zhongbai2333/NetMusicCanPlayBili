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
                false, 100, 600, "BV1GJ411x7h7", 16), VideoFeatureProperties.realMediaLifecycle());
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

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
