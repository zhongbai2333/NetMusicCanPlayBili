package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class Mp3FrameSyncTest {
    @Test
    void findsSyncAfterNoise() {
        byte[] stream = new byte[13 + 417 * 3];
        for (int i = 0; i < 13; i++) {
            stream[i] = (byte) (0x40 + i);
        }
        for (int offset = 13; offset < stream.length; offset += 417) {
            writeMpeg1Layer3FrameHeader(stream, offset);
        }

        assertEquals(13, Mp3FrameSync.findFrameSync(stream, stream.length));
    }

    @Test
    void rejectsInvalidAndTruncatedFrames() {
        byte[] invalid = { 0x00, 0x11, 0x22, 0x33, 0x44 };
        assertEquals(-1, Mp3FrameSync.findFrameSync(invalid, invalid.length));
        assertNull(Mp3FrameSync.parseFrame(invalid, 0, invalid.length));

        byte[] truncatedHeader = { (byte) 0xFF, (byte) 0xFB, (byte) 0x90 };
        assertEquals(-1, Mp3FrameSync.findFrameSync(truncatedHeader, truncatedHeader.length));
    }

    @Test
    void rejectsIsolatedFalseSync() {
        byte[] stream = new byte[2048];
        writeMpeg1Layer3FrameHeader(stream, 7);

        assertEquals(-1, Mp3FrameSync.findFrameSync(stream, stream.length));
    }

    private static void writeMpeg1Layer3FrameHeader(byte[] target, int offset) {
        target[offset] = (byte) 0xFF;
        target[offset + 1] = (byte) 0xFB;
        target[offset + 2] = (byte) 0x90;
        target[offset + 3] = 0x64;
    }
}