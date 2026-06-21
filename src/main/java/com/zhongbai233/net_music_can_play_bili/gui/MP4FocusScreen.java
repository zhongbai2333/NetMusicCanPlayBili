package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.client.MP4BiliLoginOverlay;
import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

/** MP4 submitCustomGeometry 表面的透明输入层。 */
public class MP4FocusScreen extends Screen {
    private static final int TEXTURE_W = 256;
    private static final int TEXTURE_H = 448;
    private static final int BORDER_DRAG_PIXELS = 18;
    private static final float BORDER_DRAG_REVERSE_LIMIT = 16.0F;
    private static final float FLICK_IMPULSE_SCALE = 0.22F;
    private static final float FLICK_IMPULSE_MAX = 32.0F;
    private static final float FLICK_COMMIT_DEGREES = 24.0F;
    private static final float ORIENTATION_SNAP_DEGREES = 45.0F;
    private final InteractionHand hand;
    private boolean rotatingDevice;
    private SliderDrag draggingSlider = SliderDrag.NONE;
    private float rotationStartAngle;
    private float rotationStartDegrees;
    private boolean rotationMoved;
    private float lastDragAngle;
    private long lastDragNanos;
    private float dragVelocityDegreesPerSecond;

    public MP4FocusScreen(InteractionHand hand) {
        super(Component.translatable("gui.net_music_can_play_bili.mp4.focus"));
        this.hand = hand;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        MP4FocusState.activate(hand);
    }

    @Override
    public void onClose() {
        MP4BiliLoginOverlay.close();
        MP4FocusState.settleRotationForClose();
        MP4Client.flushFocusedStateToServer();
        MP4FocusState.deactivate();
        super.onClose();
    }

