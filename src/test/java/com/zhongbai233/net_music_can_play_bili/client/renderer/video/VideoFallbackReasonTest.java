package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoFallbackReasonTest {
    @Test
    void distinguishesHardwareProfileGenericAndMissingH264Failures() {
        assertEquals(VideoFallbackReason.AV1_HARDWARE_UNAVAILABLE,
                VideoFallbackReason.classifyAv1StartupFailure(
                        new IOException("actual backend=cpu while hardware required"), true));
        assertEquals(VideoFallbackReason.AV1_PROFILE_INCOMPATIBLE,
                VideoFallbackReason.classifyAv1StartupFailure(new IOException("unsupported AV1 profile"), true));
        assertEquals(VideoFallbackReason.AV1_STARTUP_FAILURE,
                VideoFallbackReason.classifyAv1StartupFailure(new IOException("first frame damaged"), true));
        assertEquals(VideoFallbackReason.NO_H264_CANDIDATE,
                VideoFallbackReason.classifyAv1StartupFailure(new IOException("anything"), false));
    }

    @Test
    void labelsAreStableForUi() {
        assertEquals("AV1硬解不可用", VideoFallbackReason.userLabel(
                VideoFallbackReason.AV1_HARDWARE_UNAVAILABLE));
        assertEquals("性能降级(FPS)", VideoFallbackReason.userLabel(
                VideoFallbackReason.PERFORMANCE_LOW_FPS));
    }
}
