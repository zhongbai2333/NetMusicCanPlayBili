package com.zhongbai233.net_music_can_play_bili.editor.core.projection;

/** 编辑器视口在 GUI 中的像素区域。 */
public record EditorViewport(int x, int y, int width, int height) {
    public EditorViewport {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("viewport dimensions must be positive");
        }
    }

    public double aspectRatio() {
        return (double) width / (double) height;
    }
}