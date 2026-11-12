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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // NeoForge cancels in its Pre hook before invoking this callback. The
        // double-click flag does not change which projected control was clicked.
        updateHover(event.x(), event.y());
        if (event.button() == 1) {
            onClose();
            return true;
        }
        if (event.button() == 0) {
            if (isBorderDragStart(event.x(), event.y())) {
                startDeviceRotation(event.x(), event.y());
                return true;
            }
            handleLeftClick(event.x(), event.y());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingSlider != SliderDrag.NONE) {
            updateSliderDrag(event.x(), event.y());
            return true;
        }
        if (rotatingDevice && event.button() == 0) {
            updateDeviceRotation(event.x(), event.y());
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
                sendPlayback(MP4FocusState.playing() ? MP4PlaybackControlPacket.Action.SEEK
                        : MP4PlaybackControlPacket.Action.SEEK_PAUSED, currentProgressMillis());
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
        updateHover(mouseX, mouseY);
        TexturePoint point = toTexturePoint(mouseX, mouseY);
        if (point != null && MP4TextureHitTest.hit(inputView(), point) == MP4TextureHit.VOLUME) {
            MP4FocusState.adjustVolume(scrollY);
        } else if (MP4FocusState.playlistOpen()) {
            MP4FocusState.scrollQueue(scrollY);
        } else {
            MP4FocusState.adjustVolume(scrollY);
        }
        MP4Client.syncFocusedStateToServer();
        return true;
    }

    private void handleLeftClick(double mouseX, double mouseY) {
        if (rotationMoved) {
            rotationMoved = false;
            return;
        }
        TexturePoint point = toTexturePoint(mouseX, mouseY);
        if (point == null) {
            return;
        }
        if (MP4FocusState.rotationHintVisible()) {
            if (MP4TextureHitTest.rotationHintConfirm(inputView(), point)) {
                MP4FocusState.confirmRotationHint();
                MP4Client.syncFocusedStateToServer();
            }
            return;
        }
        HitResult hit = new HitResult(MP4TextureHitTest.hit(inputView(), point), point, false);
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

    private void updateSliderDrag(double mouseX, double mouseY) {
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
            MP4FocusState.setMediaProgress(MP4TextureHitTest.progressAt(inputView(), point));
        } else if (hit == MP4TextureHit.VOLUME) {
            MP4FocusState.setVolume(MP4TextureHitTest.volumeAt(inputView(), point));
        }
    }

    private boolean isBorderDragStart(double mouseX, double mouseY) {
        ProjectedSurface surface = projectedSurface();
        if (surface == null) {
            return false;
        }
        if (!surface.inflate(BORDER_DRAG_PIXELS).contains(mouseX, mouseY) || surface.contains(mouseX, mouseY)) {
            return false;
        }
        return true;
    }

    private void startDeviceRotation(double mouseX, double mouseY) {
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

    private void updateDeviceRotation(double mouseX, double mouseY) {
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

    private float pointerAngle(double mouseX, double mouseY) {
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
                sendPlayback(MP4PlaybackControlPacket.Action.PREVIOUS, 0L);
            }
            case NEXT -> {
                MP4FocusState.nextTrack();
                sendPlayback(MP4PlaybackControlPacket.Action.NEXT, 0L);
            }
            case PROGRESS -> {
                MP4FocusState.setMediaProgress(MP4TextureHitTest.progressAt(inputView(), point));
                if (draggingSlider != SliderDrag.PROGRESS) {
                    sendPlayback(MP4FocusState.playing() ? MP4PlaybackControlPacket.Action.SEEK
                            : MP4PlaybackControlPacket.Action.SEEK_PAUSED, currentProgressMillis());
                }
            }
            case VOLUME -> {
                MP4FocusState.setVolume(MP4TextureHitTest.volumeAt(inputView(), point));
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
            case PLAYLIST_AREA -> {
                int row = MP4TextureHitTest.queueRowAt(inputView(), point);
                int index = MP4FocusState.queueScrollOffset() + row;
                if (row >= 0 && index < MP4FocusState.queueSize()
                        && index != MP4FocusState.selectedQueueIndex()) {
                    MP4FocusState.selectVisibleQueueRow(row, MP4FocusState.projectedQuadLandscape());
                    sendPlayback(MP4PlaybackControlPacket.Action.SELECT, 0L);
                }
            }
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

    private static MP4TextureHitTest.View inputView() {
        return new MP4TextureHitTest.View(MP4FocusState.projectedQuadLandscape(), MP4FocusState.controlsVisible(),
                MP4FocusState.playlistOpen(), MP4FocusState.subtitleMenuOpen(), MP4FocusState.qualityMenuOpen(),
                MP4FocusState.queueSize(), MP4FocusState.queueScrollOffset());
    }

    private void updateHover(double mouseX, double mouseY) {
        TexturePoint point = toTexturePoint(mouseX, mouseY);
        if (point == null) {
            MP4FocusState.clearHoverTarget();
            return;
        }
        float localX = point.x() / (float) TEXTURE_W * 2.0F - 1.0F;
        float localY = point.y() / (float) TEXTURE_H * 2.0F - 1.0F;
        MP4FocusState.updateHover(localX, localY);
        MP4FocusState.updateHoverTarget(Math.round(point.x()), Math.round(point.y()), MP4TextureHitTest.hit(inputView(), point).name());
    }

    private TexturePoint toTexturePoint(double mouseX, double mouseY) {
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

        boolean contains(double x, double y) {
            return x >= left && y >= top && x < left + width && y < top + height;
        }

    }

    private record HitResult(MP4TextureHit hit, TexturePoint point, boolean snapped) {
    }

    private record ScreenPoint(float x, float y) {
    }

    private record Quad(ScreenPoint topLeft, ScreenPoint topRight, ScreenPoint bottomRight, ScreenPoint bottomLeft) {
        private ProjectedInputPlane plane() {
            return new ProjectedInputPlane(topLeft.x(), topLeft.y(), topRight.x(), topRight.y(),
                    bottomRight.x(), bottomRight.y(), bottomLeft.x(), bottomLeft.y());
        }

        ScreenPoint pivotPoint(float u, float v) {
            ProjectedInputPlane.Point point = plane().sample(Math.clamp(u, 0, 1), Math.clamp(v, 0, 1));
            return new ScreenPoint((float) point.x(), (float) point.y());
        }

        TexturePoint toTexturePoint(double x, double y) {
            ProjectedInputPlane.Point point = plane().toTexturePoint(x, y, TEXTURE_W, TEXTURE_H);
            return point != null ? new TexturePoint((float) point.x(), (float) point.y()) : null;
        }

        ProjectedSurface bounds() {
            float minX = Math.min(Math.min(topLeft.x(), topRight.x()), Math.min(bottomRight.x(), bottomLeft.x()));
            float minY = Math.min(Math.min(topLeft.y(), topRight.y()), Math.min(bottomRight.y(), bottomLeft.y()));
            float maxX = Math.max(Math.max(topLeft.x(), topRight.x()), Math.max(bottomRight.x(), bottomLeft.x()));
            float maxY = Math.max(Math.max(topLeft.y(), topRight.y()), Math.max(bottomRight.y(), bottomLeft.y()));
            int left = (int) Math.floor(minX), top = (int) Math.floor(minY);
            return new ProjectedSurface(left, top, (int) Math.ceil(maxX) - left, (int) Math.ceil(maxY) - top);
        }
    }

    private enum SliderDrag {
        NONE,
        PROGRESS,
        VOLUME
    }


}
