package com.zhongbai233.net_music_can_play_bili.client.renderer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectorRenderPropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        assertEquals(new ProjectorRenderProperties.VideoBounds(0.5D, 8.0D),
                ProjectorRenderProperties.videoBounds());
        assertEquals(new ProjectorRenderProperties.LyricScroll(120L, 850L, 35L, 0L),
                ProjectorRenderProperties.lyricScroll());
        assertEquals(new ProjectorRenderProperties.LyricBounds(2.0D, 2.5D, 8.0D),
                ProjectorRenderProperties.lyricBounds());
    }

    @Test
    void explicitValuesRemainConfigurable() {
        set(ProjectorRenderProperties.VIDEO_RENDER_MARGIN, "0.75");
        set(ProjectorRenderProperties.VIDEO_RENDER_MAX_ASPECT, "10.0");
        set(ProjectorRenderProperties.LYRIC_SCROLL_MIN_DURATION_MILLIS, "150");
        set(ProjectorRenderProperties.LYRIC_SCROLL_FAST_GAP_MILLIS, "900");
        set(ProjectorRenderProperties.LYRIC_SCROLL_INTERPOLATION_HALF_LIFE_MILLIS, "45");
        set(ProjectorRenderProperties.LYRIC_AUDIO_DELAY_MILLIS, "80");
        set(ProjectorRenderProperties.LYRIC_RENDER_MARGIN, "3.0");
        set(ProjectorRenderProperties.LYRIC_RENDER_MIN_INFLATE, "3.5");
        set(ProjectorRenderProperties.LYRIC_RENDER_MAX_TEXT_WIDTH, "12.0");

        assertEquals(new ProjectorRenderProperties.VideoBounds(0.75D, 10.0D),
                ProjectorRenderProperties.videoBounds());
        assertEquals(new ProjectorRenderProperties.LyricScroll(150L, 900L, 45L, 80L),
                ProjectorRenderProperties.lyricScroll());
        assertEquals(new ProjectorRenderProperties.LyricBounds(3.0D, 3.5D, 12.0D),
                ProjectorRenderProperties.lyricBounds());
    }

    @Test
    void canonicalHalfLifeTakesPriorityOverLegacyFallback() {
        set(ProjectorRenderProperties.LEGACY_LYRIC_SCROLL_INTERPOLATION_HALF_LIFE_MILLIS, "55");
        assertEquals(55L, ProjectorRenderProperties.lyricScroll().interpolationHalfLifeMillis());

        set(ProjectorRenderProperties.LYRIC_SCROLL_INTERPOLATION_HALF_LIFE_MILLIS, "40");
        assertEquals(40L, ProjectorRenderProperties.lyricScroll().interpolationHalfLifeMillis());
    }

    @Test
    void invalidAndNonFiniteValuesUseDefaultsOrValidLegacyFallback() {
        set(ProjectorRenderProperties.VIDEO_RENDER_MARGIN, "invalid");
        set(ProjectorRenderProperties.VIDEO_RENDER_MAX_ASPECT, "NaN");
        set(ProjectorRenderProperties.LYRIC_SCROLL_MIN_DURATION_MILLIS, "invalid");
        set(ProjectorRenderProperties.LYRIC_SCROLL_FAST_GAP_MILLIS, "invalid");
        set(ProjectorRenderProperties.LYRIC_SCROLL_INTERPOLATION_HALF_LIFE_MILLIS, "invalid");
        set(ProjectorRenderProperties.LEGACY_LYRIC_SCROLL_INTERPOLATION_HALF_LIFE_MILLIS, "50");
        set(ProjectorRenderProperties.LYRIC_AUDIO_DELAY_MILLIS, "invalid");
        set(ProjectorRenderProperties.LYRIC_RENDER_MARGIN, "Infinity");
        set(ProjectorRenderProperties.LYRIC_RENDER_MIN_INFLATE, "invalid");
        set(ProjectorRenderProperties.LYRIC_RENDER_MAX_TEXT_WIDTH, "-Infinity");

        assertEquals(new ProjectorRenderProperties.VideoBounds(0.5D, 8.0D),
                ProjectorRenderProperties.videoBounds());
        assertEquals(new ProjectorRenderProperties.LyricScroll(120L, 850L, 50L, 0L),
                ProjectorRenderProperties.lyricScroll());
        assertEquals(new ProjectorRenderProperties.LyricBounds(2.0D, 2.5D, 8.0D),
                ProjectorRenderProperties.lyricBounds());
    }

    @Test
    void unsafeValuesAreClamped() {
        set(ProjectorRenderProperties.VIDEO_RENDER_MARGIN, "-1.0");
        set(ProjectorRenderProperties.VIDEO_RENDER_MAX_ASPECT, "-1.0");
        set(ProjectorRenderProperties.LYRIC_SCROLL_MIN_DURATION_MILLIS, "-1");
        set(ProjectorRenderProperties.LYRIC_SCROLL_FAST_GAP_MILLIS, "-1");
        set(ProjectorRenderProperties.LYRIC_SCROLL_INTERPOLATION_HALF_LIFE_MILLIS, "0");
        set(ProjectorRenderProperties.LYRIC_AUDIO_DELAY_MILLIS, "-1");
        set(ProjectorRenderProperties.LYRIC_RENDER_MARGIN, "-1.0");
        set(ProjectorRenderProperties.LYRIC_RENDER_MIN_INFLATE, "-1.0");
        set(ProjectorRenderProperties.LYRIC_RENDER_MAX_TEXT_WIDTH, "-1.0");

        assertEquals(new ProjectorRenderProperties.VideoBounds(0.0D, 0.0D),
                ProjectorRenderProperties.videoBounds());
        assertEquals(new ProjectorRenderProperties.LyricScroll(0L, 0L, 1L, 0L),
                ProjectorRenderProperties.lyricScroll());
        assertEquals(new ProjectorRenderProperties.LyricBounds(0.0D, 0.0D, 0.0D),
                ProjectorRenderProperties.lyricBounds());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
