package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;

/** 中控台字幕的纯布局与颜色策略；实际字形拆行由Minecraft Font完成。 */
public final class SubtitleLayout {
    public static final float WORLD_TEXT_SCALE = 0.025F;

    private SubtitleLayout() {
    }

    public static float scrollLineScale(float distance) {
        if (!Float.isFinite(distance)) {
            return 0.56F;
        }
        float t = Math.clamp(Math.abs(distance) / 2.0F, 0.0F, 1.0F);
        float eased = t * t * (3.0F - 2.0F * t);
        return 1.0F - eased * 0.44F;
    }

    public static boolean isScrollingMode(String contentMode) {
        return "SCROLL_MAIN".equals(contentMode) || "SCROLL_TRANSLATION".equals(contentMode);
    }

    public static String nextDisplayMode(String contentMode) {
        return switch (contentMode) {
            case "LYRICS" -> "SCROLL_MAIN";
            case "SCROLL_MAIN", "SCROLL_TRANSLATION" -> "FIXED";
            case "FIXED" -> "AI_SUBTITLE";
            case "AI_SUBTITLE" -> LiveSubtitleMetadata.TITLE_MODE;
            case LiveSubtitleMetadata.TITLE_MODE -> LiveSubtitleMetadata.ROOM_MODE;
            case LiveSubtitleMetadata.ROOM_MODE -> LiveSubtitleMetadata.STATUS_MODE;
            default -> "LYRICS";
        };
    }

    public static String toggleScrollingTrack(String contentMode) {
        return switch (contentMode) {
            case "SCROLL_MAIN" -> "SCROLL_TRANSLATION";
            case "SCROLL_TRANSLATION" -> "SCROLL_MAIN";
            default -> contentMode;
        };
    }

    public static float x(ControlConsoleElement.Alignment alignment, int lineWidth) {
        return switch (java.util.Objects.requireNonNull(alignment, "alignment")) {
            case LEFT -> 0.0F;
            case CENTER -> -lineWidth * 0.5F;
            case RIGHT -> -lineWidth;
        };
    }

    public static int splitWidth(float maxWidth, boolean wrap) {
        return wrap && Float.isFinite(maxWidth) && maxWidth > 0.0F
                ? Math.max(1, (int) Math.floor(maxWidth)) : Integer.MAX_VALUE;
    }

    public static int multiplyAlpha(int color, float opacity) {
        float normalized = Float.isFinite(opacity) ? Math.clamp(opacity, 0.0F, 1.0F) : 0.0F;
        int alpha = Math.round((color >>> 24) * normalized);
        return color & 0x00FFFFFF | alpha << 24;
    }
}
