package com.zhongbai233.net_music_can_play_bili.client.gui;

import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;

/**
 * MP4 图形界面的共用逻辑布局。
 * <p>
 * 竖屏（96×176）：现代音乐播放器，包括专辑封面、歌曲信息、进度、控制按钮、音量滑条和播放列表开关。
 * </p>
 * <p>
 * 横屏（176×96）：类 Bilibili 视频播放器，包括全屏视频和点击显示的播放/暂停、上一个/下一个、清晰度、音量、竖屏切换控制层。
 * </p>
 */
public final class MP4GuiLayout {
    public static final int PORTRAIT_W = 96;
    public static final int PORTRAIT_H = 176;
    public static final int LANDSCAPE_W = 176;
    public static final int LANDSCAPE_H = 96;

    private MP4GuiLayout() {
    }

    public static int logicalWidth() {
        return MP4FocusState.landscape() ? LANDSCAPE_W : PORTRAIT_W;
    }

    public static int logicalHeight() {
        return MP4FocusState.landscape() ? LANDSCAPE_H : PORTRAIT_H;
    }

    public static boolean isLandscape() {
        return MP4FocusState.landscape();
    }

    // 共用区域

    public static GuiRect panelBackground() {
        return new GuiRect(0, 0, logicalWidth(), logicalHeight());
    }

    /** 完整视频区域（横屏）或专辑封面区域（竖屏）。 */
    public static GuiRect mediaArea() {
        return isLandscape()
                ? new GuiRect(3, 3, LANDSCAPE_W - 6, LANDSCAPE_H - 6)
                : new GuiRect(11, 23, 74, 72);
    }

    public static GuiRect progressBar() {
        return isLandscape()
                ? new GuiRect(14, 76, LANDSCAPE_W - 28, 4)
                : new GuiRect(10, 116, 75, 5);
    }

    public static GuiRect playButton() {
        return isLandscape()
                ? new GuiRect(12, 78, 18, 14)
                : new GuiRect(38, 127, 20, 21);
    }

    public static GuiRect prevButton() {
        return isLandscape()
                ? new GuiRect(43, 79, 15, 13)
                : new GuiRect(13, 130, 16, 16);
    }

    public static GuiRect nextButton() {
        return isLandscape()
                ? new GuiRect(70, 79, 15, 13)
                : new GuiRect(68, 130, 16, 16);
    }

    public static GuiRect qualityButton() {
        return isLandscape()
                ? new GuiRect(110, 78, 31, 14)
                : new GuiRect(75, 4, 13, 9);
    }

    public static GuiRect orientationButton() {
        return isLandscape()
                ? new GuiRect(145, 78, 19, 14)
                : new GuiRect(86, 5, 8, 8);
    }

    // 竖屏专用
    /** 顶部标题栏。 */
    public static GuiRect topBar() {
        return new GuiRect(8, 8, 80, 16);
    }

    public static GuiRect songTitleLine() {
        return new GuiRect(12, 93, 72, 7);
    }

    public static GuiRect artistLine() {
        return new GuiRect(12, 100, 72, 6);
    }

    public static GuiRect volumeSlider() {
        return new GuiRect(24, 154, 58, 5);
    }

    public static GuiRect volumeKnob() {
        float vol = MP4FocusState.volume();
        int knobX = 24 + Math.round(vol * 58);
        return new GuiRect(knobX - 2, 151, 5, 10);
    }

    public static GuiRect shuffleButton() {
        return new GuiRect(8, 162, 18, 6);
    }

    public static GuiRect repeatButton() {
        return new GuiRect(29, 162, 18, 6);
    }

    public static GuiRect playlistButton() {
        return new GuiRect(51, 162, 22, 6);
    }

    /** 打开时从底部滑入的播放列表面板。 */
    public static GuiRect playlistPanel() {
        return new GuiRect(8, 24, 80, 130);
    }

    // 横屏专用
    /** 完整控制条背景。 */
    public static GuiRect controlBar() {
        return new GuiRect(0, 70, LANDSCAPE_W, 26);
    }

    public static GuiRect timeDisplay() {
        return new GuiRect(70, 78, 60, 14);
    }

    public static GuiRect volumeSliderLandscape() {
        return new GuiRect(122, 80, 24, 8);
    }

    // 辅助方法

    public static GuiRect of(int x, int y, int w, int h) {
        return new GuiRect(x, y, w, h);
    }

    public record GuiRect(int x, int y, int w, int h) {
        public int right() {
            return x + w;
        }

        public int bottom() {
            return y + h;
        }

        public int centerX() {
            return x + w / 2;
        }

        public int centerY() {
            return y + h / 2;
        }
    }
}