    @Override
    public void tick() {
        MP4FocusState.tick();
        MP4BiliLoginOverlay.tick();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        updateHover(mouseX, mouseY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        updateHover(mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean cancelled) {
        if (cancelled) {
            return false;
        }
        updateHover((int) event.x(), (int) event.y());
        if (event.button() == 1) {
            onClose();
            return true;
        }
        if (event.button() == 0) {
            if (isBorderDragStart((int) event.x(), (int) event.y())) {
                startDeviceRotation((int) event.x(), (int) event.y());
                return true;
            }
            handleLeftClick((int) event.x(), (int) event.y());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingSlider != SliderDrag.NONE) {
            updateSliderDrag((int) event.x(), (int) event.y());
            return true;
        }
        if (rotatingDevice && event.button() == 0) {
            updateDeviceRotation((int) event.x(), (int) event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingSlider != SliderDrag.NONE) {
            SliderDrag releasedSlider = draggingSlider;
            draggingSlider = SliderDrag.NONE;
            if (releasedSlider == SliderDrag.PROGRESS) {
                MP4FocusState.setScrubbingProgress(false);
                if (MP4FocusState.playing()) {
                    sendPlayback(MP4PlaybackControlPacket.Action.SEEK, currentProgressMillis());
                }
            } else if (releasedSlider == SliderDrag.VOLUME && MP4FocusState.playing()) {
                sendPlayback(MP4PlaybackControlPacket.Action.VOLUME, currentProgressMillis());
            }
            MP4Client.syncFocusedStateToServer();
            return true;
        }
        if (rotatingDevice && event.button() == 0) {
            finishDeviceRotation();
            MP4Client.syncFocusedStateToServer();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        updateHover((int) mouseX, (int) mouseY);
        TexturePoint point = toTexturePoint((int) mouseX, (int) mouseY);
        if (point != null && MP4TextureHitTest.hit(point) == MP4TextureHit.VOLUME) {
            MP4FocusState.adjustVolume(scrollY);
        } else if (MP4FocusState.playlistOpen()) {
            MP4FocusState.scrollQueue(scrollY);
        } else {
            MP4FocusState.adjustVolume(scrollY);
        }
        MP4Client.syncFocusedStateToServer();
        return true;
    }

    private void handleLeftClick(int mouseX, int mouseY) {
        if (rotationMoved) {
            rotationMoved = false;
            return;
        }
        TexturePoint point = toTexturePoint(mouseX, mouseY);
        if (point == null) {
            return;
        }
        if (MP4FocusState.rotationHintVisible()) {
            if (MP4TextureHitTest.rotationHintConfirm(point)) {
                MP4FocusState.confirmRotationHint();
                MP4Client.syncFocusedStateToServer();
            }
            return;
        }
        HitResult hit = new HitResult(MP4TextureHitTest.hit(point), point, false);
        if (MP4BiliLoginOverlay.visible() && hit.hit() != MP4TextureHit.BILI_LOGIN) {
            return;
        }
        if (hit.hit() == MP4TextureHit.PROGRESS || hit.hit() == MP4TextureHit.VOLUME) {
            draggingSlider = hit.hit() == MP4TextureHit.PROGRESS ? SliderDrag.PROGRESS : SliderDrag.VOLUME;
            if (draggingSlider == SliderDrag.PROGRESS) {
                MP4FocusState.setScrubbingProgress(true);
            }
            updateSliderValue(hit.hit(), hit.point());
        }
        handleHit(hit.hit(), hit.point());
        MP4Client.syncFocusedStateToServer();
    }

    private void updateSliderDrag(int mouseX, int mouseY) {
        updateHover(mouseX, mouseY);
        TexturePoint point = toTexturePoint(mouseX, mouseY);
        if (point == null) {
            return;
        }
        updateSliderValue(draggingSlider == SliderDrag.PROGRESS ? MP4TextureHit.PROGRESS : MP4TextureHit.VOLUME,
                point);
    }

    private void updateSliderValue(MP4TextureHit hit, TexturePoint point) {
        if (hit == MP4TextureHit.PROGRESS) {
            MP4FocusState.setMediaProgress(MP4TextureHitTest.progressAt(point));
        } else if (hit == MP4TextureHit.VOLUME) {
            MP4FocusState.setVolume(MP4TextureHitTest.volumeAt(point));
        }
    }

    private boolean isBorderDragStart(int mouseX, int mouseY) {
        ProjectedSurface surface = projectedSurface();
        if (surface == null) {
            return false;
        }
        if (!surface.inflate(BORDER_DRAG_PIXELS).contains(mouseX, mouseY) || surface.contains(mouseX, mouseY)) {
            return false;
        }
        return true;
    }

    private void startDeviceRotation(int mouseX, int mouseY) {
        float currentAbsolute = MP4FocusState.deviceRotationDegrees(1.0F);
        MP4FocusState.cancelRotationAnimation();
        rotatingDevice = true;
        rotationMoved = false;
        rotationStartAngle = pointerAngle(mouseX, mouseY);
        rotationStartDegrees = currentAbsolute;
        lastDragAngle = rotationStartAngle;
        lastDragNanos = System.nanoTime();
        dragVelocityDegreesPerSecond = 0.0F;
    }

    private void updateDeviceRotation(int mouseX, int mouseY) {
        float currentAngle = pointerAngle(mouseX, mouseY);
        updateDragVelocity(currentAngle);
        float rawDelta = -normalizeDegrees(currentAngle - rotationStartAngle);
        float delta = applyRotationDragCurve(rawDelta);
        float absolute = rotationStartDegrees + delta;
        float base = MP4FocusState.landscape() ? -90.0F : 0.0F;
        MP4FocusState.setDragRotationDegrees(absolute - base);
        rotationMoved = Math.abs(delta) > 2.0F;
        updateHover(mouseX, mouseY);
    }

    private void updateDragVelocity(float currentAngle) {
        long now = System.nanoTime();
        float dt = Math.max(0.001F, (now - lastDragNanos) / 1_000_000_000.0F);
        float rawVelocity = -normalizeDegrees(currentAngle - lastDragAngle) / dt;
        dragVelocityDegreesPerSecond = dragVelocityDegreesPerSecond * 0.65F + rawVelocity * 0.35F;
        lastDragAngle = currentAngle;
        lastDragNanos = now;
    }

    private float applyRotationDragCurve(float rawDelta) {
        boolean reverse = !MP4FocusState.landscape() ? rawDelta > 0.0F : rawDelta < 0.0F;
        if (reverse) {
            float sign = Math.signum(rawDelta);
            float magnitude = Math.abs(rawDelta);
            return sign * BORDER_DRAG_REVERSE_LIMIT * (1.0F - (float) Math.exp(-magnitude / 16.0F));
        }
        float sign = Math.signum(rawDelta);
        float magnitude = Math.abs(rawDelta);
        float curved;
        if (magnitude < 18.0F) {
            curved = magnitude * 0.86F;
        } else if (magnitude < 54.0F) {
            curved = 15.48F + (magnitude - 18.0F) * 0.72F;
        } else {
            curved = 41.40F + (magnitude - 54.0F) * 1.12F;
        }
        return sign * Math.min(curved, 100.0F);
    }

    private void finishDeviceRotation() {
        float base = MP4FocusState.landscape() ? -90.0F : 0.0F;
        float impulse = Math.max(-FLICK_IMPULSE_MAX,
                Math.min(FLICK_IMPULSE_MAX, dragVelocityDegreesPerSecond * FLICK_IMPULSE_SCALE));
        float absolute = base + MP4FocusState.dragRotationDegrees() + impulse;
        boolean targetLandscape = Math.abs(normalizeDegrees(absolute + 90.0F)) < ORIENTATION_SNAP_DEGREES;
        boolean targetPortrait = Math.abs(normalizeDegrees(absolute)) < ORIENTATION_SNAP_DEGREES;
        if (!MP4FocusState.landscape() && impulse < -FLICK_COMMIT_DEGREES) {
            targetLandscape = true;
            targetPortrait = false;
        } else if (MP4FocusState.landscape() && impulse > FLICK_COMMIT_DEGREES) {
            targetPortrait = true;
            targetLandscape = false;
        }
        if (targetLandscape) {
            float target = MP4FocusState.landscape() ? 0.0F : -90.0F;
            MP4FocusState.animateRotationTo(target, true);
        } else if (targetPortrait) {
            float target = MP4FocusState.landscape() ? 90.0F : 0.0F;
            MP4FocusState.animateRotationTo(target, false);
        } else {
            MP4FocusState.animateRotationTo(0.0F, MP4FocusState.landscape());
        }
        rotatingDevice = false;
    }

    private float pointerAngle(int mouseX, int mouseY) {
        ScreenPoint pivot = rotationPivotScreenPoint();
        double dx = mouseX - pivot.x();
        double dy = mouseY - pivot.y();
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    private ScreenPoint rotationPivotScreenPoint() {
        Quad quad = projectedInputQuadOrNull();
        if (quad != null) {
            return quad.pivotPoint(MP4FocusState.ROTATION_PIVOT_U, MP4FocusState.ROTATION_PIVOT_V);
        }
        return new ScreenPoint(width * 0.5F, height * 0.5F);
    }

    private float normalizeDegrees(float degrees) {
        float result = degrees;
        while (result <= -180.0F) {
            result += 360.0F;
        }
        while (result > 180.0F) {
            result -= 360.0F;
        }
        return result;
    }

    private void handleHit(MP4TextureHit hit, TexturePoint point) {
        switch (hit) {
            case PLAY -> {
                MP4FocusState.togglePlaying();
                sendPlayback(MP4FocusState.playing() ? MP4PlaybackControlPacket.Action.START
                        : MP4PlaybackControlPacket.Action.PAUSE, currentProgressMillis());
            }
            case PREVIOUS -> {
                MP4FocusState.previousTrack();
                if (MP4FocusState.playing()) {
                    sendPlayback(MP4PlaybackControlPacket.Action.RESTART, 0L);
                }
            }
            case NEXT -> {
                MP4FocusState.nextTrack();
                if (MP4FocusState.playing()) {
                    sendPlayback(MP4PlaybackControlPacket.Action.RESTART, 0L);
                }
            }
            case PROGRESS -> {
                MP4FocusState.setMediaProgress(MP4TextureHitTest.progressAt(point));
                if (MP4FocusState.playing() && draggingSlider != SliderDrag.PROGRESS) {
                    sendPlayback(MP4PlaybackControlPacket.Action.SEEK, currentProgressMillis());
                }
            }
            case VOLUME -> {
                MP4FocusState.setVolume(MP4TextureHitTest.volumeAt(point));
                if (MP4FocusState.playing() && draggingSlider != SliderDrag.VOLUME) {
                    sendPlayback(MP4PlaybackControlPacket.Action.VOLUME, currentProgressMillis());
                }
            }
            case SHUFFLE -> MP4FocusState.cyclePlaybackMode();
            case REPEAT -> MP4FocusState.cycleRepeat();
            case PLAYLIST -> MP4FocusState.togglePlaylist();
            case LYRICS -> MP4FocusState.toggleLyrics();
            case BILI_LOGIN -> MP4BiliLoginOverlay.toggle();
            case SUBTITLE_PRIMARY -> MP4FocusState.selectSubtitleMode(0);
            case SUBTITLE_SECONDARY -> MP4FocusState.selectSubtitleMode(1);
            case SUBTITLE_OFF -> MP4FocusState.disableSubtitle();
            case SUBTITLE_AI -> MP4FocusState.toggleSubtitleAi();
            case QUALITY -> MP4FocusState.toggleQualityMenu();
            case QUALITY_0 -> MP4FocusState.selectQualityIndex(0);
            case QUALITY_1 -> MP4FocusState.selectQualityIndex(1);
            case QUALITY_2 -> MP4FocusState.selectQualityIndex(2);
            case QUALITY_3 -> MP4FocusState.selectQualityIndex(3);
            case QUALITY_4 -> MP4FocusState.selectQualityIndex(4);
            case QUALITY_5 -> MP4FocusState.selectQualityIndex(5);
            case QUALITY_6 -> MP4FocusState.selectQualityIndex(6);
            case QUALITY_7 -> MP4FocusState.selectQualityIndex(7);
            case VIDEO_AREA -> MP4FocusState.toggleControls();
            case MEDIA -> MP4FocusState.toggleControls();
            case PLAYLIST_AREA -> MP4FocusState.selectVisibleQueueRow(MP4TextureHitTest.queueRowAt(point));
            case NONE -> {
            }
        }
    }

    private void sendPlayback(MP4PlaybackControlPacket.Action action, long targetMillis) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            java.util.UUID deviceId = minecraft.player != null
                    ? com.zhongbai233.net_music_can_play_bili.item.MP4Item.readDeviceId(
                            minecraft.player.getItemInHand(MP4FocusState.hand()))
                    : null;
            if (deviceId == null) {
                minecraft.getConnection()
                        .send(new com.zhongbai233.net_music_can_play_bili.network.MP4EnsureDeviceIdPacket(
                                MP4FocusState.hand()));
                return;
            }
            MP4Client.cacheFocusedState(deviceId);
            minecraft.getConnection().send(new MP4PlaybackControlPacket(action, MP4FocusState.selectedQueueIndex(),
                    Math.round(MP4FocusState.volume() * 1000.0F), Math.max(0L, targetMillis), deviceId));
        }
    }

    private long currentProgressMillis() {
        return Math.round(MP4FocusState.mediaProgress() * MP4FocusState.selectedTrackDurationMillis());
    }

    private void updateHover(int mouseX, int mouseY) {
        TexturePoint point = toTexturePoint(mouseX, mouseY);
        if (point == null) {
            MP4FocusState.clearHoverTarget();
            return;
        }
        float localX = point.x() / (TEXTURE_W - 1.0F) * 2.0F - 1.0F;
        float localY = point.y() / (TEXTURE_H - 1.0F) * 2.0F - 1.0F;
        MP4FocusState.updateHover(localX, localY);
        MP4FocusState.updateHoverTarget(point.x(), point.y(), MP4TextureHitTest.hit(point).name());
    }

    private TexturePoint toTexturePoint(int mouseX, int mouseY) {
        Quad quad = projectedInputQuadOrNull();
        return quad != null ? quad.toTexturePoint(mouseX, mouseY) : null;
    }

    private ProjectedSurface projectedSurface() {
        Quad quad = projectedInputQuadOrNull();
        return quad != null ? quad.bounds() : null;
    }

    private Quad projectedInputQuadOrNull() {
        if (!MP4FocusState.hasProjectedQuad(width, height)) {
            return null;
        }
        return new Quad(
                new ScreenPoint(MP4FocusState.projectedQuadX(0), MP4FocusState.projectedQuadY(0)),
                new ScreenPoint(MP4FocusState.projectedQuadX(1), MP4FocusState.projectedQuadY(1)),
                new ScreenPoint(MP4FocusState.projectedQuadX(2), MP4FocusState.projectedQuadY(2)),
                new ScreenPoint(MP4FocusState.projectedQuadX(3), MP4FocusState.projectedQuadY(3)));
    }

    private record ProjectedSurface(int left, int top, int width, int height) {
        ProjectedSurface inflate(int amount) {
            return new ProjectedSurface(left - amount, top - amount, width + amount * 2, height + amount * 2);
        }

        boolean contains(int x, int y) {
            return x >= left && y >= top && x < left + width && y < top + height;
        }

    }

    private record TexturePoint(int x, int y) {
    }

    private record HitResult(MP4TextureHit hit, TexturePoint point, boolean snapped) {
    }

    private record ScreenPoint(float x, float y) {
    }

    private record Quad(ScreenPoint topLeft, ScreenPoint topRight, ScreenPoint bottomRight, ScreenPoint bottomLeft) {
        ScreenPoint pivotPoint(float pivotU, float pivotV) {
            return sample(Math.max(0.0F, Math.min(1.0F, pivotU)), Math.max(0.0F, Math.min(1.0F, pivotV)));
        }

        TexturePoint toTexturePoint(float screenX, float screenY) {
            if (!bounds().inflate(4).contains(Math.round(screenX), Math.round(screenY))) {
                return null;
            }
            float u = 0.5F;
            float v = 0.5F;
            for (int i = 0; i < 8; i++) {
                ScreenPoint p = sample(u, v);
                float dx = p.x() - screenX;
                float dy = p.y() - screenY;
                if (Math.abs(dx) + Math.abs(dy) < 0.01F) {
                    break;
                }
                ScreenPoint du = derivativeU(v);
                ScreenPoint dv = derivativeV(u);
                float det = du.x() * dv.y() - du.y() * dv.x();
                if (Math.abs(det) < 1.0E-4F) {
                    break;
                }
                float deltaU = (dx * dv.y() - dy * dv.x()) / det;
                float deltaV = (du.x() * dy - du.y() * dx) / det;
                u = clamp(u - deltaU);
                v = clamp(v - deltaV);
            }
            ScreenPoint resolved = sample(u, v);
            float error = Math.abs(resolved.x() - screenX) + Math.abs(resolved.y() - screenY);
            if (error > 18.0F) {
                return null;
            }
            int textureX = Math.round(u * (TEXTURE_W - 1));
            int textureY = Math.round(v * (TEXTURE_H - 1));
            return new TexturePoint(textureX, textureY);
        }

        ProjectedSurface bounds() {
            float minX = Math.min(Math.min(topLeft.x(), topRight.x()), Math.min(bottomRight.x(), bottomLeft.x()));
            float minY = Math.min(Math.min(topLeft.y(), topRight.y()), Math.min(bottomRight.y(), bottomLeft.y()));
            float maxX = Math.max(Math.max(topLeft.x(), topRight.x()), Math.max(bottomRight.x(), bottomLeft.x()));
            float maxY = Math.max(Math.max(topLeft.y(), topRight.y()), Math.max(bottomRight.y(), bottomLeft.y()));
            return new ProjectedSurface(Math.round(minX), Math.round(minY), Math.round(maxX - minX),
                    Math.round(maxY - minY));
        }

        private ScreenPoint sample(float u, float v) {
            float topX = lerp(topLeft.x(), topRight.x(), u);
            float topY = lerp(topLeft.y(), topRight.y(), u);
            float bottomX = lerp(bottomLeft.x(), bottomRight.x(), u);
            float bottomY = lerp(bottomLeft.y(), bottomRight.y(), u);
            return new ScreenPoint(lerp(topX, bottomX, v), lerp(topY, bottomY, v));
        }

        private ScreenPoint derivativeU(float v) {
            float topX = topRight.x() - topLeft.x();
            float topY = topRight.y() - topLeft.y();
            float bottomX = bottomRight.x() - bottomLeft.x();
            float bottomY = bottomRight.y() - bottomLeft.y();
            return new ScreenPoint(lerp(topX, bottomX, v), lerp(topY, bottomY, v));
        }

        private ScreenPoint derivativeV(float u) {
            float leftX = bottomLeft.x() - topLeft.x();
            float leftY = bottomLeft.y() - topLeft.y();
            float rightX = bottomRight.x() - topRight.x();
            float rightY = bottomRight.y() - topRight.y();
            return new ScreenPoint(lerp(leftX, rightX, u), lerp(leftY, rightY, u));
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private static float clamp(float value) {
            return Math.max(0.0F, Math.min(1.0F, value));
        }
    }

    private enum MP4TextureHit {
        NONE,
        MEDIA,
        VIDEO_AREA,
        PLAY,
        PREVIOUS,
        NEXT,
        PROGRESS,
        VOLUME,
        SHUFFLE,
        REPEAT,
        PLAYLIST,
        LYRICS,
        BILI_LOGIN,
        SUBTITLE_PRIMARY,
        SUBTITLE_SECONDARY,
        SUBTITLE_OFF,
        SUBTITLE_AI,
        QUALITY,
        QUALITY_0,
        QUALITY_1,
        QUALITY_2,
        QUALITY_3,
        QUALITY_4,
        QUALITY_5,
        QUALITY_6,
        QUALITY_7,
        PLAYLIST_AREA
    }

    private enum SliderDrag {
        NONE,
        PROGRESS,
        VOLUME
    }

    private static final class MP4TextureHitTest {
        private MP4TextureHitTest() {
        }

        static MP4TextureHit hit(TexturePoint point) {
            return MP4FocusState.landscape() ? landscapeHit(toLandscape(point)) : portraitHit(point);
        }

        static float volumeAt(TexturePoint point) {
            if (MP4FocusState.landscape()) {
                TexturePoint landscape = toLandscape(point);
                int x0 = 348;
                int w = 70;
                return (landscape.x() - x0) / (float) Math.max(1, w);
            }
            int x0 = 64;
            int w = 154;
            return (point.x() - x0) / (float) Math.max(1, w);
        }

        static float progressAt(TexturePoint point) {
            if (MP4FocusState.landscape()) {
                TexturePoint landscape = toLandscape(point);
                int x0 = 32;
                int w = 384;
                return (landscape.x() - x0) / (float) Math.max(1, w);
            }
            int x0 = 28;
            int w = 200;
            return (point.x() - x0) / (float) Math.max(1, w);
        }

        static int queueRowAt(TexturePoint point) {
            if (MP4FocusState.landscape()) {
                TexturePoint landscape = toLandscape(point);
                if (!inside(landscape, 88, 75, 248, 43)) {
                    return -1;
                }
                return Math.max(0, Math.min(MP4FocusState.LANDSCAPE_QUEUE_VISIBLE_ROWS - 1,
                        (landscape.y() - 75) / 14));
            }
            if (!inside(point, 31, 116, TEXTURE_W - 70, 168)) {
                return -1;
            }
            return Math.max(0, Math.min(MP4FocusState.PORTRAIT_QUEUE_VISIBLE_ROWS - 1,
                    (point.y() - 116) / 28));
        }

        static boolean rotationHintConfirm(TexturePoint point) {
            if (MP4FocusState.landscape()) {
                TexturePoint landscape = toLandscape(point);
                return inside(landscape, 174, 139, 100, 26);
            }
            return inside(point, 78, 232, 100, 26);
        }

        private static MP4TextureHit portraitHit(TexturePoint p) {
            if (MP4FocusState.qualityMenuOpen()) {
                for (int i = 0; i < MP4FocusState.QUALITIES.length; i++) {
                    if (inside(p, 160, 68 + i * 13, 66, 11)) {
                        return qualityHit(i);
                    }
                }
            }
            if (MP4FocusState.subtitleMenuOpen()) {
                if (inside(p, 146, 354, 44, 14)) {
                    return MP4TextureHit.SUBTITLE_OFF;
                }
                if (inside(p, 146, 375, 44, 14)) {
                    return MP4TextureHit.SUBTITLE_PRIMARY;
                }
                if (inside(p, 146, 396, 44, 14)) {
                    return MP4TextureHit.SUBTITLE_SECONDARY;
                }
                if (inside(p, 146, 417, 72, 14)) {
                    return MP4TextureHit.SUBTITLE_AI;
                }
            }
            if (inside(p, 193, 21, 42, 22)) {
                return MP4TextureHit.QUALITY;
            }
            if (inside(p, 34, 333, 42, 42)) {
                return MP4TextureHit.PREVIOUS;
            }
            if (inside(p, 101, 325, 54, 54)) {
                return MP4TextureHit.PLAY;
            }
            if (inside(p, 180, 333, 42, 42)) {
                return MP4TextureHit.NEXT;
            }
            if (inside(p, 28, 292, 200, 16)) {
                return MP4TextureHit.PROGRESS;
            }
            if (inside(p, 56, 386, 172, 24)) {
                return MP4TextureHit.VOLUME;
            }
            if (inside(p, 20, 411, 42, 14)) {
                return MP4TextureHit.BILI_LOGIN;
            }
            if (inside(p, 68, 411, 58, 14)) {
                return MP4TextureHit.SHUFFLE;
            }
            if (inside(p, 136, 411, 58, 14)) {
                return MP4TextureHit.PLAYLIST;
            }
            if (inside(p, 203, 411, 32, 14)) {
                return MP4TextureHit.LYRICS;
            }
            if (MP4FocusState.playlistOpen() && inside(p, 18, 62, TEXTURE_W - 36, 242)) {
                return MP4TextureHit.PLAYLIST_AREA;
            }
            return MP4TextureHit.NONE;
        }

        private static MP4TextureHit landscapeHit(TexturePoint p) {
            if (MP4FocusState.controlsVisible()) {
                if (MP4FocusState.qualityMenuOpen()) {
                    for (int i = 0; i < MP4FocusState.QUALITIES.length; i++) {
                        if (inside(p, 330, 65 + i * 13, 70, 11)) {
                            return qualityHit(i);
                        }
                    }
                }
                if (MP4FocusState.subtitleMenuOpen()) {
                    if (inside(p, 244, 72, 52, 14)) {
                        return MP4TextureHit.SUBTITLE_OFF;
                    }
                    if (inside(p, 244, 94, 52, 14)) {
                        return MP4TextureHit.SUBTITLE_PRIMARY;
                    }
                    if (inside(p, 244, 116, 52, 14)) {
                        return MP4TextureHit.SUBTITLE_SECONDARY;
                    }
                    if (inside(p, 244, 138, 82, 14)) {
                        return MP4TextureHit.SUBTITLE_AI;
                    }
                }
                if (inside(p, 330, 14, 52, 28)) {
                    return MP4TextureHit.QUALITY;
                }
                if (inside(p, 278, 16, 44, 20)) {
                    return MP4TextureHit.PLAYLIST;
                }
                if (inside(p, 28, 207, 48, 24)) {
                    return MP4TextureHit.LYRICS;
                }
                if (inside(p, 146, 204, 30, 24)) {
                    return MP4TextureHit.PREVIOUS;
                }
                if (inside(p, 200, 198, 46, 34)) {
                    return MP4TextureHit.PLAY;
                }
                if (inside(p, 270, 204, 30, 24)) {
                    return MP4TextureHit.NEXT;
                }
                if (inside(p, 32, 176, 384, 18)) {
                    return MP4TextureHit.PROGRESS;
                }
                if (inside(p, 340, 202, 86, 22)) {
                    return MP4TextureHit.VOLUME;
                }
                if (inside(p, 88, 213, 48, 14)) {
                    return MP4TextureHit.REPEAT;
                }
                if (MP4FocusState.playlistOpen() && inside(p, 72, 44, 304, 76)) {
                    return MP4TextureHit.PLAYLIST_AREA;
                }
            }
            if (inside(p, 10, 10, TEXTURE_H - 20, TEXTURE_W - 20)) {
                return MP4TextureHit.VIDEO_AREA;
            }
            return MP4TextureHit.NONE;
        }

        private static MP4TextureHit qualityHit(int index) {
            return switch (Math.max(0, Math.min(7, index))) {
                case 0 -> MP4TextureHit.QUALITY_0;
                case 1 -> MP4TextureHit.QUALITY_1;
                case 2 -> MP4TextureHit.QUALITY_2;
                case 3 -> MP4TextureHit.QUALITY_3;
                case 4 -> MP4TextureHit.QUALITY_4;
                case 5 -> MP4TextureHit.QUALITY_5;
                case 6 -> MP4TextureHit.QUALITY_6;
                default -> MP4TextureHit.QUALITY_7;
            };
        }

        private static TexturePoint toLandscape(TexturePoint point) {
            return new TexturePoint(TEXTURE_H - 1 - point.y(), point.x());
        }

        private static boolean inside(TexturePoint p, int x, int y, int w, int h) {
            return p.x() >= x && p.y() >= y && p.x() < x + w && p.y() < y + h;
        }
    }
}
