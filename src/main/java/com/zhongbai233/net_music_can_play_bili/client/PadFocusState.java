package com.zhongbai233.net_music_can_play_bili.client;

import net.minecraft.world.InteractionHand;

import java.util.UUID;

/** Pad 手持横屏聚焦/投影输入状态。 */
public final class PadFocusState {
    private static boolean active;
    private static InteractionHand hand = InteractionHand.MAIN_HAND;
    private static int ticks;
    private static float hoverX;
    private static float hoverY;
    private static float targetHoverX;
    private static float targetHoverY;
    private static int hoverTextureX = -1;
    private static int hoverTextureY = -1;
    private static String hoverControl = "NONE";
    private static int pressTextureX = -1;
    private static int pressTextureY = -1;
    private static String pressControl = "NONE";
    private static int pressTicks;
    private static int draggingMediaId = -1;
    private static String draggingMediaName = "";
    private static UUID selectedPointId;
    private static UUID draggingPointId;
    private static int draggingPointTextureX = -1;
    private static int draggingPointTextureY = -1;
    private static long revision;
    private static long lastFrameTickNanos;
    private static final float[] projectedQuadX = new float[4];
    private static final float[] projectedQuadY = new float[4];
    private static boolean projectedQuadValid;
    private static int projectedQuadGuiWidth = -1;
    private static int projectedQuadGuiHeight = -1;
    private static long projectedQuadNanos;

    private PadFocusState() {
    }

    public static void activate(InteractionHand focusHand) {
        active = true;
        hand = focusHand;
        ticks = 0;
        revision++;
    }

    public static void deactivate() {
        active = false;
        ticks = 0;
        clearHoverTarget();
        projectedQuadValid = false;
        revision++;
    }

    public static void resetAll() {
        active = false;
        hand = InteractionHand.MAIN_HAND;
        ticks = 0;
        lastFrameTickNanos = 0L;
        hoverX = 0.0F;
        hoverY = 0.0F;
        targetHoverX = 0.0F;
        targetHoverY = 0.0F;
        hoverTextureX = -1;
        hoverTextureY = -1;
        hoverControl = "NONE";
        pressTextureX = -1;
        pressTextureY = -1;
        pressControl = "NONE";
        pressTicks = 0;
        draggingMediaId = -1;
        draggingMediaName = "";
        selectedPointId = null;
        draggingPointId = null;
        draggingPointTextureX = -1;
        draggingPointTextureY = -1;
        projectedQuadValid = false;
        projectedQuadGuiWidth = -1;
        projectedQuadGuiHeight = -1;
        projectedQuadNanos = 0L;
        revision++;
    }

    public static void tick() {
        tickPressFeedback();
        ticks++;
        tickHoverAnimation();
    }

    public static void renderTick() {
        long now = System.nanoTime();
        if (now - lastFrameTickNanos < 16_000_000L) {
            return;
        }
        lastFrameTickNanos = now;
        if (active) {
            ticks++;
        }
        tickPressFeedback();
        tickHoverAnimation();
    }

    public static boolean activeFor(InteractionHand queryHand) {
        return active && hand == queryHand;
    }

    public static boolean active() {
        return active;
    }

    public static InteractionHand hand() {
        return hand;
    }

