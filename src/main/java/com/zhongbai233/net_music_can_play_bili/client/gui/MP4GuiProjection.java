package com.zhongbai233.net_music_can_play_bili.client.gui;

/**
 * MP4 投影屏幕的共用校准参数，供视觉渲染和透明输入映射使用。
 */
public final class MP4GuiProjection {
    /**
     * 聚焦 MP4 图形界面在屏幕上的近似投影中心偏移，单位为 GUI 像素。
     */
    public static final int PORTRAIT_CENTER_X_OFFSET = 112;
    public static final int LANDSCAPE_CENTER_X_OFFSET = 101;
    public static final int CENTER_Y_OFFSET = 0;

    private MP4GuiProjection() {
    }

    public static int centerXOffset() {
        return MP4GuiLayout.isLandscape() ? LANDSCAPE_CENTER_X_OFFSET : PORTRAIT_CENTER_X_OFFSET;
    }

    public static int centerYOffset() {
        return CENTER_Y_OFFSET;
    }
}
