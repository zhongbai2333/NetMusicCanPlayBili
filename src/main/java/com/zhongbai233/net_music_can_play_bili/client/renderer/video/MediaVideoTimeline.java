package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/**
 * 视频播放使用的媒体时间线抽象。
 *
 * <p>
 * 当前现代化唱片机实现背后仍走 ModernTurntableTimeline；未来 MP4 视频可提供基于
 * MP4ClientPlayback / MediaTimelineClock 的实现，避免 VideoPlaybackInstance 直接依赖某个载体。
 * </p>
 */
interface MediaVideoTimeline {
    long mediaMillis();

    long visualMillis();

    long pacingMillis();

    long relativeNanos(long absoluteStartMillis);

    long totalMillis();

    String sessionId();

    MediaVideoTimeline EMPTY = new MediaVideoTimeline() {
        @Override
        public long mediaMillis() {
            return -1L;
        }

        @Override
        public long visualMillis() {
            return -1L;
        }

        @Override
        public long pacingMillis() {
            return -1L;
        }

        @Override
        public long relativeNanos(long absoluteStartMillis) {
            return -1L;
        }

        @Override
        public long totalMillis() {
            return 0L;
        }

        @Override
        public String sessionId() {
            return "";
        }
    };
}
