package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoOpacityRouteTest {
    @Test
    void normalizesAndRoutesSubmissionOpacity() {
        assertEquals(VideoOpacityRoute.SKIP, VideoOpacityRoute.choose(Float.NaN));
        assertEquals(VideoOpacityRoute.SKIP, VideoOpacityRoute.choose(-1.0F));
        assertEquals(VideoOpacityRoute.TRANSLUCENT, VideoOpacityRoute.choose(0.5F));
        assertEquals(VideoOpacityRoute.OPAQUE, VideoOpacityRoute.choose(1.0F));
        assertEquals(VideoOpacityRoute.OPAQUE, VideoOpacityRoute.choose(Float.POSITIVE_INFINITY));
        assertEquals(0x00FFFFFF, VideoOpacityRoute.whiteVertexColor(0.0F));
        assertEquals(0x80FFFFFF, VideoOpacityRoute.whiteVertexColor(0.5F));
        assertEquals(0xFFFFFFFF, VideoOpacityRoute.whiteVertexColor(1.0F));
    }
}