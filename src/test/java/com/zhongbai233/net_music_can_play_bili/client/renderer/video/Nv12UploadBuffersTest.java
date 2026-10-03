package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class Nv12UploadBuffersTest {
    @Test
    void expandsInterleavedUvWithoutChangingSourcePosition() {
        ByteBuffer source = ByteBuffer.allocateDirect(6);
        source.put(new byte[] {99, 98, 16, (byte) 240, (byte) 128, 64}).position(1);
        ByteBuffer target = ByteBuffer.allocate(8);
        Nv12UploadBuffers.expandUv(source.asReadOnlyBuffer(), 2, 2, target);
        assertArrayEquals(new byte[] {16, (byte) 240, 0, (byte) 255,
                (byte) 128, 64, 0, (byte) 255}, target.array());
        assertEquals(1, source.position());
    }

    @Test
    void validatesBoundsBeforeWritingAnyPixels() {
        ByteBuffer source = ByteBuffer.allocate(4);
        ByteBuffer target = ByteBuffer.allocate(8);
        assertThrows(IllegalArgumentException.class,
                () -> Nv12UploadBuffers.expandUv(source, 1, 2, target));
        assertEquals(0, target.position());
        assertThrows(IllegalArgumentException.class,
                () -> Nv12UploadBuffers.expandUv(source, 0, 2, ByteBuffer.allocate(7)));
        assertThrows(ArithmeticException.class,
                () -> Nv12UploadBuffers.expandUv(source, 0, Integer.MAX_VALUE, target));
    }
}
