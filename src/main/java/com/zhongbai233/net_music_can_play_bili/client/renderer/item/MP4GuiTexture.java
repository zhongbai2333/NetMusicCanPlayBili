package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import com.zhongbai233.net_music_can_play_bili.client.MP4BiliLoginOverlay;
import com.zhongbai233.net_music_can_play_bili.client.MP4ClientPlayback;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.util.UUID;
import org.slf4j.Logger;

/** 由 NativeImage 支撑的动态纹理，用于 MP4 手持图形界面表面。 */
final class MP4GuiTexture implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int WIDTH = 256;
    static final int HEIGHT = 448;
    private static final int LANDSCAPE_WIDTH = HEIGHT;
    private static final int LANDSCAPE_HEIGHT = WIDTH;
    private static final String CALIBRATION_PROPERTY = "netmusic.mp4.calibration";
    private static final Identifier WHITE_TEXTURE_ID = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "dynamic/mp4_gui_white");
    private static DynamicTexture sharedWhiteTexture;

    private final Identifier textureId;
    private DynamicTexture texture;
    private final byte[] framePixels = new byte[WIDTH * HEIGHT * 4];
    private final MP4GuiTextRenderer textRenderer = new MP4GuiTextRenderer(WIDTH, HEIGHT, LANDSCAPE_WIDTH,
            LANDSCAPE_HEIGHT);
    private final MP4GuiPixelCanvas canvas = new MP4GuiPixelCanvas(WIDTH, HEIGHT, LANDSCAPE_WIDTH,
            LANDSCAPE_HEIGHT);
    private boolean dirty = true;
    private boolean lastLandscape;
    private boolean lastVisualLandscape;
    private boolean lastPlaying;
    private boolean lastShuffle;
    private boolean lastPlaylistOpen;
    private boolean lastLyricsEnabled;
    private boolean lastSubtitleMenuOpen;
    private boolean lastQualityMenuOpen;
    private int lastSubtitleMode = -1;
    private boolean lastSubtitleAiEnabled;
    private boolean lastShowControls;
    private boolean lastControlsVisible;
    private int lastRepeatMode = -1;
    private int lastSelectedQueueIndex = -1;
    private int lastQueueScrollOffset = -1;
    private String lastQuality = "";
    private int lastVolume = -1;
    private int lastProgress = -1;
    private String lastHoverControl = "";
    private int lastHoverTextureX = -2;
    private int lastHoverTextureY = -2;
    private int lastHoverX = -2000;
    private int lastHoverY = -2000;
    private int lastCalibrationStep = -1;
    private int lastCalibrationRun = -1;
    private boolean lastCalibrationTexture;
    private int lastBiliLoginVersion = -1;
    private boolean lastBiliLoginVisible;
    private int lastTicks = -1;
    private boolean lastVideoFrameAvailable;
    private boolean lastHeadphoneLinked;
    private boolean lastRotationTransition;
    private String lastSongName = "";
    private String lastLyricLine = "";
    private String lastTranslatedLyricLine = "";
    private String lastVideoSubtitle = "";
    private UUID currentDeviceId;
    private boolean loggedCalibrationTexture;

    MP4GuiTexture(String textureKey) {
        String safeKey = textureKey == null || textureKey.isBlank() ? "fallback"
                : textureKey.toLowerCase(java.util.Locale.ROOT);
        this.textureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, "dynamic/mp4_gui_" + safeKey);
    }

    Identifier textureId() {
        return textureId(null);
    }

    Identifier textureId(UUID deviceId) {
        currentDeviceId = deviceId;
        ensureTexture();
        MP4HandheldVideoClient.update(deviceId);
        if (stateChanged()) {
            dirty = true;
        }
        if (dirty) {
            uploadGuiFrame();
        }
        return textureId;
    }

    Identifier whiteTextureId() {
        ensureWhiteTexture();
        return WHITE_TEXTURE_ID;
    }

    void warmup() {
        MP4FontManager.warmup();
        ensureTexture();
        ensureWhiteTexture();
        if (dirty) {
            uploadGuiFrame();
        }
    }

    void markDirty() {
        dirty = true;
    }

    private void ensureTexture() {
        if (texture != null) {
            return;
        }
        texture = new DynamicTexture("mp4_gui_" + textureId.getPath().replace('/', '_'), WIDTH, HEIGHT, false);
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
        dirty = true;
    }

    private void ensureWhiteTexture() {
        if (sharedWhiteTexture != null) {
            return;
        }
        sharedWhiteTexture = new DynamicTexture("mp4_gui_white", 1, 1, false);
        NativeImage image = sharedWhiteTexture.getPixels();
        if (image != null && !image.isClosed()) {
            image.setPixel(0, 0, 0xFFFFFFFF);
            sharedWhiteTexture.upload();
        }
        Minecraft.getInstance().getTextureManager().register(WHITE_TEXTURE_ID, sharedWhiteTexture);
    }

    private boolean stateChanged() {
        boolean landscape = MP4FocusState.landscape();
        boolean visualLandscape = MP4FocusState.visualLandscape(1.0F);
        boolean playing = MP4FocusState.playing();
        boolean shuffle = MP4FocusState.shuffle();
        boolean playlistOpen = MP4FocusState.playlistOpen();
        boolean lyricsEnabled = MP4FocusState.lyricsEnabled();
        boolean subtitleMenuOpen = MP4FocusState.subtitleMenuOpen();
        boolean qualityMenuOpen = MP4FocusState.qualityMenuOpen();
        int subtitleMode = MP4FocusState.subtitleMode();
        boolean subtitleAiEnabled = MP4FocusState.subtitleAiEnabled();
        boolean showControls = MP4FocusState.showControls();
        boolean controlsVisible = MP4FocusState.controlsVisible();
        int repeatMode = MP4FocusState.repeatMode();
        int selectedQueueIndex = MP4FocusState.selectedQueueIndex();
        int queueScrollOffset = MP4FocusState.queueScrollOffset();
        String quality = MP4FocusState.quality();
        int volume = Math.round(MP4FocusState.volume() * 100.0F);
        int calibrationStep = MP4FocusState.calibrationStep();
        int calibrationRun = MP4FocusState.calibrationRun();
        boolean calibrationTexture = calibrationTextureEnabled();
        int biliLoginVersion = MP4BiliLoginOverlay.version();
        boolean biliLoginVisible = MP4BiliLoginOverlay.visible();
        boolean rotationTransition = MP4FocusState.rotationInTransition(1.0F);
        boolean videoFrameAvailable = MP4HandheldVideoClient.latestFrame(currentDeviceId) != null;
        boolean headphoneLinked = MP4Client.headphoneLinked(currentDeviceId);
        int ticks = rotationTransition ? MP4FocusState.ticks() : lastTicks;
        int progress = controlsVisible ? Math.round(MP4FocusState.mediaProgress() * 1000.0F) : lastProgress;
        String songName = currentSongTitle();
        String lyricLine = MP4ClientPlayback.localLyricLine(currentDeviceId);
        String translatedLyricLine = MP4ClientPlayback.localTranslatedLyricLine(currentDeviceId);
        String videoSubtitle = MP4HandheldVideoClient.currentSubtitle(currentDeviceId);
        int hoverX = Math.round(MP4FocusState.hoverX() * 1000.0F);
        int hoverY = Math.round(MP4FocusState.hoverY() * 1000.0F);
        boolean changed = landscape != lastLandscape
                || visualLandscape != lastVisualLandscape
                || playing != lastPlaying
                || shuffle != lastShuffle
                || playlistOpen != lastPlaylistOpen
                || lyricsEnabled != lastLyricsEnabled
                || subtitleMenuOpen != lastSubtitleMenuOpen
                || qualityMenuOpen != lastQualityMenuOpen
                || subtitleMode != lastSubtitleMode
                || subtitleAiEnabled != lastSubtitleAiEnabled
                || showControls != lastShowControls
                || controlsVisible != lastControlsVisible
                || repeatMode != lastRepeatMode
                || selectedQueueIndex != lastSelectedQueueIndex
                || queueScrollOffset != lastQueueScrollOffset
                || !quality.equals(lastQuality)
                || volume != lastVolume
                || progress != lastProgress
                || !hoverControl().equals(lastHoverControl)
                || hoverX != lastHoverX
                || hoverY != lastHoverY
                || MP4FocusState.hoverTextureX() != lastHoverTextureX
                || MP4FocusState.hoverTextureY() != lastHoverTextureY
                || calibrationStep != lastCalibrationStep
                || calibrationRun != lastCalibrationRun
                || calibrationTexture != lastCalibrationTexture
                || biliLoginVersion != lastBiliLoginVersion
                || biliLoginVisible != lastBiliLoginVisible
                || ticks != lastTicks
                || videoFrameAvailable != lastVideoFrameAvailable
                || headphoneLinked != lastHeadphoneLinked
                || !songName.equals(lastSongName)
                || !lyricLine.equals(lastLyricLine)
                || !translatedLyricLine.equals(lastTranslatedLyricLine)
                || !videoSubtitle.equals(lastVideoSubtitle)
                || rotationTransition != lastRotationTransition;
        if (changed) {
            lastLandscape = landscape;
            lastVisualLandscape = visualLandscape;
            lastPlaying = playing;
            lastShuffle = shuffle;
            lastPlaylistOpen = playlistOpen;
            lastLyricsEnabled = lyricsEnabled;
            lastSubtitleMenuOpen = subtitleMenuOpen;
            lastQualityMenuOpen = qualityMenuOpen;
            lastSubtitleMode = subtitleMode;
            lastSubtitleAiEnabled = subtitleAiEnabled;
            lastShowControls = showControls;
            lastControlsVisible = controlsVisible;
            lastRepeatMode = repeatMode;
            lastSelectedQueueIndex = selectedQueueIndex;
            lastQueueScrollOffset = queueScrollOffset;
            lastQuality = quality;
            lastVolume = volume;
            lastProgress = progress;
            lastHoverControl = hoverControl();
            lastHoverX = hoverX;
            lastHoverY = hoverY;
            lastHoverTextureX = MP4FocusState.hoverTextureX();
            lastHoverTextureY = MP4FocusState.hoverTextureY();
            lastCalibrationStep = calibrationStep;
            lastCalibrationRun = calibrationRun;
            lastCalibrationTexture = calibrationTexture;
            lastBiliLoginVersion = biliLoginVersion;
            lastBiliLoginVisible = biliLoginVisible;
            lastTicks = ticks;
            lastVideoFrameAvailable = videoFrameAvailable;
            lastHeadphoneLinked = headphoneLinked;
            lastSongName = songName;
            lastLyricLine = lyricLine;
            lastTranslatedLyricLine = translatedLyricLine;
            lastVideoSubtitle = videoSubtitle;
            lastRotationTransition = rotationTransition;
        }
        return changed;
    }

    private void uploadGuiFrame() {
        if (texture == null) {
            return;
        }
        NativeImage image = texture.getPixels();
        if (image == null || image.isClosed()) {
            return;
        }
        byte[] pixels = framePixels;
        drawGui(pixels);
        uploadPixelsSafely(image, pixels);
        texture.upload();
        dirty = false;
    }

    private void uploadPixelsSafely(NativeImage image, byte[] pixels) {
        int i = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int r = pixels[i] & 0xFF;
                int g = pixels[i + 1] & 0xFF;
                int b = pixels[i + 2] & 0xFF;
                int a = pixels[i + 3] & 0xFF;
                image.setPixel(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                i += 4;
            }
        }
    }

    private void drawGui(byte[] pixels) {
        clear(pixels, 0xFF050507);
        if (calibrationTextureEnabled()) {
            if (!loggedCalibrationTexture) {
                loggedCalibrationTexture = true;
                LOGGER.info("MP4 校准纹理绘制: texture={} device={} landscape={} step={} property={} state={}",
                        textureId, currentDeviceId, MP4FocusState.landscape(), MP4FocusState.calibrationStep(),
                        System.getProperty(CALIBRATION_PROPERTY), MP4FocusState.calibrationMode());
            }
            if (MP4FocusState.landscape()) {
                drawLandscapeCalibrationTexture(pixels);
            } else {
                drawCalibrationTexture(pixels);
            }
            return;
        }
        loggedCalibrationTexture = false;
        if (MP4FocusState.visualLandscape(1.0F)) {
            drawLandscape(pixels);
        } else {
            drawPortrait(pixels);
        }
        drawRotationPlaceholder(pixels);
        drawDeviceFrame(pixels);
    }

    private boolean calibrationTextureEnabled() {
        return MP4FocusState.calibrationMode() || (MP4FocusState.active() && Boolean.getBoolean(CALIBRATION_PROPERTY));
    }

    private void drawRotationPlaceholder(byte[] pixels) {
        if (!MP4FocusState.rotationInTransition(1.0F)) {
            return;
        }
        fillRect(pixels, 7, 7, WIDTH - 14, HEIGHT - 14, 0xFF101620);
        drawRotatingRefreshGlyph(pixels, WIDTH / 2, HEIGHT / 2, 34, MP4FocusState.ticks(), 0xFFEAF2FF);
    }

    private void drawRotatingRefreshGlyph(byte[] pixels, int cx, int cy, int radius, int ticks, int color) {
        double start = ticks * 0.22D;
        for (int i = 0; i < 54; i++) {
            double angle = start + i * Math.PI * 1.55D / 54.0D;
            int x = cx + (int) Math.round(Math.cos(angle) * radius);
            int y = cy + (int) Math.round(Math.sin(angle) * radius);
            fillRect(pixels, x - 1, y - 1, 3, 3, color);
        }
        double head = start + Math.PI * 1.55D;
        int hx = cx + (int) Math.round(Math.cos(head) * radius);
        int hy = cy + (int) Math.round(Math.sin(head) * radius);
        drawArrowHead(pixels, hx, hy, head + Math.PI * 0.5D, color);
    }

    private void drawArrowHead(byte[] pixels, int x, int y, double direction, int color) {
        double left = direction + Math.PI * 0.75D;
        double right = direction - Math.PI * 0.75D;
        drawLine(pixels, x, y, x + (int) Math.round(Math.cos(left) * 12.0D),
                y + (int) Math.round(Math.sin(left) * 12.0D), color);
        drawLine(pixels, x, y, x + (int) Math.round(Math.cos(right) * 12.0D),
                y + (int) Math.round(Math.sin(right) * 12.0D), color);
    }

    private void drawLine(byte[] pixels, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0;
        int y = y0;
        while (true) {
            fillRect(pixels, x - 1, y - 1, 3, 3, color);
            if (x == x1 && y == y1) {
                break;
            }
            int e2 = err * 2;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private void drawCalibrationTexture(byte[] pixels) {
        fillRect(pixels, 0, 0, WIDTH, HEIGHT, 0xFF080A10);
        drawPixelNoise(pixels, 0, 0, WIDTH, HEIGHT, 0x12000000, 0x14FFFFFF);
        fillRectOutline(pixels, 0, 0, WIDTH, HEIGHT, 0xFFFFFFFF);
        fillRectOutline(pixels, 8, 8, WIDTH - 16, HEIGHT - 16, 0xFF3F8CFF);
        fillRect(pixels, WIDTH / 2, 0, 1, HEIGHT, 0x663F8CFF);
        fillRect(pixels, 0, HEIGHT / 2, WIDTH, 1, 0x663F8CFF);
        drawCalibrationPoint(pixels, 0, 0, "TL", 0xFFFF5A5A, 8, 10);
        drawCalibrationPoint(pixels, WIDTH - 1, 0, "TR", 0xFFFFA64D, WIDTH - 35, 10);
        drawCalibrationPoint(pixels, WIDTH - 1, HEIGHT - 1, "BR", 0xFFFFFF62, WIDTH - 35, HEIGHT - 25);
        drawCalibrationPoint(pixels, 0, HEIGHT - 1, "BL", 0xFF58FF86, 8, HEIGHT - 25);
        drawCalibrationPoint(pixels, WIDTH / 2, HEIGHT / 4, "TOP_HALF_CENTER", 0xFF65D9FF, 64, HEIGHT / 4 + 10);
        drawCalibrationPoint(pixels, WIDTH / 2, HEIGHT * 3 / 4, "BOTTOM_HALF_CENTER", 0xFFB785FF, 44,
                HEIGHT * 3 / 4 + 10);
        drawCalibrationPoint(pixels, WIDTH / 4, HEIGHT / 2, "LEFT_HALF_CENTER", 0xFFFF7EC8, 16, HEIGHT / 2 + 10);
        drawCalibrationPoint(pixels, WIDTH * 3 / 4, HEIGHT / 2, "RIGHT_HALF_CENTER", 0xFFFFD36D, 128,
                HEIGHT / 2 + 10);
        drawCalibrationPoint(pixels, WIDTH / 2, HEIGHT / 2, "CENTER", 0xFFFFFFFF, 102, HEIGHT / 2 - 22);
        drawText(pixels, 18, 28, "MP4 输入校准贴图", 0xFFFFFFFF, false);
        int step = MP4FocusState.calibrationStep();
        int total = MP4FocusState.calibrationPoints();
        drawText(pixels, 18, 44, "请按高亮顺序点击十字中心", 0xFFBBD8FF, true);
        drawText(pixels, 18, 58, "第 " + (MP4FocusState.calibrationRun() + 1) + " 轮  进度 "
                + Math.min(step, total) + "/" + total + "  当前: "
                + calibrationLabel(step), 0xFFFFF29A, true);
        drawText(pixels, 18, 72, "滚动鼠标滚轮可旋转横竖屏", 0xFF9AFFB2, true);
        drawText(pixels, 18, 86, "完成一轮后自动写报告，可切换窗口分辨率继续测", 0xFF9AFFB2, true);
        highlightCalibrationStep(pixels, step);
    }

    private void drawLandscapeCalibrationTexture(byte[] pixels) {
        fillLandscapeRect(pixels, 0, 0, LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, 0xFF080A10);
        fillLandscapeRectOutline(pixels, 0, 0, LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, 0xFFFFFFFF);
        fillLandscapeRectOutline(pixels, 8, 8, LANDSCAPE_WIDTH - 16, LANDSCAPE_HEIGHT - 16, 0xFF3F8CFF);
        fillLandscapeRect(pixels, LANDSCAPE_WIDTH / 2, 0, 1, LANDSCAPE_HEIGHT, 0x663F8CFF);
        fillLandscapeRect(pixels, 0, LANDSCAPE_HEIGHT / 2, LANDSCAPE_WIDTH, 1, 0x663F8CFF);
        drawLandscapeCalibrationPoint(pixels, 0, 0, "TL", 0xFFFF5A5A, 8, 10);
        drawLandscapeCalibrationPoint(pixels, LANDSCAPE_WIDTH - 1, 0, "TR", 0xFFFFA64D, LANDSCAPE_WIDTH - 35, 10);
        drawLandscapeCalibrationPoint(pixels, LANDSCAPE_WIDTH - 1, LANDSCAPE_HEIGHT - 1, "BR", 0xFFFFFF62,
                LANDSCAPE_WIDTH - 35, LANDSCAPE_HEIGHT - 25);
        drawLandscapeCalibrationPoint(pixels, 0, LANDSCAPE_HEIGHT - 1, "BL", 0xFF58FF86, 8,
                LANDSCAPE_HEIGHT - 25);
        drawLandscapeCalibrationPoint(pixels, LANDSCAPE_WIDTH / 2, LANDSCAPE_HEIGHT / 4, "TOP_HALF_CENTER",
                0xFF65D9FF, LANDSCAPE_WIDTH / 2 - 56, LANDSCAPE_HEIGHT / 4 + 10);
        drawLandscapeCalibrationPoint(pixels, LANDSCAPE_WIDTH / 2, LANDSCAPE_HEIGHT * 3 / 4, "BOTTOM_HALF_CENTER",
                0xFFB785FF, LANDSCAPE_WIDTH / 2 - 66, LANDSCAPE_HEIGHT * 3 / 4 + 10);
        drawLandscapeCalibrationPoint(pixels, LANDSCAPE_WIDTH / 4, LANDSCAPE_HEIGHT / 2, "LEFT_HALF_CENTER",
                0xFFFF7EC8, LANDSCAPE_WIDTH / 4 - 76, LANDSCAPE_HEIGHT / 2 + 10);
        drawLandscapeCalibrationPoint(pixels, LANDSCAPE_WIDTH * 3 / 4, LANDSCAPE_HEIGHT / 2, "RIGHT_HALF_CENTER",
                0xFFFFD36D, LANDSCAPE_WIDTH * 3 / 4 - 38, LANDSCAPE_HEIGHT / 2 + 10);
        drawLandscapeCalibrationPoint(pixels, LANDSCAPE_WIDTH / 2, LANDSCAPE_HEIGHT / 2, "CENTER", 0xFFFFFFFF,
                LANDSCAPE_WIDTH / 2 - 26, LANDSCAPE_HEIGHT / 2 - 22);
        drawLandscapeText(pixels, 18, 28, "MP4 横屏输入校准贴图", 0xFFFFFFFF, false);
        int step = MP4FocusState.calibrationStep();
        int total = MP4FocusState.calibrationPoints();
        drawLandscapeText(pixels, 18, 44, "请按高亮顺序点击十字中心", 0xFFBBD8FF, true);
        drawLandscapeText(pixels, 18, 58, "第 " + (MP4FocusState.calibrationRun() + 1) + " 轮  进度 "
                + Math.min(step, total) + "/" + total + "  当前: "
                + calibrationLabel(step), 0xFFFFF29A, true);
        drawLandscapeText(pixels, 18, 72, "滚动鼠标滚轮可旋转回竖屏", 0xFF9AFFB2, true);
        drawLandscapeText(pixels, 18, 86, "横屏下单独采样，用来定位横屏偏移", 0xFF9AFFB2, true);
        highlightLandscapeCalibrationStep(pixels, step);
    }

    private void drawLandscapeCalibrationPoint(byte[] pixels, int x, int y, String label, int color, int labelX,
            int labelY) {
        fillLandscapeRect(pixels, x - 8, y, 17, 1, color);
        fillLandscapeRect(pixels, x, y - 8, 1, 17, color);
        fillLandscapeRectOutline(pixels, x - 6, y - 6, 13, 13, color);
        fillLandscapeRect(pixels, x - 2, y - 2, 5, 5, color);
        drawLandscapeText(pixels, labelX, labelY, label, color, true);
    }

    private void highlightLandscapeCalibrationStep(byte[] pixels, int step) {
        if (step >= MP4FocusState.calibrationPoints()) {
            drawLandscapeTextCentered(pixels, LANDSCAPE_WIDTH / 2, LANDSCAPE_HEIGHT / 2 + 36, "本轮完成，下一次点击重新开始",
                    0xFF9AFFB2, false);
            return;
        }
        int x = switch (step) {
            case 0, 1 -> 0;
            case 2, 3 -> LANDSCAPE_WIDTH - 1;
            case 5 -> LANDSCAPE_WIDTH / 4;
            case 7 -> LANDSCAPE_WIDTH * 3 / 4;
            default -> LANDSCAPE_WIDTH / 2;
        };
        int y = switch (step) {
            case 0, 2 -> 0;
            case 1, 3 -> LANDSCAPE_HEIGHT - 1;
            case 6 -> LANDSCAPE_HEIGHT / 4;
            case 8 -> LANDSCAPE_HEIGHT * 3 / 4;
            default -> LANDSCAPE_HEIGHT / 2;
        };
        int pulse = 160 + (MP4FocusState.ticks() % 20) * 4;
        int color = (Math.min(255, pulse) << 24) | 0x00FFF29A;
        fillLandscapeRectOutline(pixels, x - 14, y - 14, 29, 29, color);
        fillLandscapeRectOutline(pixels, x - 20, y - 20, 41, 41, 0xAAFFFFFF);
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
            default -> "完成";
        };
    }

    private void highlightCalibrationStep(byte[] pixels, int step) {
        int x;
        int y;
        switch (step) {
            case 0 -> {
                x = 0;
                y = 0;
            }
            case 1 -> {
                x = 0;
                y = HEIGHT - 1;
            }
            case 2 -> {
                x = WIDTH - 1;
                y = 0;
            }
            case 3 -> {
                x = WIDTH - 1;
                y = HEIGHT - 1;
            }
            case 4 -> {
                x = WIDTH / 2;
                y = HEIGHT / 2;
            }
            case 5 -> {
                x = WIDTH / 4;
                y = HEIGHT / 2;
            }
            case 6 -> {
                x = WIDTH / 2;
                y = HEIGHT / 4;
            }
            case 7 -> {
                x = WIDTH * 3 / 4;
                y = HEIGHT / 2;
            }
            case 8 -> {
                x = WIDTH / 2;
                y = HEIGHT * 3 / 4;
            }
            default -> {
                return;
            }
        }
        fillRectOutline(pixels, x - 14, y - 14, 29, 29, 0xFFFFFF00);
        fillRect(pixels, x - 14, y, 29, 1, 0xFFFFFF00);
        fillRect(pixels, x, y - 14, 1, 29, 0xFFFFFF00);
    }

    private void drawCalibrationPoint(byte[] pixels, int x, int y, String label, int color, int labelX, int labelY) {
        fillRect(pixels, x - 10, y, 21, 1, color);
        fillRect(pixels, x, y - 10, 1, 21, color);
        fillRectOutline(pixels, x - 6, y - 6, 13, 13, color);
        fillRect(pixels, x - 2, y - 2, 5, 5, color);
        drawText(pixels, labelX, labelY, label, color, true);
    }

    private void drawPortrait(byte[] pixels) {
        fillRoundRect(pixels, 0, 0, WIDTH, HEIGHT, 5, 0xFF050507);
        fillRoundRect(pixels, 2, 2, WIDTH - 4, HEIGHT - 4, 5, 0xFF12141A);
        fillRect(pixels, 5, 5, WIDTH - 10, HEIGHT - 10, 0xFF20232D);
        fillRect(pixels, 7, 7, WIDTH - 14, HEIGHT - 14, 0xFF171A22);
        drawPixelNoise(pixels, 7, 7, WIDTH - 14, HEIGHT - 14, 0x08000000, 0x08FFFFFF);

        drawStatusBar(pixels, "NET MUSIC", gameTimeLabel(), true);
        drawText(pixels, 25, 45, "MP4 队列", 0xFF8CCBFF, false);
        drawText(pixels, 83, 45, MP4FocusState.playing() ? "播放中" : "已暂停",
                MP4FocusState.playing() ? 0xFF6DFFB0 : 0xFFFFC46D, false);

        fillRect(pixels, 30, 58, 196, 184, 0xFF2B3040);
        fillRect(pixels, 35, 63, 186, 174, 0xFF202634);
        if (MP4FocusState.lyricsEnabled()) {
            drawPortraitLyricsPanel(pixels, 39, 67, 178, 166);
        } else {
            drawAlbumArt(pixels, 39, 67, 178, 166);
            drawArcSparkles(pixels, 128, 150, 70);
        }

        drawTextCentered(pixels, 128, 254, currentSongTitle(), 0xFFEAF2FF, false);
        drawTextCentered(pixels, 128, 272, currentSongSubtitle(), 0xFF8B94AA, false);
        drawProgress(pixels, 28, 295, 200, 8, MP4FocusState.mediaProgress(), 0xFF32384A, 0xFF74C7FF);
        drawText(pixels, 29, 312, formatPlaybackTime(currentElapsedMillis()), 0xFF77839D, true);
        drawText(pixels, 198, 312, formatPlaybackTime(currentDurationMillis()), 0xFF77839D, true);

        drawSquareButton(pixels, 34, 333, 42, 42, 0xFF1C2230, 0xFFBBD8FF, "<");
        drawSquareButton(pixels, 101, 325, 54, 54, MP4FocusState.playing() ? 0xFF70D8FF : 0xFFFFD26E, 0xFF071018,
                MP4FocusState.playing() ? "II" : ">");
        drawSquareButton(pixels, 180, 333, 42, 42, 0xFF1C2230, 0xFFBBD8FF, ">");

        drawText(pixels, 26, 389, "VOL", 0xFF7F8DA8, true);
        drawProgress(pixels, 64, 393, 154, 6, MP4FocusState.volume(), 0xFF32384A, 0xFF7EFFC4);
        fillRect(pixels, 20, 411, 42, 14, biliLoginActive() ? 0xFF6B4E2B : 0xFF202635);
        fillRectOutline(pixels, 20, 411, 42, 14, 0xFF394154);
        fillRect(pixels, 68, 411, 58, 14, playbackModeActive() ? 0xFF365A55 : 0xFF202635);
        fillRectOutline(pixels, 68, 411, 58, 14, 0xFF394154);
        fillRect(pixels, 136, 411, 58, 14, MP4FocusState.playlistOpen() ? 0xFF234969 : 0xFF202635);
        fillRectOutline(pixels, 136, 411, 58, 14, 0xFF394154);
        fillRect(pixels, 203, 411, 32, 14, MP4FocusState.lyricsEnabled() ? 0xFF4D3568 : 0xFF202635);
        fillRectOutline(pixels, 203, 411, 32, 14, 0xFF394154);
        drawText(pixels, 28, 413, "B站", 0xFFDCEBFF, true);
        drawText(pixels, 75, 413, playbackModeLabel(), 0xFFDCEBFF, true);
        drawText(pixels, 147, 413, "列表", 0xFFDCEBFF, true);
        drawText(pixels, 211, 413, "词", 0xFFDCEBFF, true);
        drawRotationHint(pixels);

        if (MP4FocusState.playlistOpen()) {
            drawPlaylistOverlay(pixels);
        }
        if (MP4FocusState.subtitleMenuOpen()) {
            drawSubtitleMenu(pixels);
        }
        if (MP4FocusState.qualityMenuOpen()) {
            drawQualityMenu(pixels);
        }
        if (MP4BiliLoginOverlay.visible()) {
            drawBiliLoginOverlay(pixels);
        }
        drawPortraitHover(pixels);
    }

    private void drawLandscape(byte[] pixels) {
        fillLandscapeRoundRect(pixels, 0, 0, LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, 5, 0xFF050507);
        fillLandscapeRoundRect(pixels, 2, 2, LANDSCAPE_WIDTH - 4, LANDSCAPE_HEIGHT - 4, 5, 0xFF12141A);
        fillLandscapeRect(pixels, 5, 5, LANDSCAPE_WIDTH - 10, LANDSCAPE_HEIGHT - 10, 0xFF05070C);
        fillLandscapeRect(pixels, 7, 7, LANDSCAPE_WIDTH - 14, LANDSCAPE_HEIGHT - 14, 0xFF070B12);
        drawLandscapeVideoSurface(pixels, 10, 10, LANDSCAPE_WIDTH - 20, LANDSCAPE_HEIGHT - 20);

        if (MP4FocusState.controlsVisible()) {
            drawLandscapeControlOverlay(pixels);
        } else {
            drawLandscapeText(pixels, LANDSCAPE_WIDTH - 84, 18, currentVideoResolutionLabel(), 0x99DCEBFF, true);
        }

        if (MP4FocusState.playlistOpen()) {
            drawLandscapePlaylistOverlay(pixels);
        }
        if (MP4FocusState.subtitleMenuOpen() && MP4FocusState.controlsVisible()) {
            drawLandscapeSubtitleMenu(pixels);
        }
        if (MP4FocusState.qualityMenuOpen() && MP4FocusState.controlsVisible()) {
            drawLandscapeQualityMenu(pixels);
        }
        if (MP4BiliLoginOverlay.visible()) {
            drawLandscapeBiliLoginOverlay(pixels);
        }
        drawLandscapeHover(pixels);
    }

    private void drawBiliLoginOverlay(byte[] pixels) {
        fillRect(pixels, 7, 7, WIDTH - 14, 398, 0xCC05070C);
        fillRoundRect(pixels, 28, 76, 200, 260, 7, 0xEE10141E);
        fillRoundRectOutline(pixels, 28, 76, 200, 260, 7, 0x9974C7FF);
        drawTextCentered(pixels, WIDTH / 2, 94, "B站账号登录", 0xFFEAF2FF, false);
        drawTextCentered(pixels, WIDTH / 2, 112, "使用 B站 APP 扫码", 0xFFB8C5DE, true);
        drawQrImage(pixels, MP4BiliLoginOverlay.qrImage(), 58, 130, 140);
        drawTextCentered(pixels, WIDTH / 2, 286, MP4BiliLoginOverlay.statusText(), 0xFF9AFFB2, true);
        drawTextCentered(pixels, WIDTH / 2, 308, "再次点击 B站 按钮关闭/重试", 0xFF8B94AA, true);
    }

    private void drawLandscapeBiliLoginOverlay(byte[] pixels) {
        fillLandscapeRect(pixels, 7, 7, LANDSCAPE_WIDTH - 14, LANDSCAPE_HEIGHT - 14, 0xCC05070C);
        fillLandscapeRoundRect(pixels, 124, 24, 200, 208, 7, 0xEE10141E);
        fillLandscapeRectOutline(pixels, 124, 24, 200, 208, 0x9974C7FF);
        drawLandscapeTextCentered(pixels, LANDSCAPE_WIDTH / 2, 42, "B站账号登录", 0xFFEAF2FF, false);
        drawLandscapeTextCentered(pixels, LANDSCAPE_WIDTH / 2, 60, "使用 B站 APP 扫码", 0xFFB8C5DE, true);
        drawLandscapeQrImage(pixels, MP4BiliLoginOverlay.qrImage(), 154, 76, 140);
        drawLandscapeTextCentered(pixels, LANDSCAPE_WIDTH / 2, 222, MP4BiliLoginOverlay.statusText(), 0xFF9AFFB2, true);
    }

    private void drawQrImage(byte[] pixels, BufferedImage image, int x, int y, int size) {
        fillRect(pixels, x - 3, y - 3, size + 6, size + 6, 0xFFFFFFFF);
        if (image == null) {
            fillRect(pixels, x, y, size, size, 0xFF2B3040);
            drawTextCentered(pixels, x + size / 2, y + size / 2 - 5, "二维码加载中", 0xFFB8C5DE, true);
            return;
        }
        int srcW = Math.max(1, image.getWidth());
        int srcH = Math.max(1, image.getHeight());
        for (int dy = 0; dy < size; dy++) {
            int sy = Math.min(srcH - 1, dy * srcH / Math.max(1, size));
            for (int dx = 0; dx < size; dx++) {
                int sx = Math.min(srcW - 1, dx * srcW / Math.max(1, size));
                setPixel(pixels, x + dx, y + dy, 0xFF000000 | (image.getRGB(sx, sy) & 0x00FFFFFF));
            }
        }
    }

    private void drawLandscapeQrImage(byte[] pixels, BufferedImage image, int x, int y, int size) {
        fillLandscapeRect(pixels, x - 3, y - 3, size + 6, size + 6, 0xFFFFFFFF);
        if (image == null) {
            fillLandscapeRect(pixels, x, y, size, size, 0xFF2B3040);
            drawLandscapeTextCentered(pixels, x + size / 2, y + size / 2 - 5, "二维码加载中", 0xFFB8C5DE, true);
            return;
        }
        int srcW = Math.max(1, image.getWidth());
        int srcH = Math.max(1, image.getHeight());
        for (int dy = 0; dy < size; dy++) {
            int sy = Math.min(srcH - 1, dy * srcH / Math.max(1, size));
            for (int dx = 0; dx < size; dx++) {
                int sx = Math.min(srcW - 1, dx * srcW / Math.max(1, size));
                setLandscapePixel(pixels, x + dx, y + dy, 0xFF000000 | (image.getRGB(sx, sy) & 0x00FFFFFF));
            }
        }
    }

    private void drawQualityMenu(byte[] pixels) {
        fillRoundRect(pixels, 150, 46, 86, 132, 5, 0xEE10141E);
        fillRoundRectOutline(pixels, 150, 46, 86, 132, 5, 0x9974C7FF);
        drawText(pixels, 165, 53, "画质", 0xFF8CCBFF, true);
        for (int i = 0; i < MP4FocusState.QUALITIES.length; i++) {
            drawQualityOption(pixels, 160, 68 + i * 13, 66, 11, i);
        }
    }

    private void drawQualityOption(byte[] pixels, int x, int y, int w, int h, int index) {
        boolean selected = MP4FocusState.quality().equals(MP4FocusState.QUALITIES[index]);
        fillRoundRect(pixels, x, y, w, h, 3, selected ? 0xFF263B5F : 0xFF171D2A);
        fillRectOutline(pixels, x, y, w, h, selected ? 0xFF74C7FF : 0xFF394154);
        drawText(pixels, x + 6, y + 1, MP4FocusState.QUALITIES[index], selected ? 0xFFEAF2FF : 0xFFB8C5DE, true);
    }

    private void drawLandscapeQualityMenu(byte[] pixels) {
        fillLandscapeRoundRect(pixels, 318, 42, 94, 132, 5, 0xEE10141E);
        fillLandscapeRectOutline(pixels, 318, 42, 94, 132, 0x9974C7FF);
        drawLandscapeText(pixels, 350, 50, "画质", 0xFF8CCBFF, true);
        for (int i = 0; i < MP4FocusState.QUALITIES.length; i++) {
            drawLandscapeQualityOption(pixels, 330, 65 + i * 13, 70, 11, i);
        }
    }

    private void drawLandscapeQualityOption(byte[] pixels, int x, int y, int w, int h, int index) {
        boolean selected = MP4FocusState.quality().equals(MP4FocusState.QUALITIES[index]);
        fillLandscapeRoundRect(pixels, x, y, w, h, 3, selected ? 0xFF263B5F : 0xFF171D2A);
        fillLandscapeRectOutline(pixels, x, y, w, h, selected ? 0xFF74C7FF : 0xFF394154);
        drawLandscapeText(pixels, x + 7, y + 1, MP4FocusState.QUALITIES[index],
                selected ? 0xFFEAF2FF : 0xFFB8C5DE, true);
    }

    private void drawSubtitleMenu(byte[] pixels) {
        fillRoundRect(pixels, 132, 322, 96, 118, 5, 0xEE10141E);
        fillRoundRectOutline(pixels, 132, 322, 96, 118, 5, 0x994D6FFF);
        drawText(pixels, 145, 328, "字幕设置", 0xFF8CCBFF, true);
        drawSubtitleOption(pixels, 146, 354, 44, 14, "关", !MP4FocusState.lyricsEnabled(), false);
        drawSubtitleOption(pixels, 146, 375, 44, 14, "主",
                MP4FocusState.lyricsEnabled() && MP4FocusState.subtitlePrimaryMode(), false);
        drawSubtitleOption(pixels, 146, 396, 44, 14, "副",
                MP4FocusState.lyricsEnabled() && !MP4FocusState.subtitlePrimaryMode(), false);
        drawSubtitleOption(pixels, 146, 417, 72, 14, "AI字幕", MP4FocusState.subtitleAiEnabled(), true);
    }

    private void drawSubtitleOption(byte[] pixels, int x, int y, int w, int h, String label, boolean selected,
            boolean checkbox) {
        fillRoundRect(pixels, x, y, w, h, 3, selected ? 0xFF263B5F : 0xFF171D2A);
        fillRectOutline(pixels, x, y, h, h, selected ? 0xFF74C7FF : 0xFF5A6378);
        if (selected) {
            if (checkbox) {
                drawText(pixels, x + 3, y + 2, "✓", 0xFFEAF2FF, true);
            } else {
                fillRect(pixels, x + 4, y + 4, h - 8, h - 8, 0xFF74C7FF);
            }
        }
        drawText(pixels, x + h + 5, y + 2, label, 0xFFEAF2FF, true);
    }

    private void drawLandscapeSubtitleMenu(byte[] pixels) {
        fillLandscapeRoundRect(pixels, 232, 48, 108, 114, 5, 0xEE10141E);
        fillLandscapeRectOutline(pixels, 232, 48, 108, 114, 0x994D6FFF);
        drawLandscapeText(pixels, 248, 55, "字幕设置", 0xFF8CCBFF, true);
        drawLandscapeSubtitleOption(pixels, 244, 72, 52, 14, "关", !MP4FocusState.lyricsEnabled(), false);
        drawLandscapeSubtitleOption(pixels, 244, 94, 52, 14, "主",
                MP4FocusState.lyricsEnabled() && MP4FocusState.subtitlePrimaryMode(), false);
        drawLandscapeSubtitleOption(pixels, 244, 116, 52, 14, "副",
                MP4FocusState.lyricsEnabled() && !MP4FocusState.subtitlePrimaryMode(), false);
        drawLandscapeSubtitleOption(pixels, 244, 138, 82, 14, "AI字幕", MP4FocusState.subtitleAiEnabled(), true);
    }

    private void drawLandscapeSubtitleOption(byte[] pixels, int x, int y, int w, int h, String label, boolean selected,
            boolean checkbox) {
        fillLandscapeRoundRect(pixels, x, y, w, h, 3, selected ? 0xFF263B5F : 0xFF171D2A);
        fillLandscapeRectOutline(pixels, x, y, h, h, selected ? 0xFF74C7FF : 0xFF5A6378);
        if (selected) {
            if (checkbox) {
                drawLandscapeText(pixels, x + 3, y + 2, "✓", 0xFFEAF2FF, true);
            } else {
                fillLandscapeRect(pixels, x + 4, y + 4, h - 8, h - 8, 0xFF74C7FF);
            }
        }
        drawLandscapeText(pixels, x + h + 5, y + 2, label, 0xFFEAF2FF, true);
    }

    private void drawLandscapeVideoSurface(byte[] pixels, int x, int y, int w, int h) {
        MP4HandheldVideoClient.VideoFrame frame = MP4HandheldVideoClient.latestFrame(currentDeviceId);
        if (MP4FocusState.videoEnabled() && MP4FocusState.playing() && frame != null) {
            fillLandscapeRect(pixels, x, y, w, h, 0x00000000);
        } else {
            drawLandscapeSteppedGradient(pixels, x, y, w, h, 0xFF07111F, 0xFF130B1D, 64);
            fillLandscapeCircle(pixels, x + w / 3, y + h / 3, 72, 0x220C4B80);
            fillLandscapeCircle(pixels, x + w * 2 / 3, y + h / 2, 84, 0x221A1050);
            for (int i = 0; i < 34; i++) {
                int px = x + 12 + (i * 37) % Math.max(1, w - 24);
                int py = y + 10 + (i * 23) % Math.max(1, h - 20);
                blendLandscapePixel(pixels, px, py, i % 3 == 0 ? 0x558CCBFF : 0x336DFFB0);
            }
        }
        boolean waitingForFrame = MP4FocusState.videoEnabled() && MP4FocusState.playing() && frame == null;
        if (!MP4FocusState.playing() || MP4FocusState.controlsVisible() || waitingForFrame) {
            fillLandscapeCircle(pixels, x + w / 2, y + h / 2 - 5, 42, 0x55050710);
            if (MP4FocusState.playing()) {
                drawLandscapePauseGlyph(pixels, x + w / 2, y + h / 2 - 5, 0xCCEAF2FF);
            } else {
                drawLandscapePlayGlyph(pixels, x + w / 2, y + h / 2 - 5, 0xCCEAF2FF);
            }
            drawLandscapeTextCentered(pixels, x + w / 2, y + h / 2 + 41,
                    MP4FocusState.videoEnabled() ? MP4HandheldVideoClient.statusText(currentDeviceId) : "视频线路已关闭",
                    0xCCFFFFFF, true);
        }
        String subtitle = lastVideoSubtitle;
        if (!subtitle.isBlank() && MP4FocusState.lyricsEnabled()) {
            drawLandscapeTextCentered(pixels, x + w / 2, MP4FocusState.controlsVisible() ? 151 : 199,
                    subtitle, 0xEEDCEBFF, true);
        }
    }

    private String currentVideoResolutionLabel() {
        String resolution = MP4HandheldVideoClient.currentResolutionLabel(currentDeviceId);
        return resolution.isBlank() ? MP4FocusState.quality() : resolution;
    }

    private void drawLandscapeControlOverlay(byte[] pixels) {
        fillLandscapeRect(pixels, 14, 14, 420, 26, 0x66000000);
        drawLandscapeText(pixels, 25, 21, "BiliBili 视频投影预览", 0xFFEAF2FF, false);
        drawLandscapeText(pixels, 165, 23, MP4FocusState.playing() ? "LIVE PLAYBACK" : "PAUSED",
                MP4FocusState.playing() ? 0xFF6DFFB0 : 0xFFFFC46D, true);
        fillLandscapeRect(pixels, 278, 18, 44, 16, MP4FocusState.playlistOpen() ? 0xCC234969 : 0xAA202635);
        fillLandscapeRectOutline(pixels, 278, 18, 44, 16, 0xFF394154);
        drawLandscapeText(pixels, 289, 20, "列表", 0xFFDCEBFF, true);
        drawLandscapeText(pixels, 333, 22, MP4FocusState.quality(), 0xFFDCEBFF, true);
        drawLandscapeText(pixels, 389, 22, gameTimeLabel(), 0xFF89D6FF, true);
        drawLandscapeRotationHint(pixels);

        fillLandscapeRoundRect(pixels, 16, 171, LANDSCAPE_WIDTH - 32, 66, 5, 0xAA05070C);
        drawLandscapeProgress(pixels, 32, 184, 384, 6, MP4FocusState.mediaProgress(), 0xFF32384A, 0xFF74C7FF);
        drawLandscapeText(pixels, 32, 196, formatPlaybackTime(currentElapsedMillis()), 0xFFB8C5DE, true);
        drawLandscapeText(pixels, 389, 196, formatPlaybackTime(currentDurationMillis()), 0xFFB8C5DE, true);

        drawLandscapeSquareButton(pixels, 146, 204, 30, 24, 0xAA1C2230, 0xFFBBD8FF, "<");
        drawLandscapePlayPauseButton(pixels, 200, 198, 46, 34);
        drawLandscapeSquareButton(pixels, 270, 204, 30, 24, 0xAA1C2230, 0xFFBBD8FF, ">");

        drawLandscapeText(pixels, 319, 208, "VOL", 0xFFB8C5DE, true);
        drawLandscapeProgress(pixels, 348, 212, 70, 5, MP4FocusState.volume(), 0xFF32384A, 0xFF7EFFC4);
        fillLandscapeRect(pixels, 28, 213, 48, 14, MP4FocusState.subtitleMenuOpen() ? 0xAA4D3568 : 0xAA202635);
        fillLandscapeRectOutline(pixels, 28, 213, 48, 14, 0xFF394154);
        drawLandscapeText(pixels, 40, 215, "字幕", 0xFFDCEBFF, true);
        fillLandscapeRect(pixels, 88, 213, 48, 14, MP4FocusState.repeatMode() > 0 ? 0xAA615235 : 0xAA202635);
        fillLandscapeRectOutline(pixels, 88, 213, 48, 14, 0xFF394154);
        drawLandscapeText(pixels, 100, 215, "循环", 0xFFDCEBFF, true);
    }

    private void drawLandscapePlayPauseButton(byte[] pixels, int x, int y, int w, int h) {
        fillLandscapeRect(pixels, x + 3, y + 4, w, h, 0x55000000);
        fillLandscapeRect(pixels, x, y, w, h, MP4FocusState.playing() ? 0xDD70D8FF : 0xDDFFD26E);
        fillLandscapeRectOutline(pixels, x, y, w, h, 0xFF394154);
        fillLandscapeRect(pixels, x + 3, y + 3, w - 6, 2, 0x44FFFFFF);
        fillLandscapeRect(pixels, x + 3, y + h - 5, w - 6, 2, 0x66000000);
        if (MP4FocusState.playing()) {
            drawLandscapePauseGlyph(pixels, x + w / 2, y + h / 2, 0xFF071018);
        } else {
            drawLandscapePlayGlyph(pixels, x + w / 2, y + h / 2, 0xFF071018);
        }
    }

    private void drawLandscapePlayGlyph(byte[] pixels, int cx, int cy, int color) {
        for (int dx = -7; dx <= 9; dx++) {
            int halfHeight = Math.max(1, (9 - dx) * 12 / 16);
            fillLandscapeRect(pixels, cx + dx, cy - halfHeight, 1, halfHeight * 2 + 1, color);
        }
    }

    private void drawLandscapePauseGlyph(byte[] pixels, int cx, int cy, int color) {
        fillLandscapeRect(pixels, cx - 8, cy - 11, 5, 22, color);
        fillLandscapeRect(pixels, cx + 3, cy - 11, 5, 22, color);
    }

    private void drawLandscapePlaylistOverlay(byte[] pixels) {
        fillLandscapeRoundRect(pixels, 72, 44, 304, 76, 5, 0xEE10141E);
        drawLandscapeText(pixels, 88, 56, "播放列表 / 视频源", 0xFF8CCBFF, false);
        drawLandscapeText(pixels, 290, 57, queuePageLabel(),
                0xFF8B94AA, true);
        if (MP4FocusState.queueSize() <= 0) {
            drawLandscapeText(pixels, 146, 88, "把 NetMusic 唱片放进 MP4", 0xFF8B94AA, true);
            return;
        }
        for (int i = 0; i < MP4FocusState.LANDSCAPE_QUEUE_VISIBLE_ROWS; i++) {
            int index = MP4FocusState.queueScrollOffset() + i;
            if (index >= MP4FocusState.queueSize()) {
                break;
            }
            int y = 75 + i * 14;
            int color = index == MP4FocusState.selectedQueueIndex() ? 0xFF263B5F : 0xFF171D2A;
            fillLandscapeRoundRect(pixels, 88, y, 248, 12, 3, color);
            fillLandscapeCircle(pixels, 98, y + 6, 3,
                    index == MP4FocusState.selectedQueueIndex() ? 0xFF74C7FF : 0xFF374158);
            drawLandscapeMarqueeText(pixels, 108, y + 1, 222, queueTitle(index), 0xFFEAF2FF, true);
        }
        drawLandscapeQueueScrollbar(pixels, 347, 75, 5, 40);
    }

    private String hoverControl() {
        return MP4FocusState.hoverControlName();
    }

    private void drawPortraitHover(byte[] pixels) {
        String control = hoverControl();
        int pulse = 48 + (MP4FocusState.ticks() % 24) * 3;
        int color = (Math.min(140, pulse) << 24) | 0x00FFFFFF;
        switch (control) {
            case "PREVIOUS" -> fillRectOutline(pixels, 32, 331, 46, 46, color);
            case "PLAY" -> fillRectOutline(pixels, 99, 323, 58, 58, color);
            case "NEXT" -> fillRectOutline(pixels, 178, 331, 46, 46, color);
            case "PROGRESS" -> fillRectOutline(pixels, 26, 291, 204, 18, color);
            case "VOLUME" -> fillRectOutline(pixels, 62, 388, 158, 12, color);
            case "BILI_LOGIN" -> fillRectOutline(pixels, 18, 409, 46, 18, color);
            case "SHUFFLE" -> fillRectOutline(pixels, 66, 409, 62, 18, color);
            case "PLAYLIST" -> fillRectOutline(pixels, 134, 409, 62, 18, color);
            case "LYRICS" -> fillRectOutline(pixels, 201, 409, 36, 18, color);
            case "SUBTITLE_OFF" -> fillRectOutline(pixels, 144, 352, 48, 18, color);
            case "SUBTITLE_PRIMARY" -> fillRectOutline(pixels, 144, 373, 48, 18, color);
            case "SUBTITLE_SECONDARY" -> fillRectOutline(pixels, 144, 394, 48, 18, color);
            case "SUBTITLE_AI" -> fillRectOutline(pixels, 144, 415, 76, 18, color);
            case "QUALITY" -> fillRectOutline(pixels, 191, 19, 46, 26, color);
            case "QUALITY_0" -> fillRectOutline(pixels, 158, 66, 70, 15, color);
            case "QUALITY_1" -> fillRectOutline(pixels, 158, 79, 70, 15, color);
            case "QUALITY_2" -> fillRectOutline(pixels, 158, 92, 70, 15, color);
            case "QUALITY_3" -> fillRectOutline(pixels, 158, 105, 70, 15, color);
            case "QUALITY_4" -> fillRectOutline(pixels, 158, 118, 70, 15, color);
            case "QUALITY_5" -> fillRectOutline(pixels, 158, 131, 70, 15, color);
            case "QUALITY_6" -> fillRectOutline(pixels, 158, 144, 70, 15, color);
            case "QUALITY_7" -> fillRectOutline(pixels, 158, 157, 70, 15, color);
            case "PLAYLIST_AREA" -> fillRectOutline(pixels, 22, 70, WIDTH - 44, 226, color);
            default -> {
            }
        }
    }

    private void drawLandscapeHover(byte[] pixels) {
        String control = hoverControl();
        int pulse = 54 + (MP4FocusState.ticks() % 24) * 3;
        int color = (Math.min(150, pulse) << 24) | 0x00FFFFFF;
        switch (control) {
            case "PREVIOUS" -> fillLandscapeRectOutline(pixels, 144, 202, 34, 28, color);
            case "PLAY" -> fillLandscapeRectOutline(pixels, 198, 196, 50, 38, color);
            case "NEXT" -> fillLandscapeRectOutline(pixels, 268, 202, 34, 28, color);
            case "PROGRESS" -> fillLandscapeRectOutline(pixels, 30, 176, 388, 16, color);
            case "VOLUME" -> fillLandscapeRectOutline(pixels, 346, 207, 74, 14, color);
            case "REPEAT" -> fillLandscapeRectOutline(pixels, 86, 211, 52, 18, color);
            case "PLAYLIST" -> fillLandscapeRectOutline(pixels, 276, 16, 48, 20, color);
            case "QUALITY" -> fillLandscapeRectOutline(pixels, 328, 16, 56, 20, color);
            case "LYRICS" -> fillLandscapeRectOutline(pixels, 26, 211, 52, 18, color);
            case "SUBTITLE_OFF" -> fillLandscapeRectOutline(pixels, 242, 70, 56, 18, color);
            case "SUBTITLE_PRIMARY" -> fillLandscapeRectOutline(pixels, 242, 92, 56, 18, color);
            case "SUBTITLE_SECONDARY" -> fillLandscapeRectOutline(pixels, 242, 114, 56, 18, color);
            case "SUBTITLE_AI" -> fillLandscapeRectOutline(pixels, 242, 136, 86, 18, color);
            case "QUALITY_0" -> fillLandscapeRectOutline(pixels, 328, 63, 74, 15, color);
            case "QUALITY_1" -> fillLandscapeRectOutline(pixels, 328, 76, 74, 15, color);
            case "QUALITY_2" -> fillLandscapeRectOutline(pixels, 328, 89, 74, 15, color);
            case "QUALITY_3" -> fillLandscapeRectOutline(pixels, 328, 102, 74, 15, color);
            case "QUALITY_4" -> fillLandscapeRectOutline(pixels, 328, 115, 74, 15, color);
            case "QUALITY_5" -> fillLandscapeRectOutline(pixels, 328, 128, 74, 15, color);
            case "QUALITY_6" -> fillLandscapeRectOutline(pixels, 328, 141, 74, 15, color);
            case "QUALITY_7" -> fillLandscapeRectOutline(pixels, 328, 154, 74, 15, color);
            case "PLAYLIST_AREA" -> fillLandscapeRectOutline(pixels, 72, 44, 304, 76, color);
            default -> {
            }
        }
    }

    private void drawStatusBar(byte[] pixels, String left, String right, boolean portrait) {
        drawText(pixels, 28, 27, left, 0xFFB8C5DE, true);
        int timeX = portrait ? 194 : 209;
        drawText(pixels, timeX, 27, right, 0xFFB8C5DE, true);
        if (MP4Client.headphoneLinked(currentDeviceId)) {
            drawBluetoothStatusGlyph(pixels, timeX + 37, 28, portrait);
        }
        fillRoundRect(pixels, 113, 26, 30, 8, 4, 0xFF050507);
    }

    private void drawBluetoothStatusGlyph(byte[] pixels, int x, int y, boolean portrait) {
        int glow = 0x554AA8FF;
        int color = 0xFF54B6FF;
        int highlight = 0xFFB9E8FF;
        if (portrait) {
            fillRect(pixels, x - 1, y, 3, 10, glow);
            drawLine(pixels, x, y, x, y + 10, color);
            drawLine(pixels, x, y, x + 5, y + 3, color);
            drawLine(pixels, x + 5, y + 3, x, y + 6, color);
            drawLine(pixels, x, y + 6, x + 5, y + 9, color);
            drawLine(pixels, x + 5, y + 9, x, y + 12, color);
            drawLine(pixels, x - 5, y + 2, x, y + 6, highlight);
            drawLine(pixels, x - 5, y + 10, x, y + 6, highlight);
        } else {
            fillLandscapeRect(pixels, x - 1, y, 3, 10, glow);
            drawLandscapeLine(pixels, x, y, x, y + 10, color);
            drawLandscapeLine(pixels, x, y, x + 5, y + 3, color);
            drawLandscapeLine(pixels, x + 5, y + 3, x, y + 6, color);
            drawLandscapeLine(pixels, x, y + 6, x + 5, y + 9, color);
            drawLandscapeLine(pixels, x + 5, y + 9, x, y + 12, color);
            drawLandscapeLine(pixels, x - 5, y + 2, x, y + 6, highlight);
            drawLandscapeLine(pixels, x - 5, y + 10, x, y + 6, highlight);
        }
    }

    private String gameTimeLabel() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return "--:--";
        }
        long dayTime = minecraft.level.getGameTime() % 24000L;
        int totalMinutes = (int) ((dayTime + 6000L) % 24000L * 1440L / 24000L);
        int hour = totalMinutes / 60;
        int minute = totalMinutes % 60;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute);
    }

    private void drawRotationHint(byte[] pixels) {
        int hintTicks = MP4FocusState.rotationHintTicks();
        if (!MP4FocusState.rotationHintVisible()) {
            return;
        }
        fillRect(pixels, 7, 7, WIDTH - 14, HEIGHT - 14, 0xCC05070C);
        drawRotatingRefreshGlyph(pixels, WIDTH / 2, HEIGHT / 2 - 8, 50, MP4FocusState.ticks(), 0x4474C7FF);
        fillRoundRect(pixels, 24, 142, WIDTH - 48, 142, 7, 0xDD10141E);
        fillRoundRectOutline(pixels, 24, 142, WIDTH - 48, 142, 7, 0x99BBD8FF);
        drawTextCentered(pixels, WIDTH / 2, 176, "可以拖动边框旋转屏幕", 0xFFEAF2FF, false);
        drawTextCentered(pixels, WIDTH / 2, 197, "横屏播放和竖屏队列都靠这个切换", 0xFF9EADC8, true);
        drawRotationHintButton(pixels, 78, 232, 100, 26, hintTicks, false);
    }

    private void drawLandscapeRotationHint(byte[] pixels) {
        int hintTicks = MP4FocusState.rotationHintTicks();
        if (!MP4FocusState.rotationHintVisible()) {
            return;
        }
        fillLandscapeRect(pixels, 7, 7, LANDSCAPE_WIDTH - 14, LANDSCAPE_HEIGHT - 14, 0xCC05070C);
        drawLandscapeRotatingRefreshGlyph(pixels, LANDSCAPE_WIDTH / 2, LANDSCAPE_HEIGHT / 2 - 2, 48,
                MP4FocusState.ticks(), 0x4474C7FF);
        fillLandscapeRoundRect(pixels, 102, 61, 244, 122, 7, 0xDD10141E);
        fillLandscapeRectOutline(pixels, 102, 61, 244, 122, 0x99BBD8FF);
        drawLandscapeTextCentered(pixels, LANDSCAPE_WIDTH / 2, 94, "可以拖动边框旋转屏幕", 0xFFEAF2FF, false);
        drawLandscapeTextCentered(pixels, LANDSCAPE_WIDTH / 2, 116, "横屏播放和竖屏队列都靠这个切换", 0xFF9EADC8, true);
        drawRotationHintButton(pixels, 174, 139, 100, 26, hintTicks, true);
    }

    private void drawLandscapeRotatingRefreshGlyph(byte[] pixels, int cx, int cy, int radius, int ticks, int color) {
        double start = ticks * 0.22D;
        for (int i = 0; i < 54; i++) {
            double angle = start + i * Math.PI * 1.55D / 54.0D;
            int x = cx + (int) Math.round(Math.cos(angle) * radius);
            int y = cy + (int) Math.round(Math.sin(angle) * radius);
            fillLandscapeRect(pixels, x - 1, y - 1, 3, 3, color);
        }
        double head = start + Math.PI * 1.55D;
        int hx = cx + (int) Math.round(Math.cos(head) * radius);
        int hy = cy + (int) Math.round(Math.sin(head) * radius);
        drawLandscapeArrowHead(pixels, hx, hy, head + Math.PI * 0.5D, color);
    }

    private void drawLandscapeArrowHead(byte[] pixels, int x, int y, double direction, int color) {
        double left = direction + Math.PI * 0.75D;
        double right = direction - Math.PI * 0.75D;
        drawLandscapeLine(pixels, x, y, x + (int) Math.round(Math.cos(left) * 12.0D),
                y + (int) Math.round(Math.sin(left) * 12.0D), color);
        drawLandscapeLine(pixels, x, y, x + (int) Math.round(Math.cos(right) * 12.0D),
                y + (int) Math.round(Math.sin(right) * 12.0D), color);
    }

    private void drawLandscapeLine(byte[] pixels, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0;
        int y = y0;
        while (true) {
            fillLandscapeRect(pixels, x - 1, y - 1, 3, 3, color);
            if (x == x1 && y == y1) {
                break;
            }
            int e2 = err * 2;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private void drawRotationHintButton(byte[] pixels, int x, int y, int w, int h, int hintTicks, boolean landscape) {
        int bg = 0xFF25364A;
        int border = 0xFF74C7FF;
        int text = 0xFFEAF2FF;
        String label = "确定(" + Math.max(1, (hintTicks + 19) / 20) + "s)";
        if (landscape) {
            fillLandscapeRoundRect(pixels, x, y, w, h, 5, bg);
            fillLandscapeRectOutline(pixels, x, y, w, h, border);
            drawLandscapeTextCentered(pixels, x + w / 2, y + 7, label, text, false);
        } else {
            fillRoundRect(pixels, x, y, w, h, 5, bg);
            fillRectOutline(pixels, x, y, w, h, border);
            drawTextCentered(pixels, x + w / 2, y + 7, label, text, false);
        }
    }

    private void drawAlbumArt(byte[] pixels, int x, int y, int w, int h) {
        drawSteppedGradient(pixels, x, y, w, h, 0xFF2D6AD6, 0xFF6D35B8, 10);
        fillCircle(pixels, x + w / 2, y + h / 2, 55, 0xAA050507);
        fillCircle(pixels, x + w / 2, y + h / 2, 22, 0xFF171A25);
        fillCircle(pixels, x + w / 2, y + h / 2, 8, 0xFF77D7FF);
    }

    private void drawPortraitLyricsPanel(byte[] pixels, int x, int y, int w, int h) {
        drawSteppedGradient(pixels, x, y, w, h, 0xFF101725, 0xFF241739, 14);
        fillRoundRect(pixels, x + 7, y + 8, w - 14, h - 16, 5, 0xAA05070C);
        drawTextCentered(pixels, x + w / 2, y + 15, "滚动歌词", 0xFF8CCBFF, true);
        String lyric = MP4ClientPlayback.localLyricLine(currentDeviceId);
        String translated = MP4ClientPlayback.localTranslatedLyricLine(currentDeviceId);
        boolean hasLyric = lyric != null && !lyric.isBlank();
        boolean hasTranslated = translated != null && !translated.isBlank();
        if (!hasLyric && !hasTranslated) {
            drawTextCentered(pixels, x + w / 2, y + h / 2 - 6, "暂无歌词", 0xFF8B94AA, false);
            drawTextCentered(pixels, x + w / 2, y + h / 2 + 14, "NetMusic lyric loading...", 0xFF5C6678, true);
            fillRect(pixels, x + 16, y + 124, w - 32, 1, 0x448CCBFF);
            return;
        }
        if (hasLyric) {
            drawTextCentered(pixels, x + w / 2, y + h / 2 - (hasTranslated ? 12 : 4), lyric, 0xFFEAF2FF, false);
        }
        if (hasTranslated) {
            drawTextCentered(pixels, x + w / 2, y + h / 2 + 13, translated, 0xFF9FD6FF, true);
        }
        fillRect(pixels, x + 16, y + 124, w - 32, 1, 0x448CCBFF);
    }

    private String currentSongTitle() {
        String active = MP4ClientPlayback.localSongName(currentDeviceId);
        if (active != null && !active.isBlank()) {
            return active;
        }
        return MP4FocusState.queueTitle(MP4FocusState.selectedQueueIndex());
    }

    private static String currentSongSubtitle() {
        if (MP4FocusState.queueSize() <= 0) {
            return "把 NetMusic 唱片放进 MP4";
        }
        return "NetMusic 唱片 · " + (MP4FocusState.selectedQueueIndex() + 1) + "/" + MP4FocusState.queueSize();
    }

    private long currentElapsedMillis() {
        long active = MP4ClientPlayback.localElapsedMillis(currentDeviceId);
        if (active >= 0L) {
            return active;
        }
        return Math.round(MP4FocusState.mediaProgress() * MP4FocusState.selectedTrackDurationMillis());
    }

    private long currentDurationMillis() {
        long active = MP4ClientPlayback.localDurationMillis(currentDeviceId);
        return active > 0L ? active : MP4FocusState.selectedTrackDurationMillis();
    }

    private static String formatPlaybackTime(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private void drawPlaylistOverlay(byte[] pixels) {
        fillRoundRect(pixels, 18, 62, WIDTH - 36, 242, 5, 0xEE10141E);
        drawText(pixels, 35, 78, "播放队列", 0xFF8CCBFF, false);
        drawText(pixels, 150, 81, queuePageLabel(),
                0xFF8B94AA, true);
        drawText(pixels, 35, 96, "滚轮上下滑动，点击条目选择", 0xFF8B94AA, true);
        if (MP4FocusState.queueSize() <= 0) {
            drawTextCentered(pixels, WIDTH / 2, 183, "把 NetMusic 唱片放进 MP4", 0xFF8B94AA, true);
            return;
        }
        for (int i = 0; i < MP4FocusState.PORTRAIT_QUEUE_VISIBLE_ROWS; i++) {
            int index = MP4FocusState.queueScrollOffset() + i;
            if (index >= MP4FocusState.queueSize()) {
                break;
            }
            int y = 116 + i * 28;
            int color = index == MP4FocusState.selectedQueueIndex() ? 0xFF263B5F : 0xFF171D2A;
            fillRoundRect(pixels, 31, y, WIDTH - 70, 22, 4, color);
            fillCircle(pixels, 46, y + 11, 6, index == MP4FocusState.selectedQueueIndex() ? 0xFF74C7FF : 0xFF374158);
            drawMarqueeText(pixels, 60, y + 4, 110, queueTitle(index), 0xFFEAF2FF, false);
            drawText(pixels, 176, y + 6,
                    String.format(java.util.Locale.ROOT, "%02d:%02d", 2 + index / 6, 36 + index % 6),
                    0xFF8B94AA, true);
        }
        drawQueueScrollbar(pixels, 211, 116, 5, 162);
    }

    private String queueTitle(int index) {
        return MP4FocusState.queueTitle(index);
    }

    private String queuePageLabel() {
        int size = MP4FocusState.queueSize();
        return size <= 0 ? "0/0" : (MP4FocusState.queueScrollOffset() + 1) + "/" + size;
    }

    private void drawQueueScrollbar(byte[] pixels, int x, int y, int w, int h) {
        fillRoundRect(pixels, x, y, w, h, 2, 0xFF222939);
        int max = Math.max(1, MP4FocusState.queueSize() - MP4FocusState.PORTRAIT_QUEUE_VISIBLE_ROWS);
        int thumbH = Math.max(18,
                h * MP4FocusState.PORTRAIT_QUEUE_VISIBLE_ROWS / Math.max(1, MP4FocusState.queueSize()));
        int thumbY = y + (h - thumbH) * MP4FocusState.queueScrollOffset() / max;
        fillRoundRect(pixels, x, thumbY, w, thumbH, 2, 0xFF74C7FF);
    }

    private void drawLandscapeQueueScrollbar(byte[] pixels, int x, int y, int w, int h) {
        fillLandscapeRoundRect(pixels, x, y, w, h, 2, 0xFF222939);
        int max = Math.max(1, MP4FocusState.queueSize() - MP4FocusState.LANDSCAPE_QUEUE_VISIBLE_ROWS);
        int thumbH = Math.max(9,
                h * MP4FocusState.LANDSCAPE_QUEUE_VISIBLE_ROWS / Math.max(1, MP4FocusState.queueSize()));
        int thumbY = y + (h - thumbH) * MP4FocusState.queueScrollOffset() / max;
        fillLandscapeRoundRect(pixels, x, thumbY, w, thumbH, 2, 0xFF74C7FF);
    }

    private void drawDeviceFrame(byte[] pixels) {
        fillRoundRectOutline(pixels, 1, 1, WIDTH - 2, HEIGHT - 2, 5, 0xFF020203);
        fillRoundRectOutline(pixels, 3, 3, WIDTH - 6, HEIGHT - 6, 5, 0xCC282D38);
        fillRectOutline(pixels, 6, 6, WIDTH - 12, HEIGHT - 12, 0x660B0D12);
        fillRect(pixels, 92, HEIGHT - 8, WIDTH - 184, 1, 0x77090A0E);
        fillRect(pixels, 98, HEIGHT - 6, WIDTH - 196, 1, 0x88303642);
    }

    private boolean playbackModeActive() {
        return MP4FocusState.shuffle() || MP4FocusState.repeatMode() > 0;
    }

    private boolean biliLoginActive() {
        return BiliApiClient.sessdata != null && !BiliApiClient.sessdata.isBlank();
    }

    private String playbackModeLabel() {
        if (MP4FocusState.shuffle()) {
            return "随机";
        }
        return switch (MP4FocusState.repeatMode()) {
            case 1 -> "列表循环";
            case 2 -> "单曲循环";
            default -> "顺序";
        };
    }

    private void fillLandscapeRect(byte[] pixels, int x, int y, int w, int h, int color) {
        canvas.fillLandscapeRect(pixels, x, y, w, h, color);
    }

    private void fillLandscapeRectOutline(byte[] pixels, int x, int y, int w, int h, int color) {
        canvas.fillLandscapeRectOutline(pixels, x, y, w, h, color);
    }

    private void fillLandscapeRoundRect(byte[] pixels, int x, int y, int w, int h, int radius, int color) {
        canvas.fillLandscapeRoundRect(pixels, x, y, w, h, radius, color);
    }

    private void fillLandscapeCircle(byte[] pixels, int cx, int cy, int r, int color) {
        canvas.fillLandscapeCircle(pixels, cx, cy, r, color);
    }

    private void drawLandscapeSteppedGradient(byte[] pixels, int x, int y, int w, int h, int top, int bottom,
            int steps) {
        canvas.drawLandscapeSteppedGradient(pixels, x, y, w, h, top, bottom, steps);
    }

    private void drawLandscapeProgress(byte[] pixels, int x, int y, int w, int h, float progress, int bg, int fg) {
        fillLandscapeRect(pixels, x, y, w, h, bg);
        int filled = Math.max(h, Math.round(w * Math.max(0.0F, Math.min(1.0F, progress))));
        fillLandscapeRect(pixels, x, y, filled, h, fg);
        fillLandscapeRect(pixels, x + filled - h, y - h / 2, h * 2, h * 2, 0xFF9CA3AD);
        fillLandscapeRectOutline(pixels, x + filled - h, y - h / 2, h * 2, h * 2, 0xFF4B515D);
    }

    private void drawLandscapeSquareButton(byte[] pixels, int x, int y, int w, int h, int bg, int fg, String label) {
        fillLandscapeRect(pixels, x + 3, y + 4, w, h, 0x55000000);
        fillLandscapeRect(pixels, x, y, w, h, bg);
        fillLandscapeRectOutline(pixels, x, y, w, h, 0xFF394154);
        fillLandscapeRect(pixels, x + 3, y + 3, w - 6, 2, 0x44FFFFFF);
        fillLandscapeRect(pixels, x + 3, y + h - 5, w - 6, 2, 0x66000000);
        drawLandscapeTextCentered(pixels, x + w / 2, y + h / 2 - 6, label, fg, false);
    }

    private void drawLandscapeText(byte[] pixels, int x, int y, String text, int color, boolean small) {
        textRenderer.drawLandscapeText(pixels, x, y, text, color, small, this::blendLandscapePixel);
    }

    private void drawLandscapeMarqueeText(byte[] pixels, int x, int y, int maxWidth, String text, int color,
            boolean small) {
        textRenderer.drawLandscapeMarqueeText(pixels, x, y, maxWidth, text, color, small, MP4FocusState.ticks(),
                this::blendLandscapePixel);
    }

    private void drawLandscapeTextCentered(byte[] pixels, int centerX, int y, String text, int color, boolean small) {
        textRenderer.drawLandscapeTextCentered(pixels, centerX, y, text, color, small, this::blendLandscapePixel);
    }

    private void setLandscapePixel(byte[] pixels, int x, int y, int color) {
        canvas.setLandscapePixel(pixels, x, y, color);
    }

    private void blendLandscapePixel(byte[] pixels, int x, int y, int color) {
        canvas.blendLandscapePixel(pixels, x, y, color);
    }

    private void clear(byte[] pixels, int color) {
        canvas.clear(pixels, color);
    }

    private void drawSteppedGradient(byte[] pixels, int x, int y, int w, int h, int top, int bottom, int steps) {
        canvas.drawSteppedGradient(pixels, x, y, w, h, top, bottom, steps);
    }

    private void drawPixelNoise(byte[] pixels, int x, int y, int w, int h, int dark, int light) {
        canvas.drawPixelNoise(pixels, x, y, w, h, dark, light);
    }

    private void drawArcSparkles(byte[] pixels, int cx, int cy, int r) {
        canvas.drawArcSparkles(pixels, cx, cy, r);
    }

    private void drawProgress(byte[] pixels, int x, int y, int w, int h, float progress, int bg, int fg) {
        fillRect(pixels, x, y, w, h, bg);
        int filled = Math.max(h, Math.round(w * Math.max(0.0F, Math.min(1.0F, progress))));
        fillRect(pixels, x, y, filled, h, fg);
        fillRect(pixels, x + filled - h, y - h / 2, h * 2, h * 2, 0xFF9CA3AD);
        fillRectOutline(pixels, x + filled - h, y - h / 2, h * 2, h * 2, 0xFF4B515D);
    }

    private void drawSquareButton(byte[] pixels, int x, int y, int w, int h, int bg, int fg, String label) {
        fillRect(pixels, x + 3, y + 4, w, h, 0x55000000);
        fillRect(pixels, x, y, w, h, bg);
        fillRectOutline(pixels, x, y, w, h, 0xFF394154);
        fillRect(pixels, x + 3, y + 3, w - 6, 2, 0x44FFFFFF);
        fillRect(pixels, x + 3, y + h - 5, w - 6, 2, 0x66000000);
        drawTextCentered(pixels, x + w / 2, y + h / 2 - 6, label, fg, false);
    }

    private void drawText(byte[] pixels, int x, int y, String text, int color, boolean small) {
        textRenderer.drawText(pixels, x, y, text, color, small, this::blendPixel);
    }

    private void drawMarqueeText(byte[] pixels, int x, int y, int maxWidth, String text, int color, boolean small) {
        textRenderer.drawMarqueeText(pixels, x, y, maxWidth, text, color, small, MP4FocusState.ticks(),
                this::blendPixel);
    }

    private void drawTextCentered(byte[] pixels, int centerX, int y, String text, int color, boolean small) {
        textRenderer.drawTextCentered(pixels, centerX, y, text, color, small, this::blendPixel);
    }

    private void fillRect(byte[] pixels, int x, int y, int w, int h, int color) {
        canvas.fillRect(pixels, x, y, w, h, color);
    }

    private void fillRoundRect(byte[] pixels, int x, int y, int w, int h, int radius, int color) {
        canvas.fillRoundRect(pixels, x, y, w, h, radius, color);
    }

    private void fillRoundRectOutline(byte[] pixels, int x, int y, int w, int h, int radius, int color) {
        canvas.fillRoundRectOutline(pixels, x, y, w, h, radius, color);
    }

    private void fillRectOutline(byte[] pixels, int x, int y, int w, int h, int color) {
        canvas.fillRectOutline(pixels, x, y, w, h, color);
    }

    private void fillCircle(byte[] pixels, int cx, int cy, int r, int color) {
        canvas.fillCircle(pixels, cx, cy, r, color);
    }

    private void setPixel(byte[] pixels, int x, int y, int color) {
        canvas.setPixel(pixels, x, y, color);
    }

    private void blendPixel(byte[] pixels, int x, int y, int color) {
        canvas.blendPixel(pixels, x, y, color);
    }

    @Override
    public void close() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            texture.close();
            texture = null;
        }
        dirty = true;
    }
}
