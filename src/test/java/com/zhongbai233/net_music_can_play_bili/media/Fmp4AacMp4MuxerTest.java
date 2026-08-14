package com.zhongbai233.net_music_can_play_bili.media;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Fmp4AacMp4MuxerTest {
    @Test
    void buildsSingleTrackAacMp4WithStableSampleMetadata() {
        byte[] asc = { 0x11, (byte) 0x90 };
        byte[] audio = { 1, 2, 3, 4, 5 };

        byte[] mp4 = Fmp4AacMp4Muxer.build(asc, new int[] { 3, 2 }, new int[] { 1024, 1024 }, audio, 48_000);
        ByteBuffer boxes = ByteBuffer.wrap(mp4).order(ByteOrder.BIG_ENDIAN);

        int ftypSize = boxes.getInt();
        assertEquals("ftyp", readFourCc(boxes));
        boxes.position(ftypSize);
        int moovSize = boxes.getInt();
        assertEquals("moov", readFourCc(boxes));
        byte[] moov = new byte[moovSize - 8];
        boxes.get(moov);
        int mp4a = indexOf(moov, "mp4a".getBytes(StandardCharsets.US_ASCII));
        int mdhd = indexOf(moov, "mdhd".getBytes(StandardCharsets.US_ASCII));
        assertTrue(mp4a >= 0);
        assertTrue(mdhd >= 0);
        assertTrue(indexOf(moov, asc) > mp4a);
        assertEquals(48_000, ByteBuffer.wrap(moov).order(ByteOrder.BIG_ENDIAN).getInt(mdhd + 16));

        int mdatSize = boxes.getInt();
        assertEquals("mdat", readFourCc(boxes));
        assertEquals(audio.length + 8, mdatSize);
        byte[] payload = new byte[mdatSize - 8];
        boxes.get(payload);
        assertArrayEquals(audio, payload);
        assertEquals(0, boxes.remaining());
    }

    private static String readFourCc(ByteBuffer boxes) {
        byte[] type = new byte[4];
        boxes.get(type);
        return new String(type, StandardCharsets.US_ASCII);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int index = 0; index <= haystack.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }
}
