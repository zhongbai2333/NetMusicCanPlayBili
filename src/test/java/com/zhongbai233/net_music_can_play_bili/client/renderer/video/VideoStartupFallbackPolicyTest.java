package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.DecodeMode;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.VideoCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        private static VideoCandidate candidate(int width, int height, int quality) {
        return new VideoCandidate("https://example.invalid/video.m4s", 13, width, height, 30, quality,
            DecodeMode.HARDWARE_REQUIRED);
    }
}