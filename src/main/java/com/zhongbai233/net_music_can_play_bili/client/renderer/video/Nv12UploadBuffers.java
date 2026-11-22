package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.nio.ByteBuffer;

/** CPU layout conversion shared by the backend-neutral NV12 upload path. */
final class Nv12UploadBuffers {
    private Nv12UploadBuffers() {
    }

    static void expandUv(ByteBuffer source, int offset, int pixels, ByteBuffer destination) {
        int sourceBytes = Math.multiplyExact(pixels, 2);
        int destinationBytes = Math.multiplyExact(pixels, 4);
        if (pixels < 0 || offset < 0 || offset > source.limit() - sourceBytes
                || destination.remaining() < destinationBytes) {
            throw new IllegalArgumentException("Invalid NV12 UV plane bounds");
        }
        for (int i = 0; i < pixels; i++) {
            destination.put(source.get(offset + 2 * i));
            destination.put(source.get(offset + 2 * i + 1));
            destination.put((byte) 0);
            destination.put((byte) 255);
        }
    }
}
