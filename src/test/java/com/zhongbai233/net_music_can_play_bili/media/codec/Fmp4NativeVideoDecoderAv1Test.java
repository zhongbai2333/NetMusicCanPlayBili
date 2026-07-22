package com.zhongbai233.net_music_can_play_bili.media.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class Fmp4NativeVideoDecoderAv1Test {
    @Test
    void extractsConfigObusAfterAv1CodecConfigurationHeader() {
        byte[] av1C = { (byte) 0x81, 0x0D, 0x0C, 0x00, 0x0A, 0x01, 0x02 };

        assertArrayEquals(new byte[] { 0x0A, 0x01, 0x02 },
                Fmp4NativeVideoDecoder.parseAv1ConfigObus(av1C));
    }

    @Test
    void acceptsValidAv1CWithoutConfigObus() {
        assertArrayEquals(new byte[0], Fmp4NativeVideoDecoder.parseAv1ConfigObus(
                new byte[] { (byte) 0x81, 0x0D, 0x0C, 0x00 }));
    }

    @Test
    void rejectsTruncatedWrongMarkerAndWrongVersion() {
        assertNull(Fmp4NativeVideoDecoder.parseAv1ConfigObus(new byte[] { (byte) 0x81, 0x00, 0x00 }));
        assertNull(Fmp4NativeVideoDecoder.parseAv1ConfigObus(new byte[] { 0x01, 0x00, 0x00, 0x00 }));
        assertNull(Fmp4NativeVideoDecoder.parseAv1ConfigObus(
                new byte[] { (byte) 0x82, 0x00, 0x00, 0x00 }));
    }
}