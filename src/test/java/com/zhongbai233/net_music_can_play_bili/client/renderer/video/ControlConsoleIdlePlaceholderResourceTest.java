package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ControlConsoleIdlePlaceholderResourceTest {
    private static final String IDLE = "/assets/net_music_can_play_bili/textures/gui/video_loading/idle_base.png";
    private static final String[] LOADING = {
        "/assets/net_music_can_play_bili/textures/gui/video_loading/loading_base_phase0.png",
        "/assets/net_music_can_play_bili/textures/gui/video_loading/loading_base_phase1.png",
        "/assets/net_music_can_play_bili/textures/gui/video_loading/loading_base_phase2.png",
        "/assets/net_music_can_play_bili/textures/gui/video_loading/loading_base_phase3.png"
    };

    @Test
    void idlePlaceholderIsPackagedAtTheVideoCanvasSize() throws IOException {
        BufferedImage idle = read(IDLE);
        assertEquals(320, idle.getWidth());
        assertEquals(180, idle.getHeight());
    }

    @Test
    void idlePlaceholderIsNotALoadingFrame() throws IOException {
        byte[] idle = readBytes(IDLE);
        for (String loadingPath : LOADING) {
            assertFalse(java.util.Arrays.equals(idle, readBytes(loadingPath)),
                    "idle placeholder must not reuse " + loadingPath);
        }
    }

    private static BufferedImage read(String path) throws IOException {
        try (InputStream stream = ControlConsoleIdlePlaceholderResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing placeholder resource: " + path);
            BufferedImage image = ImageIO.read(stream);
            assertNotNull(image, "invalid PNG resource: " + path);
            return image;
        }
    }

    private static byte[] readBytes(String path) throws IOException {
        try (InputStream stream = ControlConsoleIdlePlaceholderResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing placeholder resource: " + path);
            return stream.readAllBytes();
        }
    }
}
