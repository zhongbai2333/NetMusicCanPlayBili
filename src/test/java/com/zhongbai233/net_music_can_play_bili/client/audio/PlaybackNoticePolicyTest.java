package com.zhongbai233.net_music_can_play_bili.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackNoticePolicyTest {
    @Test
    void lowVolumeNoticeUsesShrunkenWarningRange() {
        assertTrue(PlaybackNoticePolicy.isWithinNoticeRange(38.4F, 0.5F));
        assertTrue(PlaybackNoticePolicy.isWithinNoticeRange(40.0F, 0.5F));
        assertFalse(PlaybackNoticePolicy.isWithinNoticeRange(42.0F, 0.5F));
    }

    @Test
    void mutedOutputNeverShowsNotice() {
        assertFalse(PlaybackNoticePolicy.isWithinNoticeRange(0.0F, 0.0F));
        assertFalse(PlaybackNoticePolicy.isWithinNoticeRange(1.5F, 0.0F));
    }
}