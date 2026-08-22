package com.zhongbai233.net_music_can_play_bili.client.renderer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VideoProjectorPresentationContractTest {
    @Test
    void physicalProjectorAlwaysSubmitsAnOpaqueFrame() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/zhongbai233/net_music_can_play_bili/"
                + "client/renderer/VideoProjectorRenderer.java"));

        assertFalse(source.contains("PlaybackPresentationEnvelope"),
                "Physical projectors must not fade video frames");
        assertFalse(source.contains("frameOpacity"),
                "Physical projectors must not route decoded frames through translucent rendering");
        assertTrue(source.contains("captureProjectorImmediatePose(state.playbackSessionId.orElseThrow(),\n"
                + "                    state.projectorPos, screenPose, halfHeight);"));
    }
}
