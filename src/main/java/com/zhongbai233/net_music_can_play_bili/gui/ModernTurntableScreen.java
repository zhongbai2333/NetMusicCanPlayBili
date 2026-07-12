package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntableSound;
import com.zhongbai233.net_music_can_play_bili.network.ModernTurntableControlPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;

/**
 * 现代唱片机 GUI — 黑金配色 + 频谱可视化 + 音频格式显示
 */
public class ModernTurntableScreen extends BlackGoldScreen {

    private static final int T_BOX_W = 340;
    private static final int T_BOX_H = 210;
    private static final int T_HEADER_H = 28;
    private static final int VIS_BARS = 26;
    private static final int VIS_MAX_H = 24;
    private static final int BTN_W = 54;
    private static final int BTN_H = 22;

    private final float[] barPhases = new float[VIS_BARS];
    private final float[] barFreqs = new float[VIS_BARS];
    private final float[] barLevels = new float[VIS_BARS];
    private float energy = 0.10f;
    private int tickCounter;
    private String cachedFormatLabel = "";
    private int nextFormatLabelRefreshTick;

    private ProgressSlider progressSlider;
    private BlackGoldButton replayButton;
    private BlackGoldButton playPauseButton;
    private BlackGoldButton repeatOneButton;
    private VolumeSlider volumeSlider;

    public ModernTurntableScreen(BlockPos pos) {
        super(Component.literal("♫ 现代唱片机"), pos);
        for (int i = 0; i < VIS_BARS; i++) {
            barPhases[i] = (float) (Math.random() * Math.PI * 2);
            barFreqs[i] = 0.4f + (float) Math.random() * 2.0f;
        }
    }

    @Override
    protected int boxX() {
        return (width - T_BOX_W) / 2;
    }

    @Override
    protected int boxY() {
        return (height - T_BOX_H) / 2;
    }

    @Override
    protected void buildWidgets() {
        int bx = boxX(), by = boxY();
        int progressY = by + T_HEADER_H + 56;
        int controlsY = by + T_BOX_H - BTN_H - 18;

        progressSlider = addRenderableWidget(
                new ProgressSlider(bx + PAD, progressY, T_BOX_W - PAD * 2, 18));

        replayButton = addRenderableWidget(new BlackGoldButton(
                bx + PAD, controlsY, BTN_W, BTN_H,
                Component.literal("⏮ 重播"),
                btn -> sendAction(ModernTurntableControlPacket.Action.REPLAY), GOLD));

        playPauseButton = addRenderableWidget(new BlackGoldButton(
                bx + PAD + BTN_W + 6, controlsY, BTN_W, BTN_H,
                Component.literal("▶ 播放"),
                btn -> onPlayPause(), GOLD));

        repeatOneButton = addRenderableWidget(new BlackGoldButton(
                bx + PAD + (BTN_W + 6) * 2, controlsY, 68, BTN_H,
                Component.literal("🔁 单曲"),
                btn -> sendAction(ModernTurntableControlPacket.Action.TOGGLE_REPEAT_ONE), GOLD));

        volumeSlider = addRenderableWidget(
                new VolumeSlider(bx + T_BOX_W - PAD - 110, controlsY, 110, BTN_H, blockPos));

        refreshWidgets();
    }

    @Override
    protected void onSave() {
    }

    @Override
    public void tick() {
        super.tick();
        tickCounter++;
        var t = turntable();
        boolean playing = t != null && t.isPlaying();
        float audioLevel = playing ? ClientAudioOutputRegistry.audioLevel(blockPos) : 0.0f;
        float target = playing ? Math.max(0.08f, (float) Math.sqrt(audioLevel) * 1.15f) : 0.02f;
        energy += (target - energy) * 0.06f;
        updateVisualizerLevels(playing, audioLevel);
        if (tickCounter >= nextFormatLabelRefreshTick) {
            cachedFormatLabel = resolveFormatLabel();
            nextFormatLabelRefreshTick = tickCounter + 20;
        }
        refreshWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        int bx = boxX(), by = boxY();
        var t = turntable();

        drawBox(g, bx, by);
        drawHeader(g, bx, by, mx, my);
        drawSongInfo(g, bx, by, t);
        drawVisualizer(g, bx, by, pt);
        drawStatusDot(g, bx, by, t);

        renderWidgets(g, mx, my, pt);

        drawProgressOverlay(g, bx, by);
        drawVolumeOverlay(g, bx, by);
    }

