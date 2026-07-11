package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/**
 * 视频播放使用的媒体时间线抽象。
 *
 * <p>
 * 当前现代化唱片机/视频投影仪实现背后仍走 ModernTurntableTimeline；MP4/Pad/预览视频可提供基于
 * ClientMediaTimelineView / MediaTimelineClock 的实现。VideoPlaybackInstance
 * 只消费本接口，不依赖具体载体。
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
