package com.zhongbai233.net_music_can_play_bili.client.renderer;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleVideoPlaceholderAssetsTest {
    private static final String ROOT = "/assets/net_music_can_play_bili/textures/gui/control_console_video/";

    @Test
    void idleBufferingAndErrorAreDistinctOpaque320x180Assets() throws Exception {
        Set<String> hashes = new HashSet<>();
        for (String name : new String[] { "idle.png", "buffering.png", "error.png" }) {
            byte[] bytes;
            try (InputStream input = getClass().getResourceAsStream(ROOT + name)) {
                assertNotNull(input, "missing control-console placeholder " + name);
                bytes = input.readAllBytes();
            }
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            assertNotNull(image, "invalid PNG " + name);
            assertEquals(320, image.getWidth(), name);
            assertEquals(180, image.getHeight(), name);
            assertTrue(isFullyOpaque(image), name + " must be fully opaque");
            assertTrue(hasVisualContrast(image), name + " must contain visible artwork");
            hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        assertEquals(3, hashes.size(), "each control-console state requires unique artwork");
    }

    private static boolean isFullyOpaque(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0xff) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasVisualContrast(BufferedImage image) {
        int first = image.getRGB(0, 0) & 0x00ffffff;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0x00ffffff) != first) {
                    return true;
                }
            }
        }
        return false;
    }
}
