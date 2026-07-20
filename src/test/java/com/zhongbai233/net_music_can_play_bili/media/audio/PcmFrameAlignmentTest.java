package com.zhongbai233.net_music_can_play_bili.media.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PcmFrameAlignmentTest {
    @Test
    void alignsStereo16BitSeekToWholeFrame() {
        assertEquals(60_504L, PcmFrameAlignment.alignDown(60_505L, 4));
    }

    @Test
    void alignsReadRequestForNonPowerOfTwoFrameSize() {
        assertEquals(65_532, PcmFrameAlignment.alignedRequest(100_000L, 65_536, 6));
        assertEquals(6, PcmFrameAlignment.alignedRequest(6L, 65_536, 6));
        assertEquals(5, PcmFrameAlignment.alignedRequest(5L, 65_536, 6));
    }

    @Test
    void clampsNegativeAndDegenerateInputs() {
        assertEquals(0L, PcmFrameAlignment.alignDown(-1L, 4));
        assertEquals(7L, PcmFrameAlignment.alignDown(7L, 0));
        assertEquals(0, PcmFrameAlignment.alignedRequest(0L, 64, 4));
    }
}