    @Override
    protected void drawBox(GuiGraphicsExtractor g, int x, int y) {
        g.fillGradient(x - 2, y - 2, x + T_BOX_W + 2, y + T_BOX_H + 2, GOLD_GLOW, GOLD_GLOW);
        g.fillGradient(x, y, x + T_BOX_W, y + T_BOX_H, BG_BLACK, BG_BLACK);
        g.fillGradient(x + 1, y + 1, x + T_BOX_W - 1, y + 2, 0x40FFFFFF, 0x20FFFFFF);
    }

    @Override
    protected void drawHeader(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        g.fillGradient(bx + 1, by + 1, bx + T_BOX_W - 1, by + T_HEADER_H, BG_HEADER, BG_HEADER);
        g.fillGradient(bx + 8, by + T_HEADER_H - 1, bx + T_BOX_W - 8, by + T_HEADER_H, GOLD_DIM, GOLD_DIM);
        g.centeredText(font, getTitle(), bx + T_BOX_W / 2, by + 9, GOLD);

        int cx = bx + T_BOX_W - CLOSE_SIZE - 8;
        int cy = by + (T_HEADER_H - CLOSE_SIZE) / 2;
        g.centeredText(font, Component.literal("✕"),
                cx + CLOSE_SIZE / 2, cy + 4,
                (mx >= cx && mx <= cx + CLOSE_SIZE && my >= cy && my <= cy + CLOSE_SIZE) ? GOLD : TEXT_SECONDARY);
    }

    private void drawSongInfo(GuiGraphicsExtractor g, int bx, int by, ModernTurntableBlockEntity t) {
        int iy = by + T_HEADER_H + 8;
        int cx = bx + T_BOX_W / 2;
        String song = (t != null && !t.getSongName().isBlank()) ? t.getSongName() : "未选择唱片";
        String displayName = truncateText(song, T_BOX_W - 40, font);
        int songColor = t != null && t.isPlaying() ? GOLD : TEXT_PRIMARY;
        g.centeredText(font, Component.literal(displayName), cx, iy, songColor);

        String fmtLabel = cachedFormatLabel;
        if (fmtLabel.isEmpty() && t != null && t.hasDisc())
            fmtLabel = "CD 唱片";
        if (!fmtLabel.isEmpty())
            g.centeredText(font, Component.literal(fmtLabel), cx, iy + 14, TEXT_SECONDARY);
    }

    private void drawVisualizer(GuiGraphicsExtractor g, int bx, int by, float pt) {
        int baseY = by + T_HEADER_H + 90;
        int leftX = bx + PAD;
        int tw = T_BOX_W - PAD * 2;
        float barW = (float) tw / VIS_BARS;
        float gap = 2f;
        float animTime = (tickCounter + pt) * 0.05f;

        for (int i = 0; i < VIS_BARS; i++) {
            float shimmer = (float) Math.sin(animTime * barFreqs[i] * Math.PI * 0.7 + barPhases[i]) * 0.12f;
            float raw = Math.max(0.03f, barLevels[i] * 0.88f + energy * 0.12f + shimmer);
            float h = Math.max(2f, Math.min(1.0f, raw + energy * 0.35f) * VIS_MAX_H);
            int bx2 = leftX + (int) (i * barW + gap);
            int bw = Math.max(1, (int) barW - (int) gap);
            int top = baseY - (int) h;
            float t = (float) i / VIS_BARS;
            g.fillGradient(bx2, top, bx2 + bw, baseY, lerpColor(GOLD_DIM, GOLD, t), GOLD_DIM);
        }
    }

