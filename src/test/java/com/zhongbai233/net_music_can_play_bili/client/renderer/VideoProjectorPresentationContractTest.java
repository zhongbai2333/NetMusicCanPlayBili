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
        assertTrue(source.contains("state.projectorPos, screenPose, halfHeight, 1.0F, state.projectionBrightness"));
        assertTrue(source.contains("1.0F, state.projectionBrightness"),
                "Physical projector brightness must preserve opaque frame submission");
    }

    @Test
    void projectorBrightnessIsPersistedAndExposedInUi() throws IOException {
        String blockEntity = Files.readString(Path.of("src/main/java/com/zhongbai233/net_music_can_play_bili/"
                + "blockentity/VideoProjectorBlockEntity.java"));
        String packet = Files.readString(Path.of("src/main/java/com/zhongbai233/net_music_can_play_bili/"
                + "network/VideoProjectorConfigPacket.java"));
        String screen = Files.readString(Path.of("src/main/java/com/zhongbai233/net_music_can_play_bili/"
                + "gui/VideoProjectorScreen.java"));

        assertTrue(blockEntity.contains("output.putFloat(PROJ_BRIGHTNESS, projectionBrightness)"));
        assertTrue(blockEntity.contains("input.getFloatOr(PROJ_BRIGHTNESS, VideoSurfaceBrightness.DEFAULT)"));
        assertTrue(packet.contains("be.setProjectionBrightness(payload.brightness())"));
        assertTrue(screen.contains("\"画面亮度\""));
    }
}
