package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/** 将已上传帧的相对 PTS 固定映射到该帧所属 decoder generation 的媒体时间。 */
final class VideoMediaTimestampPolicy {
    private VideoMediaTimestampPolicy() {
    }

    static long absoluteMillis(long frameBaseOffsetMillis, long framePtsNanos,
            long latencyCompensationMillis, long totalMillis) {
        if (frameBaseOffsetMillis < 0L || framePtsNanos < 0L) {
            return -1L;
        }
        long value = Math.max(0L, frameBaseOffsetMillis + framePtsNanos / 1_000_000L
                - latencyCompensationMillis);
        return totalMillis > 0L ? Math.min(totalMillis, value) : value;
    }
}