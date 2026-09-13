package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoPlaceholderAlphaContractTest {
    private static final String VIDEO_LOADING =
            "/assets/net_music_can_play_bili/textures/gui/video_loading/";

    @Test
    void ordinaryPlaceholderArtworkIsFullyOpaque() throws IOException {
        for (String name : new String[] {
                "loading_base_phase0.png",
                "loading_base_phase1.png",
                "loading_base_phase2.png",
                "loading_base_phase3.png",
                "iris_translucent_warning_base.png",
                "idle_base.png",
                "network_error_base.png",
                "progress_segment_42x6.png"
        }) {
            BufferedImage image = read(VIDEO_LOADING + name);
            assertTrue(allAlphaMatches(image, alpha -> alpha == 0xff),
                    name + " must remain fully opaque so it can use the depth-writing solid pass");
        }
    }

    @Test
    void progressFrameUsesBinaryAlphaSuitableForCutout() throws IOException {
        BufferedImage image = read(VIDEO_LOADING + "progress_frame_204x10.png");
        boolean hasTransparentPixel = false;
        boolean hasOpaquePixel = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                assertTrue(alpha == 0 || alpha == 0xff,
                        "progress frame must not gain partial alpha without changing its render route");
                hasTransparentPixel |= alpha == 0;
                hasOpaquePixel |= alpha == 0xff;
            }
        }
        assertTrue(hasTransparentPixel, "progress frame must retain its transparent interior");
        assertTrue(hasOpaquePixel, "progress frame must retain a visible border");
    }

    @Test
    void privacyArtworkRetainsRealTranslucency() throws IOException {
        BufferedImage image = read(
                "/assets/net_music_can_play_bili/textures/gui/holographic_privacy_overlay.png");
        assertTrue(allAlphaMatches(image, alpha -> alpha > 0), "privacy artwork must not contain empty holes");
        assertTrue(anyAlphaMatches(image, alpha -> alpha > 0 && alpha < 0xff),
                "privacy artwork must retain partial alpha and use the translucent pass");
    }

    private static BufferedImage read(String path) throws IOException {
        try (InputStream input = VideoPlaceholderAlphaContractTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "missing video artwork " + path);
            BufferedImage image = ImageIO.read(input);
            assertNotNull(image, "invalid video artwork " + path);
            return image;
        }
    }

    private static boolean allAlphaMatches(BufferedImage image, AlphaPredicate predicate) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (!predicate.test(image.getRGB(x, y) >>> 24)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean anyAlphaMatches(BufferedImage image, AlphaPredicate predicate) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (predicate.test(image.getRGB(x, y) >>> 24)) {
                    return true;
                }
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface AlphaPredicate {
        boolean test(int alpha);
    }
}
