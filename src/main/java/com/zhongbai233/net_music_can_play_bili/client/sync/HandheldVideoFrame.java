package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;

import java.nio.ByteBuffer;

/** 手持视频管线暴露的最新解码帧。 */
public record HandheldVideoFrame(byte[] data, ByteBuffer buffer, int byteLength,
        Fmp4NativeVideoDecoder.DecodedFrame.Format format, int width, int height, long ptsNanos,
        AutoCloseable delegate) implements AutoCloseable {
    public static HandheldVideoFrame retain(Fmp4NativeVideoDecoder.DecodedFrame decoded, int byteLength, int width,
            int height, long ptsNanos) {
        ByteBuffer buffer = decoded.buffer();
        byte[] data = buffer == null ? decoded.data() : null;
        return new HandheldVideoFrame(data, buffer, byteLength, decoded.format(), width, height, ptsNanos, decoded);
    }

    public HandheldVideoFrame retain() {
        if (delegate instanceof Fmp4NativeVideoDecoder.DecodedFrame decoded) {
            Fmp4NativeVideoDecoder.DecodedFrame retained = decoded.retain();
            ByteBuffer retainedBuffer = retained.buffer();
            return new HandheldVideoFrame(retainedBuffer == null ? retained.data() : null, retainedBuffer, byteLength,
                    format, width, height, ptsNanos, retained);
        }
        return new HandheldVideoFrame(data, buffer, byteLength, format, width, height, ptsNanos, null);
    }

    @Override
    public void close() {
        if (delegate != null) {
            try {
                delegate.close();
            } catch (Exception ignored) {
            }
        }
    }
}