    private void drawProgressOverlay(GuiGraphicsExtractor g, int bx, int by) {
        if (progressSlider == null)
            return;
        int x = progressSlider.getX(), y = progressSlider.getY();
        int w = progressSlider.getWidth(), h = progressSlider.getHeight();
        double val = progressSlider.getSliderValue();

        g.fillGradient(x - 1, y - 1, x + w + 1, y + h + 1, BG_BLACK, BG_BLACK);

        int pad = 4, trackY = y + h / 2 - 1, trackH = 3;
        int trackLeft = x + pad, trackW = w - pad * 2;
        g.fillGradient(trackLeft, trackY, trackLeft + trackW, trackY + trackH, 0xFF1A1A1A, 0xFF1A1A1A);

        int fillW = (int) (val * trackW);
        if (fillW > 0)
            g.fillGradient(trackLeft, trackY, trackLeft + fillW, trackY + trackH, GOLD_DIM, GOLD);

        int hx = trackLeft + fillW;
        int hr = progressSlider.isHoveredOrFocused() || progressSlider.isUserDragging() ? 5 : 4;
        int hc = progressSlider.isHoveredOrFocused() || progressSlider.isUserDragging() ? GOLD : TEXT_PRIMARY;
        g.fillGradient(hx - hr, trackY - hr + 1, hx + hr, trackY + trackH + hr - 1, hc, hc);
        g.fillGradient(hx - 2, trackY - 1, hx + 2, trackY + trackH + 1, BG_BLACK, BG_BLACK);

        String label = progressSlider.getMessage().getString();
        int lw = font.width(label);
        g.text(font, Component.literal(label), x + (w - lw) / 2, y + h + 1, TEXT_SECONDARY);
    }

    private void drawVolumeOverlay(GuiGraphicsExtractor g, int bx, int by) {
        if (volumeSlider == null)
            return;
        int x = volumeSlider.getX(), y = volumeSlider.getY();
        int w = volumeSlider.getWidth(), h = volumeSlider.getHeight();
        double val = volumeSlider.getSliderValue();

        g.fillGradient(x - 1, y - 1, x + w + 1, y + h + 1, BG_BLACK, BG_BLACK);

        int pad = 4, trackY = y + h / 2 - 2, trackH = 4;
        int trackLeft = x + pad, trackW = w - pad * 2;
        g.fillGradient(trackLeft, trackY, trackLeft + trackW, trackY + trackH, 0xFF1A1A1A, 0xFF1A1A1A);

        int fillW = (int) (val * trackW);
        if (fillW > 0)
            g.fillGradient(trackLeft, trackY, trackLeft + fillW, trackY + trackH, GOLD_DIM, GOLD);

        int hx = trackLeft + fillW;
        int hc = volumeSlider.isHoveredOrFocused() ? GOLD : TEXT_PRIMARY;
        g.fillGradient(hx - 3, trackY - 2, hx + 3, trackY + trackH + 2, hc,
                volumeSlider.isHoveredOrFocused() ? GOLD : TEXT_SECONDARY);
    }

    private void drawStatusDot(GuiGraphicsExtractor g, int bx, int by, ModernTurntableBlockEntity t) {
        int sy = by + T_BOX_H - 10;
        boolean playing = t != null && t.isPlaying();
        float dotPulse = playing ? 0.7f + (float) Math.sin(tickCounter * 0.15f) * 0.3f : 1.0f;
        int dotColor = playing ? GOLD : 0xFF444444;
        int dc = ((int) (dotPulse * 255) << 24) | (dotColor & 0x00FFFFFF);
        g.fillGradient(bx + PAD, sy - 3, bx + PAD + 6, sy + 3, dc, dc);
    }

    // ==================== 可视化 ====================

