package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.DecodeMode;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.VideoCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoStartupFallbackPolicyTest {
    @Test
    void lowerResolutionCandidateIsNotUpscaledBackToEightK() {
        var size = VideoStartupFallbackPolicy.candidateDecodeSize(8192, 4320, 3840, 2160);
        assertEquals(3840, size.width());
        assertEquals(2160, size.height());
    }

    @Test
    void oversizedCandidateIsScaledWithinCurrentTarget() {
        var size = VideoStartupFallbackPolicy.candidateDecodeSize(1920, 1080, 8192, 4320);
        assertEquals(1920, size.width());
        assertEquals(1012, size.height());
    }

    @Test
    void oversizedEightKCandidateIsSkippedBeforeOpeningNativeDecoder() {
        VideoCandidate eightK = candidate(8192, 4320, 127);
        VideoCandidate fourK = candidate(3840, 2160, 120);
        VideoCandidate fullHd = candidate(1920, 1080, 116);
        VideoCandidate h264 = new VideoCandidate("https://example.invalid/h264.m4s", 7,
                1920, 1080, 30, 80, DecodeMode.AUTO);

        assertEquals(List.of(h264, fourK, fullHd),
                VideoStartupFallbackPolicy.operationalCandidates(List.of(eightK, fourK, fullHd, h264),
                        4096, 2304));
    }

    @Test
    void onlyOversizedCandidateRemainsPlayable() {
        VideoCandidate eightK = candidate(8192, 4320, 127);
        assertEquals(List.of(eightK),
                VideoStartupFallbackPolicy.operationalCandidates(List.of(eightK), 4096, 2304));
    }

    @Test
    void boundedProbeAppliesOnlyToHardwareRequiredAv1Candidates() {
        VideoCandidate av1Hardware = candidate(1920, 1080, 80);
        VideoCandidate av1Auto = new VideoCandidate("https://example.invalid/av1-auto.m4s", 13,
                1920, 1080, 30, 80, DecodeMode.AUTO);
        VideoCandidate h264 = new VideoCandidate("https://example.invalid/h264.m4s", 7,
                1920, 1080, 30, 80, DecodeMode.HARDWARE_REQUIRED);

        assertTrue(VideoStartupFallbackPolicy.requiresBoundedFirstFrameProbe(av1Hardware));
        assertFalse(VideoStartupFallbackPolicy.requiresBoundedFirstFrameProbe(av1Auto));
        assertFalse(VideoStartupFallbackPolicy.requiresBoundedFirstFrameProbe(h264));
    }

    @Test
    void sustainedPerformanceFallbackLocksCandidateListToH264() {
        VideoCandidate av1Hardware = candidate(3840, 2160, 120);
        VideoCandidate av1Software = new VideoCandidate("https://example.invalid/av1-software.m4s", 13,
                1920, 1080, 30, 80, DecodeMode.SOFTWARE_ONLY);
        VideoCandidate h264High = new VideoCandidate("https://example.invalid/h264-high.m4s", 7,
                1920, 1080, 30, 80, DecodeMode.AUTO);
        VideoCandidate h264Low = new VideoCandidate("https://example.invalid/h264-low.m4s", 7,
                1280, 720, 30, 64, DecodeMode.AUTO);

        assertEquals(List.of(h264High, h264Low), VideoStartupFallbackPolicy.lockedH264Candidates(
                List.of(av1Hardware, av1Software, h264High, h264Low)));
        assertTrue(VideoStartupFallbackPolicy.lockedH264Candidates(List.of(av1Hardware)).isEmpty());
    }

    private static VideoCandidate candidate(int width, int height, int quality) {
        return new VideoCandidate("https://example.invalid/video.m4s", 13, width, height, 30, quality,
                DecodeMode.HARDWARE_REQUIRED);
    }
}
