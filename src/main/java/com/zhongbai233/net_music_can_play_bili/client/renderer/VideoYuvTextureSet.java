package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import net.minecraft.resources.Identifier;

interface VideoYuvTextureSet extends AutoCloseable {
    Identifier yId();

    Identifier uId();

    Identifier vId();

    int width();

    int height();

    Fmp4NativeVideoDecoder.DecodedFrame.Format format();

    boolean upload(VideoBillboardPreview.DecodedFrame frame, int width, int height);

    @Override
    void close();
}