    private void updateVisualizerLevels(boolean playing, float audioLevel) {
        for (int i = 0; i < VIS_BARS - 1; i++)
            barLevels[i] = barLevels[i + 1] * 0.94f;
        float pulse = playing ? Math.max(0.0f, Math.min(1.0f, audioLevel)) : 0.0f;
        float shaped = (float) Math.sqrt(pulse);
        float motion = 0.82f + 0.18f * (float) Math.sin(tickCounter * 0.41f + barPhases[tickCounter % VIS_BARS]);
        barLevels[VIS_BARS - 1] = Math.max(0.02f, shaped * motion);
        if (!playing || pulse <= 0.001f) {
            for (int i = 0; i < VIS_BARS; i++)
                barLevels[i] *= 0.90f;
        }
    }

    // ==================== 控件 ====================

    private void refreshWidgets() {
        var t = turntable();
        if (t == null) {
            if (progressSlider != null)
                progressSlider.setProgress(0);
            return;
        }
        boolean hasPlayback = t.hasPlaybackData();
        boolean playing = t.isPlaying();
        int dur = t.getDurationSeconds();
        long elapsed = currentElapsedMillis();
        double progress = dur <= 0 ? 0.0 : elapsed / (dur * 1000.0);

        if (progressSlider != null && !progressSlider.isUserDragging())
            progressSlider.setProgress(progress);
        if (replayButton != null)
            replayButton.active = t.hasDisc();
        if (playPauseButton != null) {
            playPauseButton.active = playing || hasPlayback || t.hasDisc();
            playPauseButton.setMessage(Component.literal(playing ? "⏸ 暂停" : "▶ 播放"));
        }
        if (repeatOneButton != null) {
            repeatOneButton.active = t.hasDisc() || hasPlayback;
            repeatOneButton.setMessage(Component.literal(t.isRepeatOne() ? "🔂 循环中" : "🔁 单曲"));
        }
        if (progressSlider != null)
            progressSlider.active = hasPlayback && dur > 0;
    }

    private void onPlayPause() {
        var t = turntable();
        if (t == null)
            return;
        send(t.isPlaying()
                ? ModernTurntableControlPacket.Action.PAUSE
                : ModernTurntableControlPacket.Action.START, currentSliderMillis());
    }

    // ==================== 网络 ====================

    private void sendAction(ModernTurntableControlPacket.Action action) {
        send(action, currentSliderMillis());
    }

