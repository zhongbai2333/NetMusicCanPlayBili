package com.zhongbai233.net_music_can_play_bili.client.debug;

/** Screen-relative sizing shared by the independent audio and video debug panels. */
final class DebugHudLayout {
    static final double SINGLE_PANEL_WIDTH_RATIO = 0.44D;
    static final double DUAL_PANEL_WIDTH_RATIO = 0.38D;
    static final double PANEL_HEIGHT_RATIO = 0.46D;

    private DebugHudLayout() {
    }

    static Plan plan(int screenWidth, int screenHeight, boolean dualPanels,
            int baseWidth, int baseHeight, int margin) {
        if (baseWidth <= 0 || baseHeight <= 0) {
            return Plan.hidden();
        }
        int widthBudget = widthBudget(screenWidth, dualPanels, margin);
        int availableHeight = Math.max(0, screenHeight - margin * 2);
        int heightBudget = Math.min(availableHeight,
                (int) Math.floor(screenHeight * PANEL_HEIGHT_RATIO));
        if (widthBudget <= 0 || heightBudget <= 0) {
            return Plan.hidden();
        }
        float scale = Math.min(1.0F, Math.min(
                widthBudget / (float) baseWidth,
                heightBudget / (float) baseHeight));
        return scale > 0.0F ? new Plan(baseWidth, baseHeight, scale) : Plan.hidden();
    }

    static int widthBudget(int screenWidth, boolean dualPanels, int margin) {
        int availableWidth = dualPanels
                ? (screenWidth - margin * 3) / 2
                : screenWidth - margin * 2;
        double ratio = dualPanels ? DUAL_PANEL_WIDTH_RATIO : SINGLE_PANEL_WIDTH_RATIO;
        int ratioWidth = (int) Math.floor(screenWidth * ratio);
        return Math.max(0, Math.min(availableWidth, ratioWidth));
    }

    record Plan(int baseWidth, int baseHeight, float scale) {
        static Plan hidden() {
            return new Plan(0, 0, 0.0F);
        }

        boolean visible() {
            return scale > 0.0F;
        }

        float renderedWidth() {
            return baseWidth * scale;
        }

        float renderedHeight() {
            return baseHeight * scale;
        }
    }
}
