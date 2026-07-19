package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaNetworkFailureClassifierTest {
    @Test
    void classifiesNetworkFailuresWithoutMislabelingMediaFailures() {
        assertTrue(MediaNetworkFailureClassifier.isNetworkFailure(new SocketTimeoutException("read timed out")));
        assertTrue(MediaNetworkFailureClassifier.isNetworkFailure(
                new IllegalStateException("wrapper", new IOException("CDN response ended early"))));
        assertFalse(MediaNetworkFailureClassifier.isNetworkFailure(
                new IOException("unable to parse fMP4 moov decoder config")));
        assertFalse(MediaNetworkFailureClassifier.isNetworkFailure(
                new IOException("native video decoder unavailable")));
    }
}