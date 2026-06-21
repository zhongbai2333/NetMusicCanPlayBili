package com.zhongbai233.net_music_can_play_bili.client.sync;

/** 手持媒体设备 GUI 纹理的逻辑尺寸。 */
public record HandheldMediaScreenSpec(int portraitWidth, int portraitHeight, int offscreenScale) {
    public HandheldMediaScreenSpec {
        portraitWidth = Math.max(1, portraitWidth);
        portraitHeight = Math.max(1, portraitHeight);
        offscreenScale = Math.max(1, offscreenScale);
    }

    public int landscapeWidth() {
        return portraitHeight;
    }

    public int landscapeHeight() {
        return portraitWidth;
    }

    public int targetWidth() {
        return portraitWidth * offscreenScale;
    }

    public int targetHeight() {
        return portraitHeight * offscreenScale;
    }
}
