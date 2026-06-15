package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;

public interface VideoYuvTextureSet extends AutoCloseable {
    Identifier yId();

    Identifier uId();

    Identifier vId();

    int width();

    int height();

    Fmp4NativeVideoDecoder.DecodedFrame.Format format();

    boolean upload(byte[] data, int width, int height);

    boolean upload(ByteBuffer data, int byteLength, int width, int height);

    @Override
    void close();
}