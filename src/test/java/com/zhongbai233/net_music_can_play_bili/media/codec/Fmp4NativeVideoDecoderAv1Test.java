package com.zhongbai233.net_music_can_play_bili.media.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void recognizesKnownHardwareBackendNames() {
        assertTrue(VideoHardwareBackendPolicy.isHardwareBackend("videotoolbox"));
        assertTrue(VideoHardwareBackendPolicy.isHardwareBackend("d3d11va"));
        assertTrue(VideoHardwareBackendPolicy.isHardwareBackend("vaapi"));
        assertTrue(VideoHardwareBackendPolicy.isHardwareBackend("CUDA"));
        assertTrue(VideoHardwareBackendPolicy.isHardwareBackend(" qsv "));
    }

    @Test
    void rejectsSoftwareDisabledAndUnknownBackendNames() {
        assertFalse(VideoHardwareBackendPolicy.isHardwareBackend("cpu"));
        assertFalse(VideoHardwareBackendPolicy.isHardwareBackend("cpu-fallback"));
        assertFalse(VideoHardwareBackendPolicy.isHardwareBackend("none"));
        assertFalse(VideoHardwareBackendPolicy.isHardwareBackend("off"));
        assertFalse(VideoHardwareBackendPolicy.isHardwareBackend("unknown-old-native"));
        assertFalse(VideoHardwareBackendPolicy.isHardwareBackend("software"));
        assertFalse(VideoHardwareBackendPolicy.isHardwareBackend("not-opened"));
        assertFalse(VideoHardwareBackendPolicy.isHardwareBackend("future-unknown-backend"));
        assertFalse(VideoHardwareBackendPolicy.isHardwareBackend(" "));
        assertFalse(VideoHardwareBackendPolicy.isHardwareBackend(null));
    }
}
