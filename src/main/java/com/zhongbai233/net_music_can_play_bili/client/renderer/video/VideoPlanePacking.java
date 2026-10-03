package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Tightly packed CPU planes, independent of the renderer and native decoder ownership. */
final class VideoPlanePacking {
    private VideoPlanePacking() { }

    static int byteCount(int width, int height, int components) {
        if (width <= 0 || height <= 0 || components <= 0) {
            throw new IllegalArgumentException("Plane dimensions and components must be positive");
        }
        return Math.multiplyExact(Math.multiplyExact(width, height), components);
    }

    static ByteBuffer slice(ByteBuffer source, int offset, int length) {
        Objects.requireNonNull(source, "source");
        Objects.checkFromIndexSize(offset, length, source.limit());
        return source.slice(offset, length);
    }

    static void copy(ByteBuffer destination, ByteBuffer source, int offset, int length) {
        destination.clear();
        destination.put(slice(source, offset, length));
        destination.flip();
    }

    static void expandUv(ByteBuffer destination, ByteBuffer source, int offset, int pixels) {
        ByteBuffer uv = slice(source, offset, Math.multiplyExact(pixels, 2));
        destination.clear();
        for (int i = 0; i < pixels; i++) {
            destination.put(uv.get()).put(uv.get()).put((byte) 0).put((byte) 255);
        }
        destination.flip();
    }
}
