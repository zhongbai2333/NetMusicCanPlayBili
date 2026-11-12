package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

class VideoPlaceholderArtworkTest {
    private static final String ASSETS = "/assets/net_music_can_play_bili/";
    private static final String LOADING = ASSETS + "textures/gui/video_loading/";

    @Test
    void ordinaryPlaceholderArtworkRemainsOpaqueForTheSolidPass() throws IOException {
        for (String name : new String[] {
                "loading_base_phase0.png", "loading_base_phase1.png",
                "loading_base_phase2.png", "loading_base_phase3.png",
                "iris_translucent_warning_base.png", "idle_base.png", "network_error_base.png",
                "progress_segment_42x6.png"
        }) {
            assertFalse(anyAlphaMatches(read(LOADING + name), alpha -> alpha != 0xff),
                    name + " must remain fully opaque for the depth-writing solid pass");
        }
    }

    @Test
    void privacyArtworkRetainsVideoCanvasDimensionsAndRealTranslucencyWithoutEmptyHoles() throws IOException {
        BufferedImage image = read(ASSETS + "textures/gui/holographic_privacy_overlay.png");
        assertEquals(320, image.getWidth());
        assertEquals(180, image.getHeight());
        assertFalse(anyAlphaMatches(image, alpha -> alpha == 0), "privacy artwork must not contain empty holes");
        assertTrue(anyAlphaMatches(image, alpha -> alpha > 0 && alpha < 0xff),
                "privacy artwork must retain partial alpha for the translucent pass");
    }

    private static BufferedImage read(String path) throws IOException {
        return decode(readBytes(path), path);
    }

    private static BufferedImage decode(byte[] bytes, String path) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image, "invalid video artwork " + path);
        return image;
    }

    private static byte[] readBytes(String path) throws IOException {
        try (InputStream input = VideoPlaceholderArtworkTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "missing video artwork " + path);
            return input.readAllBytes();
        }
    }

    private static boolean anyAlphaMatches(BufferedImage image, IntPredicate predicate) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (predicate.test(image.getRGB(x, y) >>> 24)) return true;
            }
        }
        return false;
    }
}
