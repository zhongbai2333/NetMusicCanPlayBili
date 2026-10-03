package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VideoPlanePackingTest {
    @Test void directPlaneCopyPreservesSourceCursorAndClipsPadding() {
        ByteBuffer source = ByteBuffer.allocateDirect(9);
        source.put(new byte[]{99, 16, 32, 64, 96, (byte) 128, (byte) 235, 98, 97}).position(7).limit(8);
        ByteBuffer target = ByteBuffer.allocateDirect(6);
        VideoPlanePacking.copy(target, source, 1, 6);
        assertEquals(7, source.position());
        assertEquals(8, source.limit());
        byte[] actual = new byte[target.remaining()]; target.get(actual);
        assertArrayEquals(new byte[]{16, 32, 64, 96, (byte) 128, (byte) 235}, actual);
    }

    @Test void rgbaFallbackRetainsInterleavedUvAndOpaqueAlpha() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{42, 16, 32, (byte) 240, (byte) 128, 99});
        source.position(5);
        ByteBuffer target = ByteBuffer.allocateDirect(8);
        VideoPlanePacking.expandUv(target, source, 1, 2);
        byte[] actual = new byte[target.remaining()]; target.get(actual);
        assertArrayEquals(new byte[]{16, 32, 0, (byte) 255, (byte) 240, (byte) 128, 0, (byte) 255}, actual);
        assertEquals(5, source.position());
    }

    @Test void reusedDestinationDoesNotUploadTailOfPreviousFrame() {
        ByteBuffer target = ByteBuffer.allocateDirect(16);
        VideoPlanePacking.copy(target, ByteBuffer.wrap(new byte[16]), 0, 16);
        VideoPlanePacking.copy(target, ByteBuffer.wrap(new byte[]{1, 2}), 0, 2);
        assertEquals(2, target.remaining());
    }

    @Test void rejectsInvalidPlaneDimensionsAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> VideoPlanePacking.byteCount(0, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> VideoPlanePacking.byteCount(-1, 2, 1));
        assertThrows(ArithmeticException.class, () -> VideoPlanePacking.byteCount(Integer.MAX_VALUE, 2, 4));
        assertEquals(1920 * 1080 * 2, VideoPlanePacking.byteCount(1920, 1080, 2));
    }

    @Test void rejectsShortPlanesBeforeCopyAndDoesNotMoveSource() {
        ByteBuffer source = ByteBuffer.allocateDirect(3).position(2);
        assertThrows(IndexOutOfBoundsException.class,
                () -> VideoPlanePacking.copy(ByteBuffer.allocateDirect(6), source, 1, 3));
        assertThrows(IndexOutOfBoundsException.class,
                () -> VideoPlanePacking.expandUv(ByteBuffer.allocateDirect(8), source, 0, 2));
        assertEquals(2, source.position());
    }
}
