package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

/** JVM property boundary for video and lyric projector rendering. */
final class ProjectorRenderProperties {
    static final String VIDEO_RENDER_MARGIN = "ncpb.video.projector.render_margin";
    static final String VIDEO_RENDER_MAX_ASPECT = "ncpb.video.projector.render_max_aspect";

    static final String LYRIC_SCROLL_MIN_DURATION_MILLIS = "ncpb.lyric.scroll.min_duration_ms";
    static final String LYRIC_SCROLL_FAST_GAP_MILLIS = "ncpb.lyric.scroll.fast_gap_ms";
    static final String LYRIC_SCROLL_INTERPOLATION_HALF_LIFE_MILLIS =
            "ncpb.lyric.scroll.interpolation_half_life_ms";
    static final String LEGACY_LYRIC_SCROLL_INTERPOLATION_HALF_LIFE_MILLIS =
            "bili.lyric.scroll.interpolation_half_life_ms";
    static final String LYRIC_AUDIO_DELAY_MILLIS = "ncpb.lyric.audio_delay_ms";

    static final String LYRIC_RENDER_MARGIN = "ncpb.lyric.projector.render_margin";
    static final String LYRIC_RENDER_MIN_INFLATE = "ncpb.lyric.projector.render_min_inflate";
    static final String LYRIC_RENDER_MAX_TEXT_WIDTH = "ncpb.lyric.projector.render_max_text_width";

    private ProjectorRenderProperties() {
    }

    static VideoBounds videoBounds() {
        return new VideoBounds(
                NcpbSystemProperties.doubleValue(VIDEO_RENDER_MARGIN, 0.5D),
                NcpbSystemProperties.doubleValue(VIDEO_RENDER_MAX_ASPECT, 8.0D));
    }

    static LyricScroll lyricScroll() {
        return new LyricScroll(
                NcpbSystemProperties.longValue(LYRIC_SCROLL_MIN_DURATION_MILLIS, 120L),
                NcpbSystemProperties.longValue(LYRIC_SCROLL_FAST_GAP_MILLIS, 850L),
                NcpbSystemProperties.longValue(LYRIC_SCROLL_INTERPOLATION_HALF_LIFE_MILLIS,
                        LEGACY_LYRIC_SCROLL_INTERPOLATION_HALF_LIFE_MILLIS, 35L),
                NcpbSystemProperties.longValue(LYRIC_AUDIO_DELAY_MILLIS, 0L));
    }

    static LyricBounds lyricBounds() {
        return new LyricBounds(
                NcpbSystemProperties.doubleValue(LYRIC_RENDER_MARGIN, 2.0D),
                NcpbSystemProperties.doubleValue(LYRIC_RENDER_MIN_INFLATE, 2.5D),
                NcpbSystemProperties.doubleValue(LYRIC_RENDER_MAX_TEXT_WIDTH, 8.0D));
    }

    record VideoBounds(double margin, double maxAspect) {
        VideoBounds {
            margin = Math.max(0.0D, margin);
            maxAspect = Math.max(0.0D, maxAspect);
        }
    }

    record LyricScroll(long minDurationMillis, long fastGapMillis,
            long interpolationHalfLifeMillis, long audioDelayMillis) {
        LyricScroll {
            minDurationMillis = Math.max(0L, minDurationMillis);
            fastGapMillis = Math.max(0L, fastGapMillis);
            interpolationHalfLifeMillis = Math.max(1L, interpolationHalfLifeMillis);
            audioDelayMillis = Math.max(0L, audioDelayMillis);
        }
    }

    record LyricBounds(double margin, double minInflate, double maxTextWidth) {
        LyricBounds {
            margin = Math.max(0.0D, margin);
            minInflate = Math.max(0.0D, minInflate);
            maxTextWidth = Math.max(0.0D, maxTextWidth);
        }
    }
}
