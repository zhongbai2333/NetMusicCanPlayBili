package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoResolveAdmissionPolicyTest {
    @Test
    void liveRequestStartsOnlyWithCurrentSourceAndConsumer() {
        assertEquals(VideoResolveAdmissionPolicy.Decision.START,
                VideoResolveAdmissionPolicy.decide(true, true, true, true));
        assertEquals(VideoResolveAdmissionPolicy.Decision.DROP_NO_CONSUMER,
                VideoResolveAdmissionPolicy.decide(true, true, true, false));
    }

    @Test
    void staleSessionAndStoppedSourceCannotPublishLateResults() {
        assertEquals(VideoResolveAdmissionPolicy.Decision.DROP_STALE_REQUEST,
                VideoResolveAdmissionPolicy.decide(false, true, true, true));
        assertEquals(VideoResolveAdmissionPolicy.Decision.DROP_SESSION_CHANGED,
                VideoResolveAdmissionPolicy.decide(true, false, true, true));
        assertEquals(VideoResolveAdmissionPolicy.Decision.DROP_SOURCE_STOPPED,
                VideoResolveAdmissionPolicy.decide(true, true, false, true));
    }
}