    private void send(ModernTurntableControlPacket.Action action, long targetMillis) {
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn != null)
            ((ICommonPacketListener) conn)
                    .send(new ModernTurntableControlPacket(blockPos, action, Math.max(0L, targetMillis)));
    }

    private ModernTurntableBlockEntity turntable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return null;
        BlockEntity be = mc.level.getBlockEntity(blockPos);
        return be instanceof ModernTurntableBlockEntity t ? t : null;
    }

    private long currentElapsedMillis() {
        var t = turntable();
        if (Minecraft.getInstance().level == null || t == null)
            return 0L;
        return t.getPlaybackElapsedMillis(Minecraft.getInstance().level.getGameTime());
    }

    private long currentSliderMillis() {
        var t = turntable();
        if (t == null || t.getDurationSeconds() <= 0 || progressSlider == null) {
            return currentElapsedMillis();
        }
        return Math.round(progressSlider.getSliderValue() * t.getDurationSeconds() * 1000.0D);
    }

    // ==================== 格式 ====================

    private static String resolveFormatLabel() {
        String codec = BiliPlaybackDiagnostics.currentCodecSummary();
        if (codec == null || codec.equals("unknown / unknown") || codec.contains("resolving"))
            return "";
        if (!codec.contains("unknown"))
            return formatCodec(codec);
        return getFormatLabel();
    }

    private static String getFormatLabel() {
        try {
            var diag = BiliPlaybackDiagnostics.describeCurrentPlayback();
            if (diag instanceof java.util.List<?> lines && !lines.isEmpty()) {
                for (Object line : lines) {
                    String s = line.toString();
                    if (s.startsWith("容器/编码: ")) {
                        String codec = s.substring(s.indexOf(": ") + 2);
                        if (!codec.equals("unknown") && !codec.equals("resolving"))
                            return formatCodec(codec);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String formatCodec(String raw) {
        String[] parts = raw.split(" / ");
        String codec = parts.length > 1 ? parts[1].trim().toLowerCase() : raw.trim().toLowerCase();
        return switch (codec) {
            case "flac" -> "♫ FLAC 无损";
            case "ec-3" -> "♪ Dolby Digital Plus";
            case "aac" -> "♫ AAC 高音质";
            default -> raw.contains("/") ? raw.substring(raw.lastIndexOf('/') + 1).trim().toUpperCase() : raw;
        };
    }

    // ==================== 工具 ====================

    private static String formatTime(long millis) {
        long s = Math.max(0L, millis / 1000L);
        return (s / 60L) + ":" + ((s % 60L) < 10L ? "0" : "") + (s % 60L);
    }

    private static String truncateText(String text, int maxW, Font font) {
        if (font.width(text) <= maxW)
            return text;
        String ellipsis = "...";
        int ew = font.width(ellipsis);
        StringBuilder sb = new StringBuilder();
        int cw = 0;
        for (char c : text.toCharArray()) {
            if (cw + font.width(String.valueOf(c)) + ew > maxW)
                break;
            sb.append(c);
            cw += font.width(String.valueOf(c));
        }
        return sb.append(ellipsis).toString();
    }

    private static int lerpColor(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return (((int) (((c1 >> 24) & 0xFF) + (((c2 >> 24) & 0xFF) - ((c1 >> 24) & 0xFF)) * t)) << 24)
                | (((int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t)) << 16)
                | (((int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t)) << 8)
                | ((int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t));
    }

    // ==================== 内部控件类 ====================

    private final class VolumeSlider extends AbstractSliderButton {
        VolumeSlider(int x, int y, int w, int h, BlockPos sourcePos) {
            super(x, y, w, h, Component.literal(""),
                    gainToSlider(ClientAudioOutputRegistry.userVolume(sourcePos)));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float gain = sliderToGain((float) this.value);
            ModernTurntableSound.clientVolume = gain;
            ClientAudioOutputRegistry.setUserVolume(blockPos, gain);
            setMessage(Component.literal((int) (this.value * 100) + "%"));
        }

        @Override
        protected void applyValue() {
            updateMessage();
        }

        double getSliderValue() {
            return this.value;
        }

        private static float sliderToGain(float slider) {
            return slider * slider;
        }

        private static float gainToSlider(float gain) {
            return (float) Math.sqrt(Math.max(0f, gain));
        }
    }

    private final class ProgressSlider extends AbstractSliderButton {
        private boolean userDragging;

        ProgressSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.literal(""), 0.0D);
            updateMessage();
        }

        boolean isUserDragging() {
            return userDragging;
        }

        double getSliderValue() {
            return this.value;
        }

        void setProgress(double p) {
            this.value = Math.max(0.0, Math.min(1.0, p));
            updateMessage();
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean cancelled) {
            userDragging = true;
            super.onClick(event, cancelled);
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            userDragging = false;
            var t = turntable();
            if (t == null || t.getDurationSeconds() <= 0)
                return;
            send(ModernTurntableControlPacket.Action.SEEK,
                    Math.round(value * t.getDurationSeconds() * 1000.0));
        }

        @Override
        protected void updateMessage() {
            var t = turntable();
            long dur = t != null ? t.getDurationSeconds() * 1000L : 0L;
            long val = Math.round(value * dur);
            setMessage(Component.literal(formatTime(val) + " / " + formatTime(dur)));
        }

        @Override
        protected void applyValue() {
            updateMessage();
        }
    }
}
