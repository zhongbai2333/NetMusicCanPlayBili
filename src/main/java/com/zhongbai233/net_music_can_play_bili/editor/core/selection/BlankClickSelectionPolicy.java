package com.zhongbai233.net_music_can_play_bili.editor.core.selection;

/** 区分空白单击与相机拖动；只有左键短距离单击才取消当前选择。 */
public final class BlankClickSelectionPolicy {
    private static final double CLICK_DISTANCE_SQUARED = 16.0D;

    private BlankClickSelectionPolicy() {
    }

    public static boolean shouldDeselect(boolean blankCandidate, int mouseButton,
            double pressX, double pressY, double releaseX, double releaseY) {
        if (!blankCandidate || mouseButton != 0) {
            return false;
        }
        double dx = releaseX - pressX;
        double dy = releaseY - pressY;
        return dx * dx + dy * dy < CLICK_DISTANCE_SQUARED;
    }
}