    public static float progress(float partialTick) {
        if (!active) {
            return 0.0F;
        }
        float value = (ticks + partialTick) / 8.0F;
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    public static float hoverX() {
        return hoverX;
    }

    public static float hoverY() {
        return hoverY;
    }

    public static int hoverTextureX() {
        return hoverTextureX;
    }

    public static int hoverTextureY() {
        return hoverTextureY;
    }

    public static boolean hoverControl(String control) {
        return hoverControl.equals(control);
    }

    public static boolean pressControl(String control) {
        return pressTicks > 0 && pressControl.equals(control);
    }

    public static int pressTextureX() {
        return pressTextureX;
    }

    public static int pressTextureY() {
        return pressTextureY;
    }

    public static int pressTicks() {
        return pressTicks;
    }

    public static int draggingMediaId() {
        return draggingMediaId;
    }

    public static String draggingMediaName() {
        return draggingMediaName;
    }

    public static boolean draggingMedia() {
        return draggingMediaId > 0;
    }

    public static UUID selectedPointId() {
        return selectedPointId;
    }

    public static boolean selectedPoint(UUID pointId) {
        return pointId != null && pointId.equals(selectedPointId);
    }

    public static UUID draggingPointId() {
        return draggingPointId;
    }

    public static boolean draggingPoint() {
        return draggingPointId != null;
    }

    public static int draggingPointTextureX() {
        return draggingPointTextureX;
    }

    public static int draggingPointTextureY() {
        return draggingPointTextureY;
    }

    public static long revision() {
        return revision;
    }

    public static void updateHover(float x, float y) {
        targetHoverX = Math.max(-1.0F, Math.min(1.0F, x));
        targetHoverY = Math.max(-1.0F, Math.min(1.0F, y));
    }

    public static void updateHoverTarget(int textureX, int textureY, String control) {
        if (hoverTextureX == textureX && hoverTextureY == textureY && hoverControl.equals(control)) {
            return;
        }
        hoverTextureX = textureX;
        hoverTextureY = textureY;
        hoverControl = control == null ? "NONE" : control;
        revision++;
    }

    public static void pressFeedback(int textureX, int textureY, String control) {
        pressTextureX = textureX;
        pressTextureY = textureY;
        pressControl = control == null ? "NONE" : control;
        pressTicks = 8;
        revision++;
    }

    public static void beginMediaDrag(int mediaId, String mediaName) {
        draggingMediaId = Math.max(-1, mediaId);
        draggingMediaName = mediaName == null ? "" : mediaName;
        revision++;
    }

    public static void endMediaDrag() {
        if (draggingMediaId <= 0) {
            return;
        }
        draggingMediaId = -1;
        draggingMediaName = "";
        revision++;
    }

    public static void selectPoint(UUID pointId) {
        if (java.util.Objects.equals(selectedPointId, pointId)) {
            return;
        }
        selectedPointId = pointId;
        revision++;
    }

    public static void beginPointDrag(UUID pointId) {
        if (pointId == null) {
            return;
        }
        draggingPointId = pointId;
        selectPoint(pointId);
        revision++;
    }

    public static void updatePointDragPreview(int textureX, int textureY) {
        if (draggingPointId == null) {
            return;
        }
        if (draggingPointTextureX == textureX && draggingPointTextureY == textureY) {
            return;
        }
        draggingPointTextureX = textureX;
        draggingPointTextureY = textureY;
        revision++;
    }

    public static void endPointDrag() {
        if (draggingPointId == null) {
            return;
        }
        draggingPointId = null;
        draggingPointTextureX = -1;
        draggingPointTextureY = -1;
        revision++;
    }

    public static void clearHoverTarget() {
        hoverTextureX = -1;
        hoverTextureY = -1;
        hoverControl = "NONE";
        targetHoverX = 0.0F;
        targetHoverY = 0.0F;
    }

    public static void updateProjectedQuad(float topLeftX, float topLeftY, float topRightX, float topRightY,
            float bottomRightX, float bottomRightY, float bottomLeftX, float bottomLeftY, int guiWidth,
            int guiHeight) {
        projectedQuadX[0] = topLeftX;
        projectedQuadY[0] = topLeftY;
        projectedQuadX[1] = topRightX;
        projectedQuadY[1] = topRightY;
        projectedQuadX[2] = bottomRightX;
        projectedQuadY[2] = bottomRightY;
        projectedQuadX[3] = bottomLeftX;
        projectedQuadY[3] = bottomLeftY;
        projectedQuadGuiWidth = guiWidth;
        projectedQuadGuiHeight = guiHeight;
        projectedQuadNanos = System.nanoTime();
        projectedQuadValid = true;
    }

    public static boolean hasProjectedQuad(int guiWidth, int guiHeight) {
        return projectedQuadValid
                && projectedQuadGuiWidth == guiWidth
                && projectedQuadGuiHeight == guiHeight
                && System.nanoTime() - projectedQuadNanos < 250_000_000L;
    }

    public static float projectedQuadX(int index) {
        return projectedQuadX[index];
    }

    public static float projectedQuadY(int index) {
        return projectedQuadY[index];
    }

    private static void tickHoverAnimation() {
        hoverX += (targetHoverX - hoverX) * 0.32F;
        hoverY += (targetHoverY - hoverY) * 0.32F;
        if (Math.abs(hoverX) < 0.01F) {
            hoverX = 0.0F;
        }
        if (Math.abs(hoverY) < 0.01F) {
            hoverY = 0.0F;
        }
    }

    private static void tickPressFeedback() {
        if (pressTicks <= 0) {
            return;
        }
        pressTicks--;
        if (pressTicks == 0) {
            pressTextureX = -1;
            pressTextureY = -1;
            pressControl = "NONE";
            revision++;
        }
    }
}