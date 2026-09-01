package com.zhongbai233.net_music_can_play_bili.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayableMediaUrlTest {
    @Test
    void acceptsOnlyAbsoluteHttpMediaUrls() {
        assertTrue(PlayableMediaUrl.isHttp("https://cdn.example.test/audio.m4s"));
        assertTrue(PlayableMediaUrl.isHttp(
                "http://cdn.example.test/audio.mp3#nmb_request=550e8400-e29b-41d4-a716-446655440000"));
        assertFalse(PlayableMediaUrl.isHttp("BV12LKP6gEdK|p=1"));
        assertFalse(PlayableMediaUrl.isHttp("BV12LKP6gEdK|p=1#nmb_request=550e8400-e29b-41d4-a716-446655440000"));
        assertFalse(PlayableMediaUrl.isHttp("ftp://cdn.example.test/audio.mp3"));
        assertFalse(PlayableMediaUrl.isHttp(""));
        assertFalse(PlayableMediaUrl.isHttp(null));
    }
}
