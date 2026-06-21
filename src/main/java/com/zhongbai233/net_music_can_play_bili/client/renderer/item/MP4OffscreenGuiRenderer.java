package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldMediaProfile;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.slf4j.Logger;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.UUID;

/** MP4 GPU 离屏 GUI 渲染器，保留 256x448 逻辑纹理坐标。 */
final class MP4OffscreenGuiRenderer implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SCALE = MP4HandheldMediaProfile.SCREEN.offscreenScale();
    private static final int WIDTH = MP4GuiTexture.WIDTH;
    private static final int HEIGHT = MP4GuiTexture.HEIGHT;
    private static final int TARGET_WIDTH = WIDTH * Math.max(1, SCALE);
    private static final int TARGET_HEIGHT = HEIGHT * Math.max(1, SCALE);
    private final Identifier textureId;
    private TextureTarget target;
    private RenderTargetTexture texture;
    private GuiRenderer renderer;
    private GuiRenderState renderState;
    private ProjectionMatrixBuffer projectionBuffer;
    private SubmitNodeStorage submitNodeStorage;
    private FogRenderer fogRenderer;
    private boolean registered;
    private boolean failed;
    private boolean loggedReady;
    private int renderTicks;

    MP4OffscreenGuiRenderer(String textureKey) {
        String safeKey = textureKey == null || textureKey.isBlank() ? "fallback"
                : textureKey.toLowerCase(java.util.Locale.ROOT);
        this.textureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/mp4_gui_offscreen_" + safeKey);
    }

    Identifier textureId(UUID deviceId) {
        request(deviceId);
        return textureId;
    }

    void request(UUID deviceId) {
        if (failed) {
            return;
        }
        try {
            ensureResources();
        } catch (RuntimeException ex) {
            failed = true;
            LOGGER.warn("MP4 离屏 GUI 初始化失败: {}", textureId, ex);
        }
    }

    void renderFrameStart(UUID deviceId) {
        if (failed) {
            return;
        }
        try {
            ensureResources();
            MP4HandheldVideoClient.update(deviceId);
            render(deviceId);
            if (!loggedReady) {
                loggedReady = true;
                LOGGER.info("MP4 离屏 GUI 已启用: texture={} target={}x{} logical={}x{} scale={}", textureId,
                        TARGET_WIDTH, TARGET_HEIGHT, WIDTH, HEIGHT, Math.max(1, SCALE));
            }
        } catch (RuntimeException ex) {
            failed = true;
            LOGGER.warn("MP4 离屏 GUI 渲染失败: {}", textureId, ex);
        }
    }

    private void ensureResources() {
        if (target != null) {
            return;
        }
        target = new TextureTarget("netmusic mp4 gui", TARGET_WIDTH, TARGET_HEIGHT, true);
        texture = new RenderTargetTexture(target);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getTextureManager().register(textureId, texture);
        registered = true;

        renderState = new GuiRenderState();
        projectionBuffer = new ProjectionMatrixBuffer("netmusic_mp4_offscreen_gui_projection");
        submitNodeStorage = new SubmitNodeStorage();
        fogRenderer = new FogRenderer();
        RenderBuffers buffers = minecraft.renderBuffers();
        renderer = new GuiRenderer(renderState, buffers.bufferSource(), submitNodeStorage,
                minecraft.gameRenderer.getFeatureRenderDispatcher(), List.of());
    }

    private void render(UUID deviceId) {
        Minecraft minecraft = Minecraft.getInstance();
        renderState.reset();
        renderState.clearColorOverride = 0x00000000;
        GuiGraphicsExtractor graphics = new GuiGraphicsExtractor(minecraft, renderState, -1, -1);
        drawGui(graphics, MP4GuiViewState.capture(deviceId));

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (target.getDepthTexture() != null) {
            encoder.clearColorAndDepthTextures(target.getColorTexture(), 0x00000000, target.getDepthTexture(), 1.0D);
        } else {
            encoder.clearColorTexture(target.getColorTexture(), 0x00000000);
        }

        WindowRenderState windowState = minecraft.gameRenderer.getGameRenderState().windowRenderState;
        int oldWidth = windowState.width;
        int oldHeight = windowState.height;
        int oldGuiScale = windowState.guiScale;
        windowState.width = TARGET_WIDTH;
        windowState.height = TARGET_HEIGHT;
        windowState.guiScale = Math.max(1, SCALE);
        Matrix4f projection = new Matrix4f().setOrtho(0.0F, TARGET_WIDTH, TARGET_HEIGHT, 0.0F, 1000.0F, 3000.0F);
        GpuBufferSlice oldProjectionBuffer = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType oldProjectionType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        try {
            // RenderSystem 的投影备份是单槽，不是栈；Iris 手部渲染外层已经使用它保存世界投影。
            // 离屏 GUI 不能再嵌套 backup/restore，否则会覆盖 Iris 的备份，导致方块选择框使用手部投影。
            modelViewStack.identity();
            OffscreenGuiRenderTargetContext.withTarget(target,
                    () -> renderer.render(fogRenderer.getBuffer(FogRenderer.FogMode.NONE)));
            renderer.endFrame();
            submitNodeStorage.endFrame();
            fogRenderer.endFrame();
            texture.refreshView();
        } finally {
            modelViewStack.popMatrix();
            if (oldProjectionBuffer != null) {
                RenderSystem.setProjectionMatrix(oldProjectionBuffer, oldProjectionType);
            }
            windowState.width = oldWidth;
            windowState.height = oldHeight;
            windowState.guiScale = oldGuiScale;
        }
    }

    private void drawGui(GuiGraphicsExtractor g, MP4GuiViewState view) {
        renderTicks = view.ticks();
        if (!view.transparentLandscapeVideoOverlay()) {
            fill(g, 0, 0, WIDTH, HEIGHT, 0xFF050507);
        }
        if (view.landscape()) {
            withLandscape(g, () -> drawLandscape(g, view));
        } else {
            drawPortrait(g, view);
        }
        if (!view.transparentLandscapeVideoOverlay()) {
            drawDeviceFrame(g);
        }
    }

    private void drawPortrait(GuiGraphicsExtractor g, MP4GuiViewState view) {
        fill(g, 0, 0, WIDTH, HEIGHT, 0xFF050507);
        fill(g, 2, 2, WIDTH - 4, HEIGHT - 4, 0xFF12141A);
        fill(g, 7, 7, WIDTH - 14, HEIGHT - 14, 0xFF171A22);
        text(g, 28, 27, "NET MUSIC", 0xFFB8C5DE, true);
        text(g, 194, 27, gameTimeLabel(), 0xFFB8C5DE, true);
        text(g, 25, 45, "MP4 队列", 0xFF8CCBFF, false);
        text(g, 83, 45, view.playing() ? "播放中" : "已暂停",
                view.playing() ? 0xFF6DFFB0 : 0xFFFFC46D, false);

        fill(g, 30, 58, 196, 184, 0xFF2B3040);
        fill(g, 35, 63, 186, 174, 0xFF202634);
        if (view.lyricsEnabled()) {
            drawLyricsPanel(g, view, 39, 67, 178, 166);
        } else {
            drawAlbumArt(g, 39, 67, 178, 166);
        }

        centeredMarqueeText(g, 128, 254, 184, view.songTitle(), 0xFFEAF2FF, false);
        centeredText(g, 128, 272, view.songSubtitle(), 0xFF8B94AA, true);
        progress(g, 28, 295, 200, 8, view.mediaProgress(), 0xFF32384A, 0xFF74C7FF);
        text(g, 29, 312, formatPlaybackTime(view.elapsedMillis()), 0xFF77839D, true);
        text(g, 198, 312, formatPlaybackTime(view.durationMillis()), 0xFF77839D, true);

        prevButton(g, 34, 333, 42, 42, 0xFF1C2230, 0xFFBBD8FF);
        playPauseButton(g, 101, 325, 54, 54, view.playing() ? 0xFF70D8FF : 0xFFFFD26E, 0xFF071018,
                view.playing());
        nextButton(g, 180, 333, 42, 42, 0xFF1C2230, 0xFFBBD8FF);

        text(g, 26, 389, "VOL", 0xFF7F8DA8, true);
        progress(g, 64, 393, 154, 6, view.volume(), 0xFF32384A, 0xFF7EFFC4);
        smallToggle(g, 20, 411, 42, 14, view.biliLoginActive() ? 0xFF6B4E2B : 0xFF202635, "B站");
        smallToggle(g, 68, 411, 58, 14, view.playbackModeActive() ? 0xFF365A55 : 0xFF202635,
                view.playbackModeLabel());
        smallToggle(g, 136, 411, 58, 14, view.playlistOpen() ? 0xFF234969 : 0xFF202635, "列表");
        smallToggle(g, 203, 411, 32, 14, view.lyricsEnabled() ? 0xFF4D3568 : 0xFF202635, "词");

        if (view.playlistOpen()) {
            drawPlaylist(g, view);
        }
        if (view.subtitleMenuOpen()) {
            drawSubtitleMenu(g, view);
        }
        if (view.qualityMenuOpen()) {
            drawQualityMenu(g, view);
        }
        if (view.biliLoginVisible()) {
            drawBiliLoginOverlay(g, view);
        }
        drawHover(g, view, false);
    }

    private void drawLandscape(GuiGraphicsExtractor g, MP4GuiViewState view) {
        if (view.hasVideoFrame()) {
            if (view.controlsVisible()) {
                fill(g, 0, 0, 448, 10, 0x9905070C);
                fill(g, 0, 246, 448, 10, 0x9905070C);
                fill(g, 0, 10, 10, 236, 0x9905070C);
                fill(g, 438, 10, 10, 236, 0x9905070C);
                outline(g, 7, 7, 434, 242, 0x66273040);
            }
        } else {
            fill(g, 0, 0, 448, 256, 0xFF05070C);
            fill(g, 7, 7, 434, 242, 0xFF070B12);
            fill(g, 10, 10, 428, 236, 0xFF07111F);
            if (view.audioOnly()) {
                centeredText(g, 224, 114, "纯音乐", 0xCCEAF2FF, false);
                centeredText(g, 224, 134, "当前音源没有视频画面", 0x99DCEBFF, true);
            } else {
                centeredText(g, 224, 122, view.videoEnabled()
                        ? view.videoStatusText()
                        : "视频线路已关闭", 0xCCFFFFFF, true);
            }
        }
        if (!view.videoSubtitle().isBlank() && view.lyricsEnabled()) {
            int subtitleY = view.controlsVisible() ? 151 : 199;
            drawLandscapeSubtitle(g, view.videoSubtitle(), subtitleY, view.controlsVisible());
        }
        if (view.controlsVisible()) {
            fill(g, 14, 14, 420, 26, 0x66000000);
            text(g, 25, 21, "BiliBili 视频投影预览", 0xFFEAF2FF, false);
            text(g, 165, 23, view.playing() ? "LIVE PLAYBACK" : "PAUSED",
                    view.playing() ? 0xFF6DFFB0 : 0xFFFFC46D, true);
            smallToggle(g, 278, 18, 44, 16, view.playlistOpen() ? 0xCC234969 : 0xAA202635, "列表");
            text(g, 333, 22, view.quality(), 0xFFDCEBFF, true);
            text(g, 389, 22, gameTimeLabel(), 0xFF89D6FF, true);

            fill(g, 16, 171, 416, 66, 0xAA05070C);
            progress(g, 32, 184, 384, 6, view.mediaProgress(), 0xFF32384A, 0xFF74C7FF);
            text(g, 32, 196, formatPlaybackTime(view.elapsedMillis()), 0xFFB8C5DE, true);
            text(g, 389, 196, formatPlaybackTime(view.durationMillis()), 0xFFB8C5DE, true);
            prevButton(g, 146, 204, 30, 24, 0xAA1C2230, 0xFFBBD8FF);
            playPauseButton(g, 200, 198, 46, 34, view.playing() ? 0xDD70D8FF : 0xDDFFD26E, 0xFF071018,
                    view.playing());
            nextButton(g, 270, 204, 30, 24, 0xAA1C2230, 0xFFBBD8FF);
            text(g, 319, 208, "VOL", 0xFFB8C5DE, true);
            progress(g, 348, 212, 70, 5, view.volume(), 0xFF32384A, 0xFF7EFFC4);
            smallToggle(g, 28, 213, 48, 14, view.subtitleMenuOpen() ? 0xAA4D3568 : 0xAA202635, "字幕");
            smallToggle(g, 88, 213, 48, 14, view.repeatMode() > 0 ? 0xAA615235 : 0xAA202635, "循环");
        } else {
            text(g, 360, 18, view.videoResolutionLabel(), 0x99DCEBFF, true);
        }
        if (view.playlistOpen()) {
            drawLandscapePlaylist(g, view);
        }
        if (view.subtitleMenuOpen() && view.controlsVisible()) {
            drawLandscapeSubtitleMenu(g, view);
        }
        if (view.qualityMenuOpen() && view.controlsVisible()) {
            drawLandscapeQualityMenu(g, view);
        }
        if (view.biliLoginVisible()) {
            drawLandscapeBiliLoginOverlay(g, view);
        }
        drawHover(g, view, true);
    }

    private void withLandscape(GuiGraphicsExtractor g, Runnable draw) {
        g.pose().pushMatrix();
        g.pose().translate(0.0F, HEIGHT);
        g.pose().rotate((float) -Math.PI / 2.0F);
        draw.run();
        g.pose().popMatrix();
    }

    private void drawAlbumArt(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        fill(g, x, y, w, h, 0xFF2D6AD6);
        fill(g, x + 8, y + 8, w - 16, h - 16, 0xAA050507);
        fill(g, x + w / 2 - 22, y + h / 2 - 22, 44, 44, 0xFF171A25);
        fill(g, x + w / 2 - 8, y + h / 2 - 8, 16, 16, 0xFF77D7FF);
    }

    private void drawLyricsPanel(GuiGraphicsExtractor g, MP4GuiViewState view, int x, int y, int w, int h) {
        steppedGradientV(g, x, y, w, h, 0xFF101725, 0xFF241739, 14);
        fill(g, x + 7, y + 8, w - 14, h - 16, 0xAA05070C);
        text(g, x + w / 2 - 20, y + 14, "滚动歌词", 0xFF8CCBFF, true);
        String lyric = view.lyricLine();
        String translated = view.translatedLyricLine();
        boolean hasLyric = lyric != null && !lyric.isBlank();
        boolean hasTranslated = translated != null && !translated.isBlank();
        int maxTextWidth = w - 30;
        int lineHeight = 12;
        int contentCenterY = y + h / 2 + 4;
        if (!hasLyric && !hasTranslated) {
            centeredText(g, x + w / 2, contentCenterY - 8, "暂无歌词", 0xFF8B94AA, false);
            centeredText(g, x + w / 2, contentCenterY + 14, "NetMusic lyric loading...", 0xFF5C6678, true);
            fill(g, x + 16, y + 124, w - 32, 1, 0x338CCBFF);
            return;
        }
        if (hasLyric && hasTranslated) {
            drawWrappedCentered(g, x + w / 2, contentCenterY - lineHeight, maxTextWidth, lineHeight,
                    lyric, 0xFFEAF2FF, false);
            drawWrappedCentered(g, x + w / 2, contentCenterY + lineHeight + 2, maxTextWidth, lineHeight,
                    translated, 0xFF9FD6FF, false);
        } else if (hasLyric) {
            drawWrappedCentered(g, x + w / 2, contentCenterY, maxTextWidth, lineHeight,
                    lyric, 0xFFEAF2FF, false);
        } else {
            drawWrappedCentered(g, x + w / 2, contentCenterY, maxTextWidth, lineHeight,
                    translated, 0xFF9FD6FF, false);
        }
        fill(g, x + 16, y + 124, w - 32, 1, 0x338CCBFF);
    }

    private void drawPlaylist(GuiGraphicsExtractor g, MP4GuiViewState view) {
        fill(g, 18, 62, WIDTH - 36, 242, 0xEE10141E);
        text(g, 35, 78, "播放队列", 0xFF8CCBFF, false);
        text(g, 150, 81, queuePageLabel(view), 0xFF8B94AA, true);
        if (view.queueSize() <= 0) {
            centeredText(g, WIDTH / 2, 183, "把 NetMusic 唱片放进 MP4", 0xFF8B94AA, true);
            return;
        }
        for (int i = 0; i < view.portraitQueueVisibleRows(); i++) {
            int index = view.queueScrollOffset() + i;
            if (index >= view.queueSize()) {
                break;
            }
            int rowY = 116 + i * 28;
            boolean selected = index == view.selectedQueueIndex();
            fill(g, 31, rowY, WIDTH - 70, 22, selected ? 0xFF263B5F : 0xFF171D2A);
            fill(g, 40, rowY + 8, 5, 5, selected ? 0xFF74C7FF : 0xFF374158);
            marqueeText(g, 52, rowY + 6, WIDTH - 132, view.queueTitle(index), 0xFFEAF2FF, true);
        }
    }

    private void drawLandscapePlaylist(GuiGraphicsExtractor g, MP4GuiViewState view) {
        fill(g, 72, 44, 304, 76, 0xEE10141E);
        text(g, 88, 56, "播放列表 / 视频源", 0xFF8CCBFF, false);
        for (int i = 0; i < view.landscapeQueueVisibleRows(); i++) {
            int index = view.queueScrollOffset() + i;
            if (index >= view.queueSize()) {
                break;
            }
            int rowY = 75 + i * 14;
            boolean selected = index == view.selectedQueueIndex();
            fill(g, 88, rowY, 248, 12, selected ? 0xFF263B5F : 0xFF171D2A);
            fill(g, 93, rowY + 4, 4, 4, selected ? 0xFF74C7FF : 0xFF374158);
            marqueeText(g, 102, rowY + 1, 222, view.queueTitle(index), 0xFFEAF2FF, true);
        }
    }

    private void drawQualityMenu(GuiGraphicsExtractor g, MP4GuiViewState view) {
        fill(g, 150, 46, 86, 132, 0xEE10141E);
        text(g, 165, 53, "画质", 0xFF8CCBFF, true);
        for (int i = 0; i < view.qualities().size(); i++) {
            qualityOption(g, view, 160, 68 + i * 13, 66, 11, i);
        }
    }

    private void drawLandscapeQualityMenu(GuiGraphicsExtractor g, MP4GuiViewState view) {
        fill(g, 318, 42, 94, 132, 0xEE10141E);
        text(g, 350, 50, "画质", 0xFF8CCBFF, true);
        for (int i = 0; i < view.qualities().size(); i++) {
            qualityOption(g, view, 330, 65 + i * 13, 70, 11, i);
        }
    }

    private void qualityOption(GuiGraphicsExtractor g, MP4GuiViewState view, int x, int y, int w, int h, int index) {
        String quality = index >= 0 && index < view.qualities().size() ? view.qualities().get(index) : "";
        boolean selected = view.quality().equals(quality);
        fill(g, x, y, w, h, selected ? 0xFF263B5F : 0xFF171D2A);
        outline(g, x, y, w, h, selected ? 0xFF74C7FF : 0xFF394154);
        text(g, x + 6, y + 1, quality, selected ? 0xFFEAF2FF : 0xFFB8C5DE, true);
    }

    private void drawSubtitleMenu(GuiGraphicsExtractor g, MP4GuiViewState view) {
        fill(g, 132, 322, 96, 118, 0xEE10141E);
        text(g, 145, 328, "字幕设置", 0xFF8CCBFF, true);
        subtitleOption(g, 146, 354, 44, 14, "关", !view.lyricsEnabled());
        subtitleOption(g, 146, 375, 44, 14, "主", view.lyricsEnabled() && view.subtitlePrimaryMode());
        subtitleOption(g, 146, 396, 44, 14, "副", view.lyricsEnabled() && !view.subtitlePrimaryMode());
        subtitleOption(g, 146, 417, 72, 14, "AI字幕", view.subtitleAiEnabled());
    }

    private void drawLandscapeSubtitleMenu(GuiGraphicsExtractor g, MP4GuiViewState view) {
        fill(g, 232, 48, 108, 114, 0xEE10141E);
        text(g, 248, 55, "字幕设置", 0xFF8CCBFF, true);
        subtitleOption(g, 244, 72, 52, 14, "关", !view.lyricsEnabled());
        subtitleOption(g, 244, 94, 52, 14, "主", view.lyricsEnabled() && view.subtitlePrimaryMode());
        subtitleOption(g, 244, 116, 52, 14, "副", view.lyricsEnabled() && !view.subtitlePrimaryMode());
        subtitleOption(g, 244, 138, 82, 14, "AI字幕", view.subtitleAiEnabled());
    }

    private void subtitleOption(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean selected) {
        fill(g, x, y, w, h, selected ? 0xFF263B5F : 0xFF171D2A);
        outline(g, x, y, h, h, selected ? 0xFF74C7FF : 0xFF5A6378);
        if (selected) {
            text(g, x + 3, y + 2, "✓", 0xFFEAF2FF, true);
        }
        text(g, x + h + 5, y + 2, label, 0xFFEAF2FF, true);
    }

    private void drawBiliLoginOverlay(GuiGraphicsExtractor g, MP4GuiViewState view) {
        fill(g, 7, 7, WIDTH - 14, 398, 0xCC05070C);
        fill(g, 28, 76, 200, 260, 0xEE10141E);
        outline(g, 28, 76, 200, 260, 0x9974C7FF);
        centeredText(g, WIDTH / 2, 94, "B站账号登录", 0xFFEAF2FF, false);
        centeredText(g, WIDTH / 2, 112, "使用 B站 APP 扫码", 0xFFB8C5DE, true);
        drawQrImage(g, view.biliQrImage(), 58, 130, 140);
        centeredText(g, WIDTH / 2, 286, view.biliLoginStatusText(), 0xFF9AFFB2, true);
        centeredText(g, WIDTH / 2, 308, "再次点击 B站 按钮关闭/重试", 0xFF8B94AA, true);
    }

    private void drawLandscapeBiliLoginOverlay(GuiGraphicsExtractor g, MP4GuiViewState view) {
        fill(g, 7, 7, 434, 242, 0xCC05070C);
        fill(g, 124, 24, 200, 208, 0xEE10141E);
        outline(g, 124, 24, 200, 208, 0x9974C7FF);
        centeredText(g, 224, 42, "B站账号登录", 0xFFEAF2FF, false);
        centeredText(g, 224, 60, "使用 B站 APP 扫码", 0xFFB8C5DE, true);
        drawQrImage(g, view.biliQrImage(), 154, 76, 140);
        centeredText(g, 224, 222, view.biliLoginStatusText(), 0xFF9AFFB2, true);
    }

    private void drawQrImage(GuiGraphicsExtractor g, BufferedImage image, int x, int y, int size) {
        fill(g, x - 3, y - 3, size + 6, size + 6, 0xFFFFFFFF);
        if (image == null) {
            fill(g, x, y, size, size, 0xFF2B3040);
            centeredText(g, x + size / 2, y + size / 2 - 5, "二维码加载中", 0xFFB8C5DE, true);
            return;
        }
        int srcW = Math.max(1, image.getWidth());
        int srcH = Math.max(1, image.getHeight());
        for (int dy = 0; dy < size; dy++) {
            int sy = Math.min(srcH - 1, dy * srcH / Math.max(1, size));
            int runColor = 0;
            int runStart = x;
            int runWidth = 0;
            for (int dx = 0; dx < size; dx++) {
                int sx = Math.min(srcW - 1, dx * srcW / Math.max(1, size));
                int color = 0xFF000000 | (image.getRGB(sx, sy) & 0x00FFFFFF);
                if (runWidth == 0) {
                    runColor = color;
                    runStart = x + dx;
                    runWidth = 1;
                } else if (color == runColor) {
                    runWidth++;
                } else {
                    fill(g, runStart, y + dy, runWidth, 1, runColor);
                    runColor = color;
                    runStart = x + dx;
                    runWidth = 1;
                }
            }
            if (runWidth > 0) {
                fill(g, runStart, y + dy, runWidth, 1, runColor);
            }
        }
    }

    private void drawHover(GuiGraphicsExtractor g, MP4GuiViewState view, boolean landscape) {
        String control = view.hoverControlName();
        int color = 0x66FFFFFF;
        switch (control) {
            case "PREVIOUS" -> outline(g, landscape ? 144 : 32, landscape ? 202 : 331, landscape ? 34 : 46,
                    landscape ? 28 : 46, color);
            case "PLAY" -> outline(g, landscape ? 198 : 99, landscape ? 196 : 323, landscape ? 50 : 58,
                    landscape ? 38 : 58, color);
            case "NEXT" -> outline(g, landscape ? 268 : 178, landscape ? 202 : 331, landscape ? 34 : 46,
                    landscape ? 28 : 46, color);
            case "PROGRESS" -> outline(g, landscape ? 30 : 26, landscape ? 176 : 291, landscape ? 388 : 204,
                    landscape ? 16 : 18, color);
            case "VOLUME" -> outline(g, landscape ? 346 : 62, landscape ? 207 : 388, landscape ? 74 : 158,
                    landscape ? 14 : 12, color);
            default -> {
            }
        }
    }

    private void drawDeviceFrame(GuiGraphicsExtractor g) {
        outline(g, 1, 1, WIDTH - 2, HEIGHT - 2, 0xFF020203);
        outline(g, 3, 3, WIDTH - 6, HEIGHT - 6, 0xCC282D38);
    }

    private void fill(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
    }

    private void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.outline(x, y, w, h, color);
    }

    private void text(GuiGraphicsExtractor g, int x, int y, String value, int color, boolean small) {
        Font font = Minecraft.getInstance().font;
        g.text(font, value, x, y, color, false);
    }

    private void centeredText(GuiGraphicsExtractor g, int centerX, int y, String value, int color, boolean small) {
        Font font = Minecraft.getInstance().font;
        int width = font.width(value);
        text(g, centerX - width / 2, y, value, color, small);
    }

    private void marqueeText(GuiGraphicsExtractor g, int x, int y, int maxWidth, String text, int color,
            boolean small) {
        Font font = Minecraft.getInstance().font;
        float scale = 1.0F;
        int textWidth = Math.round(font.width(text) * scale);
        if (textWidth <= maxWidth) {
            text(g, x, y, text, color, small);
            return;
        }
        String padded = text + "    " + text;
        int scrollPeriod = Math.max(1, Math.round(font.width(padded) * scale) + maxWidth);
        int offset = (renderTicks * 2) % scrollPeriod;
        int accumulated = 0;
        int start = 0;
        for (int i = 0; i < padded.length(); i++) {
            int cw = Math.round(font.width(String.valueOf(padded.charAt(i))) * scale);
            if (accumulated + cw > offset) {
                start = i;
                break;
            }
            accumulated += cw;
        }
        StringBuilder visible = new StringBuilder();
        int visibleWidth = 0;
        for (int i = start; i < padded.length(); i++) {
            char c = padded.charAt(i);
            int cw = Math.round(font.width(String.valueOf(c)) * scale);
            if (visibleWidth + cw > maxWidth) {
                break;
            }
            visible.append(c);
            visibleWidth += cw;
        }
        text(g, x, y, visible.toString(), color, small);
    }

    private void centeredMarqueeText(GuiGraphicsExtractor g, int centerX, int y, int maxWidth, String text,
            int color, boolean small) {
        Font font = Minecraft.getInstance().font;
        if (font.width(text) <= maxWidth) {
            centeredText(g, centerX, y, text, color, small);
            return;
        }
        marqueeText(g, centerX - maxWidth / 2, y, maxWidth, text, color, small);
    }

    private void drawLandscapeSubtitle(GuiGraphicsExtractor g, String subtitle, int y, boolean controlsVisible) {
        Font font = Minecraft.getInstance().font;
        int textWidth = Math.min(408, font.width(subtitle));
        int bgWidth = Math.min(420, Math.max(36, textWidth + 18));
        int bgX = 224 - bgWidth / 2;
        int bgY = y - 3;
        fill(g, bgX, bgY, bgWidth, 15, controlsVisible ? 0x77000000 : 0x66000000);
        fill(g, bgX + 1, bgY + 1, bgWidth - 2, 13, controlsVisible ? 0x55203048 : 0x4405070C);
        centeredText(g, 224, y, subtitle, 0xF2DCEBFF, true);
    }

    private void steppedGradientV(GuiGraphicsExtractor g, int x, int y, int w, int h, int topColor, int bottomColor,
            int steps) {
        steps = Math.max(1, Math.min(h, steps));
        float stepH = (float) h / steps;
        for (int i = 0; i < steps; i++) {
            int sy = y + Math.round(i * stepH);
            int sh = Math.round((i + 1) * stepH) - Math.round(i * stepH);
            float t = (float) i / (steps - 1);
            int color = lerpColor(topColor, bottomColor, t);
            fill(g, x, sy, w, sh, color);
        }
    }

    private static int lerpColor(int c0, int c1, float t) {
        float r0 = ((c0 >> 16) & 0xFF) / 255.0F;
        float g0 = ((c0 >> 8) & 0xFF) / 255.0F;
        float b0 = (c0 & 0xFF) / 255.0F;
        float r1 = ((c1 >> 16) & 0xFF) / 255.0F;
        float g1 = ((c1 >> 8) & 0xFF) / 255.0F;
        float b1 = (c1 & 0xFF) / 255.0F;
        int r = Math.round((r0 + (r1 - r0) * t) * 255.0F);
        int g = Math.round((g0 + (g1 - g0) * t) * 255.0F);
        int b = Math.round((b0 + (b1 - b0) * t) * 255.0F);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void drawWrappedCentered(GuiGraphicsExtractor g, int centerX, int centerY, int maxWidth,
            int lineHeight, String text, int color, boolean small) {
        java.util.List<String> lines = wrapText(text, maxWidth, small);
        if (lines.isEmpty()) {
            return;
        }
        float blockH = lines.size() * lineHeight;
        float startY = centerY - blockH / 2.0F + lineHeight / 2.0F;
        for (int i = 0; i < lines.size(); i++) {
            centeredText(g, centerX, Math.round(startY + i * lineHeight), lines.get(i), color, small);
        }
    }

    private java.util.List<String> wrapText(String text, int maxWidth, boolean small) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        Font font = Minecraft.getInstance().font;
        float scale = 1.0F;
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = currentLine.isEmpty() ? word : currentLine + " " + word;
            int candidateWidth = Math.round(font.width(candidate) * scale);
            if (candidateWidth <= maxWidth) {
                if (!currentLine.isEmpty()) {
                    currentLine.append(' ');
                }
                currentLine.append(word);
            } else {
                if (!currentLine.isEmpty()) {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder();
                }
                int wordWidth = Math.round(font.width(word) * scale);
                if (wordWidth <= maxWidth) {
                    currentLine.append(word);
                } else {
                    for (int i = 0; i < word.length(); i++) {
                        String ch = word.substring(i, i + 1);
                        String chCandidate = currentLine.isEmpty() ? ch : currentLine.toString() + ch;
                        int chWidth = Math.round(font.width(chCandidate) * scale);
                        if (chWidth <= maxWidth) {
                            currentLine.append(ch);
                        } else {
                            if (!currentLine.isEmpty()) {
                                lines.add(currentLine.toString());
                            }
                            currentLine = new StringBuilder(ch);
                        }
                    }
                }
            }
        }
        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }
        return lines;
    }

    private void progress(GuiGraphicsExtractor g, int x, int y, int w, int h, float progress, int bg, int fg) {
        fill(g, x, y, w, h, bg);
        int filled = Math.max(h, Math.round(w * Math.max(0.0F, Math.min(1.0F, progress))));
        fill(g, x, y, filled, h, fg);
        fill(g, x + filled - h, y - h / 2, h * 2, h * 2, 0xFF9CA3AD);
        outline(g, x + filled - h, y - h / 2, h * 2, h * 2, 0xFF4B515D);
    }

    private static final int[] PLAY_TRIANGLE_ROWS = { 1, 3, 5, 7, 9, 11, 9, 7, 5, 3, 1 };

    private void prevButton(GuiGraphicsExtractor g, int x, int y, int w, int h, int bg, int fg) {
        fill(g, x + 3, y + 4, w, h, 0x55000000);
        fill(g, x, y, w, h, bg);
        outline(g, x, y, w, h, 0xFF394154);
        int triHSize = 6;
        int triWSize = playTriangleWidth();
        int barW = Math.max(2, triWSize / 2);
        int barH = triHSize * 2;
        int gap = 1;
        int iconW = barW + gap + triWSize;
        int iconStartX = x + w / 2 - iconW / 2;
        int iconCy = y + h / 2;
        fill(g, iconStartX, iconCy - barH / 2, barW, barH, fg);
        drawTriangleBackward(g, iconStartX + barW + gap + triWSize / 2, iconCy, fg);
    }

    private void nextButton(GuiGraphicsExtractor g, int x, int y, int w, int h, int bg, int fg) {
        fill(g, x + 3, y + 4, w, h, 0x55000000);
        fill(g, x, y, w, h, bg);
        outline(g, x, y, w, h, 0xFF394154);
        int triHSize = 6;
        int triWSize = playTriangleWidth();
        int barW = Math.max(2, triWSize / 2);
        int barH = triHSize * 2;
        int gap = 1;
        int iconW = triWSize + gap + barW;
        int iconStartX = x + w / 2 - iconW / 2;
        int iconCy = y + h / 2;
        drawTriangleForward(g, iconStartX + triWSize / 2, iconCy, fg);
        fill(g, iconStartX + triWSize + gap, iconCy - barH / 2, barW, barH, fg);
    }

    private void playPauseButton(GuiGraphicsExtractor g, int x, int y, int w, int h, int bg, int fg,
            boolean playing) {
        fill(g, x + 3, y + 4, w, h, 0x55000000);
        fill(g, x, y, w, h, bg);
        outline(g, x, y, w, h, 0xFF394154);
        if (playing) {
            int barW = Math.max(2, w / 14);
            int barH = Math.min(w, h) / 3;
            drawPauseBars(g, x + w / 2, y + h / 2, barW, barH, Math.max(2, w / 18), fg);
        } else {
            drawTriangleForward(g, x + w / 2, y + h / 2, fg);
        }
    }

    private int playTriangleWidth() {
        return PLAY_TRIANGLE_ROWS[PLAY_TRIANGLE_ROWS.length / 2];
    }

    private void drawTriangleForward(GuiGraphicsExtractor g, int cx, int cy, int color) {
        int left = cx - playTriangleWidth() / 2;
        int top = cy - PLAY_TRIANGLE_ROWS.length / 2;
        for (int row = 0; row < PLAY_TRIANGLE_ROWS.length; row++) {
            fill(g, left, top + row, PLAY_TRIANGLE_ROWS[row], 1, color);
        }
    }

    private void drawTriangleBackward(GuiGraphicsExtractor g, int cx, int cy, int color) {
        int right = cx + playTriangleWidth() / 2;
        int top = cy - PLAY_TRIANGLE_ROWS.length / 2;
        for (int row = 0; row < PLAY_TRIANGLE_ROWS.length; row++) {
            fill(g, right - PLAY_TRIANGLE_ROWS[row] + 1, top + row, PLAY_TRIANGLE_ROWS[row], 1, color);
        }
    }

    private void drawPauseBars(GuiGraphicsExtractor g, int cx, int cy, int barW, int barH, int gap, int color) {
        int halfGap = gap / 2;
        int top = cy - barH / 2;
        fill(g, cx - halfGap - barW, top, barW, barH, color);
        fill(g, cx + halfGap, top, barW, barH, color);
    }

    private void smallToggle(GuiGraphicsExtractor g, int x, int y, int w, int h, int bg, String label) {
        fill(g, x, y, w, h, bg);
        outline(g, x, y, w, h, 0xFF394154);
        centeredText(g, x + w / 2, y + 2, label, 0xFFDCEBFF, true);
    }

    private String gameTimeLabel() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return "--:--";
        }
        long dayTime = minecraft.level.getGameTime() % 24000L;
        int totalMinutes = (int) ((dayTime + 6000L) % 24000L * 1440L / 24000L);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", totalMinutes / 60, totalMinutes % 60);
    }

    private static String formatPlaybackTime(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    private String queuePageLabel(MP4GuiViewState view) {
        int size = view.queueSize();
        return size <= 0 ? "0/0" : (view.queueScrollOffset() + 1) + "/" + size;
    }

    @Override
    public void close() {
        if (registered) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            registered = false;
        }
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        if (projectionBuffer != null) {
            projectionBuffer.close();
            projectionBuffer = null;
        }
        if (fogRenderer != null) {
            fogRenderer.close();
            fogRenderer = null;
        }
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}