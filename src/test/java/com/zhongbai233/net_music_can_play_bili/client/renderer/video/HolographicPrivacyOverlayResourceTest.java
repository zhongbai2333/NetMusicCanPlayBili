package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HolographicPrivacyOverlayResourceTest {
    private static final String RESOURCE =
            "/assets/net_music_can_play_bili/textures/gui/holographic_privacy_overlay.png";

    @Test
    void safeModeContainsCompleteFGlyph() throws IOException {
        BufferedImage image = read();
        assertEquals(320, image.getWidth());
        assertEquals(180, image.getHeight());

        // SAFE MODE 从 x=111 开始；F 是第三个 5x7 双像素字形，左上角为 (135, 66)。
        assertEquals(0xFFFFFFFF, image.getRGB(143, 66), "F top bar is missing");
        assertEquals(0xFFFFFFFF, image.getRGB(141, 72), "F middle bar is missing");
        assertEquals(0xFFFFFFFF, image.getRGB(135, 78), "F vertical stem is missing");
    }

    private static BufferedImage read() throws IOException {
        try (InputStream stream = HolographicPrivacyOverlayResourceTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(stream, "missing privacy overlay resource");
            BufferedImage image = ImageIO.read(stream);
            assertNotNull(image, "invalid privacy overlay PNG");
            return image;
        }
    }
}