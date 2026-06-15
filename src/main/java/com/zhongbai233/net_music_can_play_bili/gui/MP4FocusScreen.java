package com.zhongbai233.net_music_can_play_bili.gui;

import com.mojang.logging.LogUtils;
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
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** MP4 submitCustomGeometry 表面的透明输入层。 */
public class MP4FocusScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean CALIBRATION_MODE = Boolean.getBoolean("netmusic.mp4.calibration");
    private static final List<CalibrationRunSummary> CALIBRATION_REPORTS = new ArrayList<>();
    private static final int TEXTURE_W = 256;
    private static final int TEXTURE_H = 448;
        private static final int[] CALIBRATION_TEXTURE_X = { 0, 0, TEXTURE_W - 1, TEXTURE_W - 1, TEXTURE_W / 2,
            TEXTURE_W / 4, TEXTURE_W / 2, TEXTURE_W * 3 / 4, TEXTURE_W / 2 };
        private static final int[] CALIBRATION_TEXTURE_Y = { 0, TEXTURE_H - 1, 0, TEXTURE_H - 1, TEXTURE_H / 2,
            TEXTURE_H / 2, TEXTURE_H / 4, TEXTURE_H / 2, TEXTURE_H * 3 / 4 };
    private static final float DEFAULT_PORTRAIT_SCALE_MIN = 0.35F;
    private static final float DEFAULT_PORTRAIT_SCALE_MAX = 0.72F;
    private static final float DEFAULT_PORTRAIT_SCALE_HEIGHT_FACTOR = 0.001543F;
    private static final float DEFAULT_PORTRAIT_SCALE_BASE = 0.004F;
    private static final float DEFAULT_PORTRAIT_X_OFFSET_HEIGHT_FACTOR = 0.306162F;
    private static final float DEFAULT_PORTRAIT_X_OFFSET_BASE = 0.448F;
    private static final float DEFAULT_PORTRAIT_Y_OFFSET_HEIGHT_FACTOR = -0.063369F;
    private static final float DEFAULT_PORTRAIT_Y_OFFSET_BASE = -0.340F;
    private static final float PORTRAIT_RIGHT_HALF_SCREEN_X_FIX = -5.0F;
    private static final float LANDSCAPE_FINE_TUNE_X = 4.4F;
    private static final float LANDSCAPE_FINE_TUNE_Y = -4.2F;
    private static final float LANDSCAPE_TOP_HALF_TEXTURE_X_FIX = 18.0F;
    private static final int CLICK_SNAP_RADIUS = 12;
    private static final int BORDER_DRAG_PIXELS = 18;
    private static final float BORDER_DRAG_REVERSE_LIMIT = 16.0F;
    private static final float FLICK_IMPULSE_SCALE = 0.22F;
    private static final float FLICK_IMPULSE_MAX = 32.0F;
    private static final float FLICK_COMMIT_DEGREES = 24.0F;
    private static final float ORIENTATION_SNAP_DEGREES = 45.0F;
    private static final long CALIBRATION_SCROLL_TOGGLE_COOLDOWN_NANOS = 180_000_000L;
    private final InteractionHand hand;
    private boolean rotatingDevice;
    private SliderDrag draggingSlider = SliderDrag.NONE;
    private float rotationStartAngle;
    private float rotationStartDegrees;
    private boolean rotationMoved;
    private float lastDragAngle;
    private long lastDragNanos;
    private long lastCalibrationScrollToggleNanos;
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
        MP4FocusState.setCalibrationMode(CALIBRATION_MODE);
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
        if (CALIBRATION_MODE) {
            toggleCalibrationOrientation(scrollY);
            MP4Client.syncFocusedStateToServer();
            return true;
        }
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

    private void toggleCalibrationOrientation(double scrollY) {
        if (Math.abs(scrollY) < 0.01D) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastCalibrationScrollToggleNanos < CALIBRATION_SCROLL_TOGGLE_COOLDOWN_NANOS) {
            return;
        }
        lastCalibrationScrollToggleNanos = now;
        boolean targetLandscape = !MP4FocusState.landscape();
        float targetDegrees = targetLandscape ? -90.0F : 90.0F;
        MP4FocusState.animateRotationTo(targetDegrees, targetLandscape);
        LOGGER.info("MP4 校准模式滚轮旋转: scrollY={} targetLandscape={}", fmt(scrollY), targetLandscape);
    }

    private void handleLeftClick(int mouseX, int mouseY) {
        if (rotationMoved) {
            rotationMoved = false;
            return;
        }
        if (CALIBRATION_MODE) {
            handleCalibrationClick(mouseX, mouseY);
            return;
        }
        TexturePoint point = toTexturePoint(mouseX, mouseY);
        if (point == null) {
            if (CALIBRATION_MODE) {
                LOGGER.info("MP4 点击校准: screen=({}, {}) outside surface={}", mouseX, mouseY, projectedSurface());
            }
            return;
        }
        if (MP4FocusState.rotationHintVisible()) {
            if (MP4TextureHitTest.rotationHintConfirm(point)) {
                MP4FocusState.confirmRotationHint();
                MP4Client.syncFocusedStateToServer();
            }
            return;
        }
        HitResult hit = MP4TextureHitTest.hitOrSnap(point);
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
        if (CALIBRATION_MODE) {
            logCalibration(mouseX, mouseY, hit.point(), hit.hit());
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
        if (CALIBRATION_MODE) {
            return false;
        }
        ProjectedSurface surface = projectedSurface();
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
        if (CALIBRATION_MODE) {
            LOGGER.info("MP4 边框旋转开始: screen=({}, {}) landscape={}", mouseX, mouseY, MP4FocusState.landscape());
        }
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
        if (CALIBRATION_MODE) {
            LOGGER.info("MP4 边框旋转结束: absolute={} targetLandscape={} targetPortrait={} landscape={}",
                fmt(absolute), targetLandscape, targetPortrait, MP4FocusState.landscape());
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
        return activeInputQuad().pivotPoint(MP4FocusState.ROTATION_PIVOT_U, MP4FocusState.ROTATION_PIVOT_V);
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

    private void handleCalibrationClick(int mouseX, int mouseY) {
        if (MP4FocusState.calibrationComplete(width, height)) {
            MP4FocusState.resetCalibration(width, height);
        }
        int step = MP4FocusState.calibrationStep();
        if (step >= MP4FocusState.calibrationPoints()) {
            return;
        }
        int targetX = calibrationTextureX(step);
        int targetY = calibrationTextureY(step);
        MP4FocusState.recordCalibrationPoint(width, height, mouseX, mouseY);
        MP4FocusState.pressFeedback(targetX, targetY);
        boolean complete = MP4FocusState.calibrationComplete(width, height);
        LOGGER.info("MP4 九点校准采样: run={} step={}/{} window={}x{} screen=({}, {}) target=({}, {}) complete={}",
            MP4FocusState.calibrationRun() + 1, step + 1, MP4FocusState.calibrationPoints(), width, height,
            mouseX, mouseY, targetX, targetY, complete);
        if (complete) {
            SamplePoint[] samples = calibrationSamples();
            CalibrationRunSummary summary = buildCalibrationRunSummary(samples);
            CALIBRATION_REPORTS.add(summary);
            logCalibrationReport(samples, summary);
            writeCalibrationReportFiles(samples, summary);
            MP4FocusState.finishCalibrationRun();
            MP4FocusState.resetCalibration(width, height);
        }
    }

    private CalibrationRunSummary buildCalibrationRunSummary(SamplePoint[] samples) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        double totalError = 0.0D;
        double maxError = 0.0D;
        Quad defaultQuad = activeInputQuad();
        for (SamplePoint sample : samples) {
            minX = Math.min(minX, sample.screenX());
            minY = Math.min(minY, sample.screenY());
            maxX = Math.max(maxX, sample.screenX());
            maxY = Math.max(maxY, sample.screenY());
            ScreenPoint expected = defaultQuad.screenPointForTexture(sample.textureX(), sample.textureY());
            double dx = sample.screenX() - expected.x();
            double dy = sample.screenY() - expected.y();
            double error = Math.sqrt(dx * dx + dy * dy);
            totalError += error;
            maxError = Math.max(maxError, error);
        }
        float surfaceW = Math.max(1.0F, maxX - minX);
        float surfaceH = Math.max(1.0F, maxY - minY);
        float centerX = (minX + maxX) * 0.5F;
        float centerY = (minY + maxY) * 0.5F;
        float scaleX = surfaceW / (TEXTURE_W - 1.0F);
        float scaleY = surfaceH / (TEXTURE_H - 1.0F);
        float scale = (scaleX + scaleY) * 0.5F;
        float offsetX = centerX - width * 0.5F;
        float offsetY = centerY - height * 0.5F;
        return new CalibrationRunSummary(MP4FocusState.calibrationRun() + 1, MP4FocusState.landscape(), width, height,
            scaleX, scaleY, scale, offsetX, offsetY, totalError / samples.length, maxError);
    }

    private void logCalibrationReport(SamplePoint[] samples, CalibrationRunSummary summary) {
        LOGGER.info(
            "MP4 校准报告: run={} orientation={} window={}x{} fitScaleX={} fitScaleY={} fitScaleAvg={} centerOffset=({}, {}) currentDefaultErrorAvgPx={} currentDefaultErrorMaxPx={} samples={}",
            summary.run(), summary.orientationName(), summary.width(), summary.height(), fmt(summary.scaleX()), fmt(summary.scaleY()),
            fmt(summary.scale()), fmt(summary.offsetX()), fmt(summary.offsetY()), fmt(summary.avgErrorPx()),
            fmt(summary.maxErrorPx()), csvSamples(samples));
        LOGGER.info(
            "MP4 校准汇总: runs={} portrait(scale={}, xOffset={}, yOffset={}, avgErrorPx={}) landscape(scale={}, xOffset={}, yOffset={}, avgErrorPx={})",
            CALIBRATION_REPORTS.size(), fmt(averageScale(false)), Math.round(averageOffsetX(false)),
            Math.round(averageOffsetY(false)), fmt(averageError(false)), fmt(averageScale(true)),
            Math.round(averageOffsetX(true)), Math.round(averageOffsetY(true)), fmt(averageError(true)));
    }

    private String csvSamples(SamplePoint[] samples) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < samples.length; i++) {
            if (i > 0) {
                builder.append(';');
            }
            builder.append(calibrationLabel(i)).append(':')
                .append(samples[i].textureX()).append(',').append(samples[i].textureY()).append("->")
                .append(fmt(samples[i].screenX())).append(',').append(fmt(samples[i].screenY()));
        }
        return builder.toString();
    }

    private void writeCalibrationReportFiles(SamplePoint[] samples, CalibrationRunSummary summary) {
        Path directory = Minecraft.getInstance().gameDirectory.toPath();
        Path csv = directory.resolve("mp4-calibration-report.csv");
        Path text = directory.resolve("mp4-calibration-summary.txt");
        try {
            migrateLegacyCalibrationCsv(csv);
            if (Files.notExists(csv)) {
                Files.writeString(csv,
                    "run,orientation,width,height,label,textureX,textureY,screenX,screenY,fitScaleX,fitScaleY,fitScaleAvg,offsetX,offsetY,defaultErrorAvgPx,defaultErrorMaxPx\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            StringBuilder rows = new StringBuilder();
            for (int i = 0; i < samples.length; i++) {
                rows.append(summary.run()).append(',')
                    .append(summary.orientationName()).append(',')
                    .append(summary.width()).append(',')
                    .append(summary.height()).append(',')
                    .append(calibrationLabel(i)).append(',')
                    .append(samples[i].textureX()).append(',')
                    .append(samples[i].textureY()).append(',')
                    .append(fmt(samples[i].screenX())).append(',')
                    .append(fmt(samples[i].screenY())).append(',')
                    .append(fmt(summary.scaleX())).append(',')
                    .append(fmt(summary.scaleY())).append(',')
                    .append(fmt(summary.scale())).append(',')
                    .append(fmt(summary.offsetX())).append(',')
                    .append(fmt(summary.offsetY())).append(',')
                    .append(fmt(summary.avgErrorPx())).append(',')
                    .append(fmt(summary.maxErrorPx())).append('\n');
            }
            Files.writeString(csv, rows.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
            Files.writeString(text, calibrationSummaryText(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            LOGGER.warn("MP4 校准报告写入失败", ex);
        }
    }

    private void migrateLegacyCalibrationCsv(Path csv) throws IOException {
        if (Files.notExists(csv)) {
            return;
        }
        String header = Files.readString(csv, StandardCharsets.UTF_8).lines().findFirst().orElse("");
        if (header.contains("orientation")) {
            return;
        }
        Path legacy = csv.resolveSibling("mp4-calibration-report-legacy-" + System.currentTimeMillis() + ".csv");
        Files.move(csv, legacy, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("MP4 旧版校准 CSV 已备份: {}", legacy.getFileName());
    }

    private String calibrationSummaryText() {
        StringBuilder builder = new StringBuilder();
        builder.append("MP4 GUI calibration summary\n")
            .append("runs=").append(CALIBRATION_REPORTS.size()).append('\n')
            .append("active portrait fit scale=").append(fmt(DEFAULT_PORTRAIT_SCALE_HEIGHT_FACTOR))
            .append("*height+").append(fmt(DEFAULT_PORTRAIT_SCALE_BASE)).append('\n')
            .append("active portrait fit xOffset=").append(fmt(DEFAULT_PORTRAIT_X_OFFSET_HEIGHT_FACTOR))
            .append("*height+").append(fmt(DEFAULT_PORTRAIT_X_OFFSET_BASE)).append('\n')
            .append("active portrait fit yOffset=").append(fmt(DEFAULT_PORTRAIT_Y_OFFSET_HEIGHT_FACTOR))
            .append("*height+").append(fmt(DEFAULT_PORTRAIT_Y_OFFSET_BASE)).append('\n')
            .append("active portrait right-half screenX fix=").append(fmt(PORTRAIT_RIGHT_HALF_SCREEN_X_FIX)).append("px")
            .append('\n')
            .append("landscape note=screen vertical maps to texture X; screen horizontal maps to texture Y")
            .append('\n')
            .append("active landscape fine tune dx=").append(fmt(LANDSCAPE_FINE_TUNE_X))
            .append(" dy=").append(fmt(LANDSCAPE_FINE_TUNE_Y))
            .append(" topHalfTextureXFix=").append(fmt(LANDSCAPE_TOP_HALF_TEXTURE_X_FIX))
            .append("\n\n")
            .append("portrait runs=").append(countRuns(false)).append('\n')
            .append("recommended PORTRAIT_SCALE=").append(fmt(averageScale(false))).append('\n')
            .append("recommended PORTRAIT_X_OFFSET=").append(Math.round(averageOffsetX(false))).append('\n')
            .append("recommended PORTRAIT_Y_OFFSET=").append(Math.round(averageOffsetY(false))).append('\n')
            .append("portrait avg error px=").append(fmt(averageError(false))).append("\n\n")
            .append("landscape runs=").append(countRuns(true)).append('\n')
            .append("recommended LANDSCAPE_SCALE=").append(fmt(averageScale(true))).append('\n')
            .append("recommended LANDSCAPE_X_OFFSET=").append(Math.round(averageOffsetX(true))).append('\n')
            .append("recommended LANDSCAPE_Y_OFFSET=").append(Math.round(averageOffsetY(true))).append('\n')
            .append("landscape avg error px=").append(fmt(averageError(true))).append("\n\n");
        for (CalibrationRunSummary summary : CALIBRATION_REPORTS) {
            builder.append("run ").append(summary.run())
                .append(" orientation=").append(summary.orientationName())
                .append(" window=").append(summary.width()).append('x').append(summary.height())
                .append(" scale=").append(fmt(summary.scale()))
                .append(" scaleX=").append(fmt(summary.scaleX()))
                .append(" scaleY=").append(fmt(summary.scaleY()))
                .append(" offsetX=").append(fmt(summary.offsetX()))
                .append(" offsetY=").append(fmt(summary.offsetY()))
                .append(" avgErrorPx=").append(fmt(summary.avgErrorPx()))
                .append(" maxErrorPx=").append(fmt(summary.maxErrorPx()))
                .append('\n');
        }
        return builder.toString();
    }

    private long countRuns(boolean landscape) {
        return CALIBRATION_REPORTS.stream().filter(summary -> summary.landscape() == landscape).count();
    }

    private double averageScale(boolean landscape) {
        return CALIBRATION_REPORTS.stream().filter(summary -> summary.landscape() == landscape)
            .mapToDouble(summary -> summary.scale()).average()
            .orElse(defaultPortraitScale(height));
    }

    private double averageOffsetX(boolean landscape) {
        return CALIBRATION_REPORTS.stream().filter(summary -> summary.landscape() == landscape)
            .mapToDouble(summary -> summary.offsetX()).average()
            .orElse(defaultPortraitXOffset(height));
    }

    private double averageOffsetY(boolean landscape) {
        return CALIBRATION_REPORTS.stream().filter(summary -> summary.landscape() == landscape)
            .mapToDouble(summary -> summary.offsetY()).average()
            .orElse(defaultPortraitYOffset(height));
    }

    private double averageError(boolean landscape) {
        return CALIBRATION_REPORTS.stream().filter(summary -> summary.landscape() == landscape)
            .mapToDouble(summary -> summary.avgErrorPx()).average().orElse(0.0D);
    }

    private String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String calibrationLabel(int step) {
        return switch (step) {
            case 0 -> "TL";
            case 1 -> "BL";
            case 2 -> "TR";
            case 3 -> "BR";
            case 4 -> "CENTER";
            case 5 -> "LEFT_HALF_CENTER";
            case 6 -> "TOP_HALF_CENTER";
            case 7 -> "RIGHT_HALF_CENTER";
            case 8 -> "BOTTOM_HALF_CENTER";
            default -> "DONE";
        };
    }

    private void logCalibration(int mouseX, int mouseY, TexturePoint point, MP4TextureHit hit) {
        ProjectedSurface surface = projectedSurface();
        float hoverX = (mouseX - surface.centerX()) / Math.max(1.0F, surface.width() * 0.5F);
        float hoverY = (mouseY - surface.centerY()) / Math.max(1.0F, surface.height() * 0.5F);
        CalibrationPoint nearest = CalibrationPoint.nearest(point);
        LOGGER.info(
            "MP4 点击校准: screen=({}, {}) surface=[left={}, top={}, w={}, h={}, center=({}, {})] texture=({}, {}) normalized=({}/{}, {}/{}) hover=({}, {}) landscape={} hit={} nearest={} target=({}, {}) error=({}, {})",
            mouseX, mouseY, surface.left(), surface.top(), surface.width(), surface.height(), surface.centerX(),
            surface.centerY(), point.x(), point.y(), point.x(), TEXTURE_W, point.y(), TEXTURE_H,
            String.format(java.util.Locale.ROOT, "%.3f", hoverX),
            String.format(java.util.Locale.ROOT, "%.3f", hoverY), MP4FocusState.landscape(), hit, nearest.label(),
            nearest.x(), nearest.y(), point.x() - nearest.x(), point.y() - nearest.y());
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
                minecraft.getConnection().send(new com.zhongbai233.net_music_can_play_bili.network.MP4EnsureDeviceIdPacket(
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
        if (CALIBRATION_MODE && MP4FocusState.calibrationComplete(width, height)) {
            return calibratedPortraitPoint(mouseX, mouseY);
        }
        TexturePoint point = activeInputQuad().toTexturePoint(mouseX, mouseY);
        if (point == null) {
            return null;
        }
        point = adjustPortraitTexturePoint(point);
        return adjustLandscapeTexturePoint(point);
    }

    private TexturePoint adjustPortraitTexturePoint(TexturePoint point) {
        float landscapeShift = MP4FocusState.landscapeTransformProgress(1.0F)
            * MP4FocusState.LANDSCAPE_LEFT_SHIFT_FRACTION;
        if (landscapeShift > 0.0F) {
            return point;
        }
        float rightWeight = Math.max(0.0F, Math.min(1.0F, (point.x() - TEXTURE_W * 0.5F) / (TEXTURE_W * 0.5F)));
        if (rightWeight <= 0.0F) {
            return point;
        }
        float textureXFix = PORTRAIT_RIGHT_HALF_SCREEN_X_FIX / Math.max(0.001F, defaultPortraitScale(height));
        int adjustedX = Math.round(point.x() + textureXFix * rightWeight);
        return new TexturePoint(Math.max(0, Math.min(TEXTURE_W - 1, adjustedX)), point.y());
    }

    private TexturePoint adjustLandscapeTexturePoint(TexturePoint point) {
        float landscapeShift = MP4FocusState.landscapeTransformProgress(1.0F)
            * MP4FocusState.LANDSCAPE_LEFT_SHIFT_FRACTION;
        if (landscapeShift <= 0.0F) {
            return point;
        }
        float upperWeight = Math.max(0.0F, Math.min(1.0F, (TEXTURE_W * 0.5F - point.x()) / (TEXTURE_W * 0.5F)));
        if (upperWeight <= 0.0F) {
            return point;
        }
        int adjustedX = Math.round(point.x() + LANDSCAPE_TOP_HALF_TEXTURE_X_FIX * landscapeShift * upperWeight);
        return new TexturePoint(Math.max(0, Math.min(TEXTURE_W - 1, adjustedX)), point.y());
    }

    private ProjectedSurface projectedSurface() {
        if (CALIBRATION_MODE && MP4FocusState.calibrationComplete(width, height)) {
            return calibratedBounds();
        }
        return activeInputQuad().bounds();
    }

    private Quad activeInputQuad() {
        Quad portrait = defaultPortraitQuad();
        Quad rotated = portrait.rotateDegrees(MP4FocusState.deviceRotationDegrees(1.0F),
            MP4FocusState.ROTATION_PIVOT_U, MP4FocusState.ROTATION_PIVOT_V);
        float landscapeShift = MP4FocusState.landscapeTransformProgress(1.0F)
            * MP4FocusState.LANDSCAPE_LEFT_SHIFT_FRACTION;
        if (landscapeShift <= 0.0F) {
            return rotated;
        }
        float shift = -rotated.height() * landscapeShift;
        return rotated.translate(shift + LANDSCAPE_FINE_TUNE_X * landscapeShift,
            LANDSCAPE_FINE_TUNE_Y * landscapeShift);
    }

    private Quad defaultPortraitQuad() {
        float scale = defaultPortraitScale(height);
        float centerX = width * 0.5F + defaultPortraitXOffset(height);
        float centerY = height * 0.5F + defaultPortraitYOffset(height);
        float halfW = TEXTURE_W * scale * 0.5F;
        float halfH = TEXTURE_H * scale * 0.5F;
        return new Quad(
            new ScreenPoint(centerX - halfW, centerY - halfH),
            new ScreenPoint(centerX + halfW, centerY - halfH),
            new ScreenPoint(centerX + halfW, centerY + halfH),
            new ScreenPoint(centerX - halfW, centerY + halfH));
    }

    private static float defaultPortraitScale(int screenHeight) {
        float fitted = DEFAULT_PORTRAIT_SCALE_HEIGHT_FACTOR * screenHeight + DEFAULT_PORTRAIT_SCALE_BASE;
        return Math.max(DEFAULT_PORTRAIT_SCALE_MIN, Math.min(DEFAULT_PORTRAIT_SCALE_MAX, fitted));
    }

    private static float defaultPortraitXOffset(int screenHeight) {
        return DEFAULT_PORTRAIT_X_OFFSET_HEIGHT_FACTOR * screenHeight + DEFAULT_PORTRAIT_X_OFFSET_BASE;
    }

    private static float defaultPortraitYOffset(int screenHeight) {
        return DEFAULT_PORTRAIT_Y_OFFSET_HEIGHT_FACTOR * screenHeight + DEFAULT_PORTRAIT_Y_OFFSET_BASE;
    }

    private TexturePoint calibratedPortraitPoint(float mouseX, float mouseY) {
        SamplePoint[] points = calibrationSamples();
        int[] nearest = nearestSamples(points, mouseX, mouseY);
        if (nearest.length < 3) {
            return defaultPortraitQuad().toTexturePoint(mouseX, mouseY);
        }
        float totalWeight = 0.0F;
        float textureX = 0.0F;
        float textureY = 0.0F;
        for (int index : nearest) {
            SamplePoint sample = points[index];
            float dx = mouseX - sample.screenX();
            float dy = mouseY - sample.screenY();
            float weight = 1.0F / Math.max(1.0F, dx * dx + dy * dy);
            totalWeight += weight;
            textureX += (sample.textureX() + dx * localScaleX(points, index)) * weight;
            textureY += (sample.textureY() + dy * localScaleY(points, index)) * weight;
        }
        if (totalWeight <= 0.0F) {
            return null;
        }
        int x = Math.round(textureX / totalWeight);
        int y = Math.round(textureY / totalWeight);
        return new TexturePoint(Math.max(0, Math.min(TEXTURE_W - 1, x)), Math.max(0, Math.min(TEXTURE_H - 1, y)));
    }

    private ProjectedSurface calibratedBounds() {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (SamplePoint point : calibrationSamples()) {
            minX = Math.min(minX, point.screenX());
            minY = Math.min(minY, point.screenY());
            maxX = Math.max(maxX, point.screenX());
            maxY = Math.max(maxY, point.screenY());
        }
        return new ProjectedSurface(Math.round(minX), Math.round(minY), Math.round(maxX - minX),
            Math.round(maxY - minY)).inflate(8);
    }

    private SamplePoint[] calibrationSamples() {
        SamplePoint[] points = new SamplePoint[CALIBRATION_TEXTURE_X.length];
        for (int i = 0; i < points.length; i++) {
            points[i] = new SamplePoint(MP4FocusState.calibrationScreenX(i), MP4FocusState.calibrationScreenY(i),
                calibrationTextureX(i), calibrationTextureY(i));
        }
        return points;
    }

    private int calibrationTextureX(int step) {
        if (!MP4FocusState.landscape()) {
            return CALIBRATION_TEXTURE_X[Math.max(0, Math.min(CALIBRATION_TEXTURE_X.length - 1, step))];
        }
        return landscapeCalibrationTextureY(step);
    }

    private int calibrationTextureY(int step) {
        if (!MP4FocusState.landscape()) {
            return CALIBRATION_TEXTURE_Y[Math.max(0, Math.min(CALIBRATION_TEXTURE_Y.length - 1, step))];
        }
        return TEXTURE_H - 1 - landscapeCalibrationTextureX(step);
    }

    private int landscapeCalibrationTextureX(int step) {
        return switch (Math.max(0, Math.min(CALIBRATION_TEXTURE_X.length - 1, step))) {
            case 0, 1 -> 0;
            case 2, 3 -> TEXTURE_H - 1;
            case 5 -> TEXTURE_H / 4;
            case 7 -> TEXTURE_H * 3 / 4;
            default -> TEXTURE_H / 2;
        };
    }

    private int landscapeCalibrationTextureY(int step) {
        return switch (Math.max(0, Math.min(CALIBRATION_TEXTURE_Y.length - 1, step))) {
            case 0, 2 -> 0;
            case 1, 3 -> TEXTURE_W - 1;
            case 6 -> TEXTURE_W / 4;
            case 8 -> TEXTURE_W * 3 / 4;
            default -> TEXTURE_W / 2;
        };
    }

    private int[] nearestSamples(SamplePoint[] points, float x, float y) {
        int[] result = { 0, 1, 2, 3 };
        float[] distances = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
        for (int i = 0; i < points.length; i++) {
            float dx = x - points[i].screenX();
            float dy = y - points[i].screenY();
            float distance = dx * dx + dy * dy;
            for (int slot = 0; slot < result.length; slot++) {
                if (distance < distances[slot]) {
                    for (int move = result.length - 1; move > slot; move--) {
                        distances[move] = distances[move - 1];
                        result[move] = result[move - 1];
                    }
                    distances[slot] = distance;
                    result[slot] = i;
                    break;
                }
            }
        }
        return result;
    }

    private float localScaleX(SamplePoint[] points, int index) {
        SamplePoint point = points[index];
        SamplePoint best = null;
        float bestDistance = Float.MAX_VALUE;
        for (SamplePoint other : points) {
            if (other == point || other.textureX() == point.textureX()) {
                continue;
            }
            float verticalPenalty = Math.abs(other.textureY() - point.textureY()) * 3.0F;
            float distance = Math.abs(other.textureX() - point.textureX()) + verticalPenalty;
            if (distance < bestDistance) {
                best = other;
                bestDistance = distance;
            }
        }
        if (best == null || Math.abs(best.screenX() - point.screenX()) < 1.0F) {
            return 1.0F;
        }
        return (best.textureX() - point.textureX()) / (best.screenX() - point.screenX());
    }

    private float localScaleY(SamplePoint[] points, int index) {
        SamplePoint point = points[index];
        SamplePoint best = null;
        float bestDistance = Float.MAX_VALUE;
        for (SamplePoint other : points) {
            if (other == point || other.textureY() == point.textureY()) {
                continue;
            }
            float horizontalPenalty = Math.abs(other.textureX() - point.textureX()) * 3.0F;
            float distance = Math.abs(other.textureY() - point.textureY()) + horizontalPenalty;
            if (distance < bestDistance) {
                best = other;
                bestDistance = distance;
            }
        }
        if (best == null || Math.abs(best.screenY() - point.screenY()) < 1.0F) {
            return 1.0F;
        }
        return (best.textureY() - point.textureY()) / (best.screenY() - point.screenY());
    }

    private record ProjectedSurface(int left, int top, int width, int height) {
        ProjectedSurface inflate(int amount) {
            return new ProjectedSurface(left - amount, top - amount, width + amount * 2, height + amount * 2);
        }

        boolean contains(int x, int y) {
            return x >= left && y >= top && x < left + width && y < top + height;
        }

        int centerX() {
            return left + width / 2;
        }

        int centerY() {
            return top + height / 2;
        }
    }

    private record TexturePoint(int x, int y) {
    }

    private record HitResult(MP4TextureHit hit, TexturePoint point, boolean snapped) {
    }

    private record CalibrationPoint(String label, int x, int y) {
        private static final CalibrationPoint[] POINTS = {
                new CalibrationPoint("TL", 0, 0),
                new CalibrationPoint("TR", TEXTURE_W - 1, 0),
                new CalibrationPoint("BR", TEXTURE_W - 1, TEXTURE_H - 1),
                new CalibrationPoint("BL", 0, TEXTURE_H - 1),
                new CalibrationPoint("TOP_HALF_CENTER", TEXTURE_W / 2, TEXTURE_H / 4),
                new CalibrationPoint("BOTTOM_HALF_CENTER", TEXTURE_W / 2, TEXTURE_H * 3 / 4),
                new CalibrationPoint("LEFT_HALF_CENTER", TEXTURE_W / 4, TEXTURE_H / 2),
                new CalibrationPoint("RIGHT_HALF_CENTER", TEXTURE_W * 3 / 4, TEXTURE_H / 2),
                new CalibrationPoint("CENTER", TEXTURE_W / 2, TEXTURE_H / 2)
        };

        static CalibrationPoint nearest(TexturePoint point) {
            CalibrationPoint best = POINTS[0];
            int bestDistance = Integer.MAX_VALUE;
            for (CalibrationPoint candidate : POINTS) {
                int dx = point.x() - candidate.x();
                int dy = point.y() - candidate.y();
                int distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
            return best;
        }
    }

    private record ScreenPoint(float x, float y) {
    }

    private record SamplePoint(float screenX, float screenY, int textureX, int textureY) {
    }

    private record CalibrationRunSummary(int run, boolean landscape, int width, int height, double scaleX,
            double scaleY, double scale, double offsetX, double offsetY, double avgErrorPx, double maxErrorPx) {
        String orientationName() {
            return landscape ? "landscape" : "portrait";
        }
    }

    private record Quad(ScreenPoint topLeft, ScreenPoint topRight, ScreenPoint bottomRight, ScreenPoint bottomLeft) {
        Quad rotateDegrees(float degrees, float pivotU, float pivotV) {
            if (Math.abs(degrees) < 0.01F) {
                return this;
            }
            ScreenPoint pivot = pivotPoint(pivotU, pivotV);
            return new Quad(rotatePoint(topLeft, pivot, degrees), rotatePoint(topRight, pivot, degrees),
                rotatePoint(bottomRight, pivot, degrees), rotatePoint(bottomLeft, pivot, degrees));
        }

        ScreenPoint pivotPoint(float pivotU, float pivotV) {
            return sample(Math.max(0.0F, Math.min(1.0F, pivotU)), Math.max(0.0F, Math.min(1.0F, pivotV)));
        }

        Quad translate(float dx, float dy) {
            return new Quad(translatePoint(topLeft, dx, dy), translatePoint(topRight, dx, dy),
                translatePoint(bottomRight, dx, dy), translatePoint(bottomLeft, dx, dy));
        }

        float height() {
            float left = distance(topLeft, bottomLeft);
            float right = distance(topRight, bottomRight);
            return (left + right) * 0.5F;
        }

        private static ScreenPoint translatePoint(ScreenPoint point, float dx, float dy) {
            return new ScreenPoint(point.x() + dx, point.y() + dy);
        }

        private static float distance(ScreenPoint a, ScreenPoint b) {
            float dx = a.x() - b.x();
            float dy = a.y() - b.y();
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        private static ScreenPoint rotatePoint(ScreenPoint point, ScreenPoint center, float degrees) {
            float dx = point.x() - center.x();
            float dy = point.y() - center.y();
            double radians = Math.toRadians(degrees);
            float cos = (float) Math.cos(radians);
            float sin = (float) Math.sin(radians);
            return new ScreenPoint(center.x() + dx * cos + dy * sin, center.y() - dx * sin + dy * cos);
        }

        ScreenPoint screenPointForTexture(int textureX, int textureY) {
            float u = textureX / (TEXTURE_W - 1.0F);
            float v = textureY / (TEXTURE_H - 1.0F);
            return sample(u, v);
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

        static HitResult hitOrSnap(TexturePoint point) {
            MP4TextureHit direct = hit(point);
            if (direct != MP4TextureHit.NONE) {
                return new HitResult(direct, point, false);
            }
            SnapCandidate best = nearestSnap(point);
            if (best != null && best.distanceSquared() <= CLICK_SNAP_RADIUS * CLICK_SNAP_RADIUS) {
                return new HitResult(best.hit(), best.point(), true);
            }
            return new HitResult(MP4TextureHit.NONE, point, false);
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

        private static SnapCandidate nearestSnap(TexturePoint point) {
            if (MP4FocusState.landscape()) {
                return nearestLandscapeSnap(point);
            }
            SnapCandidate best = null;
            best = nearest(best, point, MP4TextureHit.QUALITY, 193, 21, 42, 22);
            best = nearest(best, point, MP4TextureHit.PREVIOUS, 34, 333, 42, 42);
            best = nearest(best, point, MP4TextureHit.PLAY, 101, 325, 54, 54);
            best = nearest(best, point, MP4TextureHit.NEXT, 180, 333, 42, 42);
            best = nearest(best, point, MP4TextureHit.PROGRESS, 28, 292, 200, 16);
            best = nearest(best, point, MP4TextureHit.VOLUME, 56, 386, 172, 24);
            best = nearest(best, point, MP4TextureHit.BILI_LOGIN, 20, 411, 42, 14);
            best = nearest(best, point, MP4TextureHit.SHUFFLE, 68, 411, 58, 14);
            best = nearest(best, point, MP4TextureHit.PLAYLIST, 136, 411, 58, 14);
            best = nearest(best, point, MP4TextureHit.LYRICS, 203, 411, 32, 14);
            if (MP4FocusState.qualityMenuOpen()) {
                for (int i = 0; i < MP4FocusState.QUALITIES.length; i++) {
                    best = nearest(best, point, qualityHit(i), 160, 68 + i * 13, 66, 11);
                }
            }
            if (MP4FocusState.subtitleMenuOpen()) {
                best = nearest(best, point, MP4TextureHit.SUBTITLE_OFF, 146, 354, 44, 14);
                best = nearest(best, point, MP4TextureHit.SUBTITLE_PRIMARY, 146, 375, 44, 14);
                best = nearest(best, point, MP4TextureHit.SUBTITLE_SECONDARY, 146, 396, 44, 14);
                best = nearest(best, point, MP4TextureHit.SUBTITLE_AI, 146, 417, 72, 14);
            }
            return best;
        }

        private static SnapCandidate nearestLandscapeSnap(TexturePoint point) {
            if (!MP4FocusState.controlsVisible()) {
                return null;
            }
            TexturePoint landscape = toLandscape(point);
            SnapCandidate best = null;
            best = nearestLandscape(best, landscape, MP4TextureHit.QUALITY, 330, 14, 52, 28);
            best = nearestLandscape(best, landscape, MP4TextureHit.PLAYLIST, 278, 16, 44, 20);
            best = nearestLandscape(best, landscape, MP4TextureHit.LYRICS, 28, 207, 48, 24);
            if (MP4FocusState.qualityMenuOpen()) {
                for (int i = 0; i < MP4FocusState.QUALITIES.length; i++) {
                    best = nearestLandscape(best, landscape, qualityHit(i), 330, 65 + i * 13, 70, 11);
                }
            }
            best = nearestLandscape(best, landscape, MP4TextureHit.PREVIOUS, 146, 204, 30, 24);
            best = nearestLandscape(best, landscape, MP4TextureHit.PLAY, 200, 198, 46, 34);
            best = nearestLandscape(best, landscape, MP4TextureHit.NEXT, 270, 204, 30, 24);
            best = nearestLandscape(best, landscape, MP4TextureHit.PROGRESS, 32, 176, 384, 18);
            best = nearestLandscape(best, landscape, MP4TextureHit.VOLUME, 340, 202, 86, 22);
            best = nearestLandscape(best, landscape, MP4TextureHit.REPEAT, 88, 213, 48, 14);
            if (MP4FocusState.subtitleMenuOpen()) {
                best = nearestLandscape(best, landscape, MP4TextureHit.SUBTITLE_OFF, 244, 72, 52, 14);
                best = nearestLandscape(best, landscape, MP4TextureHit.SUBTITLE_PRIMARY, 244, 94, 52, 14);
                best = nearestLandscape(best, landscape, MP4TextureHit.SUBTITLE_SECONDARY, 244, 116, 52, 14);
                best = nearestLandscape(best, landscape, MP4TextureHit.SUBTITLE_AI, 244, 138, 82, 14);
            }
            return best;
        }

        private static SnapCandidate nearest(SnapCandidate best, TexturePoint point, MP4TextureHit hit, int x, int y,
                int w, int h) {
            int snappedX = Math.max(x, Math.min(x + w - 1, point.x()));
            int snappedY = Math.max(y, Math.min(y + h - 1, point.y()));
            int dx = point.x() - snappedX;
            int dy = point.y() - snappedY;
            int distanceSquared = dx * dx + dy * dy;
            if (best == null || distanceSquared < best.distanceSquared()) {
                return new SnapCandidate(hit, new TexturePoint(snappedX, snappedY), distanceSquared);
            }
            return best;
        }

        private static SnapCandidate nearestLandscape(SnapCandidate best, TexturePoint landscapePoint,
                MP4TextureHit hit, int x, int y, int w, int h) {
            int snappedX = Math.max(x, Math.min(x + w - 1, landscapePoint.x()));
            int snappedY = Math.max(y, Math.min(y + h - 1, landscapePoint.y()));
            int dx = landscapePoint.x() - snappedX;
            int dy = landscapePoint.y() - snappedY;
            int distanceSquared = dx * dx + dy * dy;
            if (best == null || distanceSquared < best.distanceSquared()) {
                return new SnapCandidate(hit, fromLandscape(new TexturePoint(snappedX, snappedY)), distanceSquared);
            }
            return best;
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

        private static TexturePoint fromLandscape(TexturePoint point) {
            return new TexturePoint(point.y(), TEXTURE_H - 1 - point.x());
        }

        private record SnapCandidate(MP4TextureHit hit, TexturePoint point, int distanceSquared) {
        }

        private static boolean inside(TexturePoint p, int x, int y, int w, int h) {
            return p.x() >= x && p.y() >= y && p.x() < x + w && p.y() < y + h;
        }
    }
}
