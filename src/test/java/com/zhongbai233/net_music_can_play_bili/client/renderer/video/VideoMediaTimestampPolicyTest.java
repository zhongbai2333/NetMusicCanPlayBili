package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoMediaTimestampPolicyTest {
    @Test
    void retainedFrameKeepsItsOriginalGenerationBaseOffset() {
        long oldFrameBase = 46_750L;
        long oldFramePts = 54_570_000_000L;

        assertEquals(101_320L,
                VideoMediaTimestampPolicy.absoluteMillis(oldFrameBase, oldFramePts, 0L, 0L));
        // 新 generation seek 到 103854ms 不应把这张旧纹理错误解释为 158424ms。
        assertEquals(101_320L,
                VideoMediaTimestampPolicy.absoluteMillis(oldFrameBase, oldFramePts, 0L, 200_000L));
    }

    @Test
    void timestampIsClampedAndUnavailableWithoutUploadedFrame() {
        assertEquals(120_000L,
                VideoMediaTimestampPolicy.absoluteMillis(100_000L, 25_000_000_000L, 0L, 120_000L));
        assertEquals(-1L, VideoMediaTimestampPolicy.absoluteMillis(-1L, 1L, 0L, 0L));
    }
}