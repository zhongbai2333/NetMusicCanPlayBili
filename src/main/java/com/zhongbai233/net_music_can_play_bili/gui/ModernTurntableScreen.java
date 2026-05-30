package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntableSound;
import com.zhongbai233.net_music_can_play_bili.network.ModernTurntableControlPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;

/**
 * 摩登唱片机 GUI — 黑金配色 + 频谱可视化 + 音频格式显示
 */
public class ModernTurntableScreen extends Screen {

    // ---- Layout ----
    private static final int BOX_W = 340;
    private static final int BOX_H = 210;
    private static final int HEADER_H = 28;
    private static final int VIS_BARS = 26;
    private static final int VIS_MAX_H = 24;
    private static final int CLOSE_SIZE = 14;
    private static final int BTN_W = 54;
    private static final int BTN_H = 22;
    private static final int PAD = 16;

    // ---- Black-Gold palette ----
    private static final int GOLD = 0xFFD4A843;
    private static final int GOLD_DIM = 0xFF6B4F12;
    private static final int GOLD_GLOW = 0x30D4A843;
    private static final int BG_BLACK = 0xFF0D0D0D;
    private static final int BG_HEADER = 0xFF1C1C1C;
    private static final int TEXT_PRIMARY = 0xFFE0D8C8;
    private static final int TEXT_SECONDARY = 0xFFA09888;
    private static final int TEXT_DIM = 0xFF605848;

    // ---- Animation ----
    private final float[] barPhases = new float[VIS_BARS];
    private final float[] barFreqs = new float[VIS_BARS];
    private final float[] barLevels = new float[VIS_BARS];
    private float energy = 0.10f;
    private int tickCounter;
    private String cachedFormatLabel = "";
    private int nextFormatLabelRefreshTick;

    // ---- Widgets ----
    private final BlockPos pos;
    private ProgressSlider progressSlider;
    private Button replayButton;
    private Button playPauseButton;
    private VolumeSlider volumeSlider;
    private boolean closeHovered;

    public ModernTurntableScreen(BlockPos pos) {
        super(Component.literal("现代唱片机"));
        this.pos = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
        for (int i = 0; i < VIS_BARS; i++) {
            barPhases[i] = (float) (Math.random() * Math.PI * 2);
            barFreqs[i] = 0.4f + (float) Math.random() * 2.0f;
        }
    }

    // ==================== Lifecycle ====================

    @Override
    protected void init() {
        int bx = boxX();
        int by = boxY();

        int progressY = by + HEADER_H + 56;
        int controlsY = by + BOX_H - BTN_H - 18;

        this.progressSlider = addRenderableWidget(
                new ProgressSlider(bx + PAD, progressY, BOX_W - PAD * 2, 18));

        this.replayButton = addRenderableWidget(
                new ModernButton(bx + PAD, controlsY, BTN_W, BTN_H,
                        Component.literal("⏮ 重播"),
                        btn -> sendAction(ModernTurntableControlPacket.Action.REPLAY), GOLD));

        this.playPauseButton = addRenderableWidget(
                new ModernButton(bx + PAD + BTN_W + 6, controlsY, BTN_W, BTN_H,
                        Component.literal("▶ 播放"), this::onPlayPause, GOLD));

        this.volumeSlider = addRenderableWidget(
                new VolumeSlider(bx + BOX_W - PAD - 110, controlsY, 110, BTN_H, pos));

        refreshWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        tickCounter++;
        var t = turntable();
        boolean playing = t != null && t.isPlaying();
        float audioLevel = playing ? DolbyAudioRegistry.audioLevel(pos) : 0.0f;
        float target = playing ? Math.max(0.08f, (float) Math.sqrt(audioLevel) * 1.15f) : 0.02f;
        energy += (target - energy) * 0.06f;
        updateVisualizerLevels(playing, audioLevel);
        if (tickCounter >= nextFormatLabelRefreshTick) {
            cachedFormatLabel = resolveFormatLabel();
            nextFormatLabelRefreshTick = tickCounter + 20;
        }
        refreshWidgets();
    }

    // ==================== Rendering ====================

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fillGradient(0, 0, width, height, 0xCC000000, 0xDD050505);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        int bx = boxX(), by = boxY();
        var t = turntable();
        var font = this.font;

        drawBox(g, bx, by);
        drawHeader(g, bx, by, mx, my, font);
        drawSongInfo(g, bx, by, t, font);
        drawVisualizer(g, bx, by, pt);
        drawStatusDot(g, bx, by, t);

        super.extractRenderState(g, mx, my, pt);

        drawProgressOverlay(g, bx, by, font);
        drawVolumeOverlay(g, bx, by, font);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean cancelled) {
        if (cancelled)
            return false;
        int bx = boxX(), by = boxY();
        int cx = bx + BOX_W - CLOSE_SIZE - 8;
        int cy = by + (HEADER_H - CLOSE_SIZE) / 2;
        if (event.x() >= cx && event.x() <= cx + CLOSE_SIZE
                && event.y() >= cy && event.y() <= cy + CLOSE_SIZE) {
            onClose();
            return true;
        }
        return super.mouseClicked(event, cancelled);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null)
            minecraft.setScreen(null);
    }

    // ==================== Drawing ====================

    private void drawBox(GuiGraphicsExtractor g, int x, int y) {
        g.fillGradient(x - 2, y - 2, x + BOX_W + 2, y + BOX_H + 2, GOLD_GLOW, GOLD_GLOW);
        g.fillGradient(x, y, x + BOX_W, y + BOX_H, BG_BLACK, BG_BLACK);
        g.fillGradient(x + 1, y + 1, x + BOX_W - 1, y + 2, 0x40FFFFFF, 0x20FFFFFF);
    }

    private void drawHeader(GuiGraphicsExtractor g, int bx, int by,
            int mx, int my, net.minecraft.client.gui.Font font) {
        g.fillGradient(bx + 1, by + 1, bx + BOX_W - 1, by + HEADER_H, BG_HEADER, BG_HEADER);
        g.fillGradient(bx + 8, by + HEADER_H - 1, bx + BOX_W - 8, by + HEADER_H, GOLD_DIM, GOLD_DIM);
        g.centeredText(font, "♫ 现代唱片机", bx + BOX_W / 2, by + 9, GOLD);

        int cx = bx + BOX_W - CLOSE_SIZE - 8;
        int cy = by + (HEADER_H - CLOSE_SIZE) / 2;
        closeHovered = mx >= cx && mx <= cx + CLOSE_SIZE && my >= cy && my <= cy + CLOSE_SIZE;
        g.centeredText(font, "✕", cx + CLOSE_SIZE / 2, cy + 4, closeHovered ? GOLD : TEXT_SECONDARY);
    }

    private void drawSongInfo(GuiGraphicsExtractor g, int bx, int by,
            ModernTurntableBlockEntity t, net.minecraft.client.gui.Font font) {
        int iy = by + HEADER_H + 8;
        int cx = bx + BOX_W / 2;
        String song = (t != null && !t.getSongName().isBlank()) ? t.getSongName() : "未选择唱片";
        String displayName = truncateText(song, BOX_W - 40, font);
        int songColor = t != null && t.isPlaying() ? GOLD : TEXT_PRIMARY;
        g.centeredText(font, displayName, cx, iy, songColor);

        String formatLabel = cachedFormatLabel;
        if (formatLabel.isEmpty() && t != null && t.hasDisc())
            formatLabel = "CD 唱片";
        if (!formatLabel.isEmpty())
            g.centeredText(font, formatLabel, cx, iy + 14, TEXT_SECONDARY);
    }

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
        switch (codec) {
            case "flac":
                return "♫ FLAC 无损";
            case "ec-3":
                return "♪ Dolby Digital Plus";
            case "aac":
                return "♫ AAC 高音质";
            default:
                return raw.contains("/") ? raw.substring(raw.lastIndexOf('/') + 1).trim().toUpperCase() : raw;
        }
    }

    private void drawVisualizer(GuiGraphicsExtractor g, int bx, int by, float pt) {
        int baseY = by + HEADER_H + 90;
        int leftX = bx + PAD;
        int tw = BOX_W - PAD * 2;
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

    private void updateVisualizerLevels(boolean playing, float audioLevel) {
        for (int i = 0; i < VIS_BARS - 1; i++) {
            barLevels[i] = barLevels[i + 1] * 0.94f;
        }
        float pulse = playing ? Math.max(0.0f, Math.min(1.0f, audioLevel)) : 0.0f;
        float shaped = (float) Math.sqrt(pulse);
        float motion = 0.82f + 0.18f * (float) Math.sin(tickCounter * 0.41f + barPhases[tickCounter % VIS_BARS]);
        barLevels[VIS_BARS - 1] = Math.max(0.02f, shaped * motion);
        if (!playing || pulse <= 0.001f) {
            for (int i = 0; i < VIS_BARS; i++) {
                barLevels[i] *= 0.90f;
            }
        }
    }

    private void drawProgressOverlay(GuiGraphicsExtractor g, int bx, int by,
            net.minecraft.client.gui.Font font) {
        if (progressSlider == null)
            return;
        int x = progressSlider.getX(), y = progressSlider.getY();
        int w = progressSlider.getWidth(), h = progressSlider.getHeight();
        double val = progressSlider.getSliderValue();

        g.fillGradient(x - 1, y - 1, x + w + 1, y + h + 1, BG_BLACK, BG_BLACK);

        // Vanilla slider uses 4px inner padding for mouse→value mapping
        int pad = 4;
        int trackY = y + h / 2 - 1;
        int trackH = 3;
        int trackLeft = x + pad;
        int trackW = w - pad * 2;
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

    private void drawVolumeOverlay(GuiGraphicsExtractor g, int bx, int by,
            net.minecraft.client.gui.Font font) {
        if (volumeSlider == null)
            return;
        int x = volumeSlider.getX(), y = volumeSlider.getY();
        int w = volumeSlider.getWidth(), h = volumeSlider.getHeight();
        double val = volumeSlider.getSliderValue();

        g.fillGradient(x - 1, y - 1, x + w + 1, y + h + 1, BG_BLACK, BG_BLACK);

        // Match vanilla slider's 4px inner padding
        int pad = 4;
        int trackY = y + h / 2 - 2;
        int trackH = 4;
        int trackLeft = x + pad;
        int trackW = w - pad * 2;
        g.fillGradient(trackLeft, trackY, trackLeft + trackW, trackY + trackH, 0xFF1A1A1A, 0xFF1A1A1A);

        int fillW = (int) (val * trackW);
        if (fillW > 0)
            g.fillGradient(trackLeft, trackY, trackLeft + fillW, trackY + trackH, GOLD_DIM, GOLD);

        int hx = trackLeft + fillW;
        int hc = volumeSlider.isHoveredOrFocused() ? GOLD : TEXT_PRIMARY;
        g.fillGradient(hx - 3, trackY - 2, hx + 3, trackY + trackH + 2, hc,
                volumeSlider.isHoveredOrFocused() ? GOLD : TEXT_SECONDARY);
    }

    private void drawStatusDot(GuiGraphicsExtractor g, int bx, int by,
            ModernTurntableBlockEntity t) {
        int sy = by + BOX_H - 10;
        boolean playing = t != null && t.isPlaying();

        int dotX = bx + PAD;
        int dotColor = playing ? GOLD : 0xFF444444;
        float dotPulse = playing ? 0.7f + (float) Math.sin(tickCounter * 0.15f) * 0.3f : 1.0f;
        int dotAlpha = (int) (dotPulse * 255);
        int dc = (dotAlpha << 24) | (dotColor & 0x00FFFFFF);
        g.fillGradient(dotX, sy - 3, dotX + 6, sy + 3, dc, dc);
    }

    // ==================== Widget mgmt ====================

    private void refreshWidgets() {
        var t = turntable();
        if (t == null) {
            setAllActive(false);
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
        if (progressSlider != null)
            progressSlider.active = hasPlayback && dur > 0;
    }

    private void setAllActive(boolean a) {
        if (progressSlider != null) {
            progressSlider.active = a;
            progressSlider.setProgress(0);
        }
        if (replayButton != null)
            replayButton.active = a;
        if (playPauseButton != null)
            playPauseButton.active = a;
    }

    private void onPlayPause(Button btn) {
        var t = turntable();
        if (t == null)
            return;
        sendAction(t.isPlaying()
                ? ModernTurntableControlPacket.Action.PAUSE
                : ModernTurntableControlPacket.Action.START);
    }

    // ==================== Network ====================

    private void sendAction(ModernTurntableControlPacket.Action action) {
        send(action, currentElapsedMillis());
    }

    private void send(ModernTurntableControlPacket.Action action, long targetMillis) {
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn != null)
            ((ICommonPacketListener) conn)
                    .send(new ModernTurntableControlPacket(pos, action, Math.max(0L, targetMillis)));
    }

    // ==================== Data ====================

    private int boxX() {
        return (width - BOX_W) / 2;
    }

    private int boxY() {
        return (height - BOX_H) / 2;
    }

    private ModernTurntableBlockEntity turntable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return null;
        BlockEntity be = mc.level.getBlockEntity(pos);
        return be instanceof ModernTurntableBlockEntity t ? t : null;
    }

    private long currentElapsedMillis() {
        Minecraft mc = Minecraft.getInstance();
        var t = turntable();
        if (mc.level == null || t == null)
            return 0L;
        return t.getPlaybackElapsedMillis(mc.level.getGameTime());
    }

    // ==================== Utils ====================

    private static String formatTime(long millis) {
        long s = Math.max(0L, millis / 1000L);
        long minutes = s / 60L;
        long seconds = s % 60L;
        return minutes + ":" + (seconds < 10L ? "0" : "") + seconds;
    }

    private static String truncateText(String text, int maxW, net.minecraft.client.gui.Font font) {
        if (font.width(text) <= maxW)
            return text;
        String ellipsis = "...";
        int ew = font.width(ellipsis);
        StringBuilder sb = new StringBuilder();
        int cw = 0;
        for (char c : text.toCharArray()) {
            int cw2 = font.width(String.valueOf(c));
            if (cw + cw2 + ew > maxW)
                break;
            sb.append(c);
            cw += cw2;
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

    // ==================== Inner widgets ====================

    private static final class ModernButton extends Button {
        private final int accentColor;

        ModernButton(int x, int y, int w, int h, Component msg, OnPress onPress, int accent) {
            super(x, y, w, h, msg, onPress, DEFAULT_NARRATION);
            this.accentColor = accent;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float pt) {
            var font = Minecraft.getInstance().font;
            int bg, borderC;
            if (!this.active) {
                bg = 0xFF111111;
                borderC = 0xFF222222;
            } else if (this.isHoveredOrFocused()) {
                bg = 0xFF2A2A20;
                borderC = accentColor;
            } else {
                bg = 0xFF1A1A1A;
                borderC = 0xFF333333;
            }
            g.fillGradient(getX(), getY(), getX() + width, getY() + height, bg, bg);
            g.fillGradient(getX(), getY(), getX() + 2, getY() + height, borderC, borderC);
            g.fillGradient(getX(), getY() + height - 1, getX() + width, getY() + height, borderC, borderC);

            int tc = this.active ? (this.isHoveredOrFocused() ? accentColor : TEXT_PRIMARY) : TEXT_DIM;
            g.centeredText(font, getMessage(), getX() + width / 2, getY() + (height - 8) / 2, tc);
        }
    }

    private static final class VolumeSlider extends AbstractSliderButton {
        private final BlockPos sourcePos;

        VolumeSlider(int x, int y, int w, int h, BlockPos sourcePos) {
            super(x, y, w, h, Component.literal(""), DolbyAudioRegistry.userVolume(sourcePos));
            this.sourcePos = new BlockPos(sourcePos.getX(), sourcePos.getY(), sourcePos.getZ());
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            ModernTurntableSound.clientVolume = (float) this.value;
            DolbyAudioRegistry.setUserVolume(sourcePos, (float) this.value);
            setMessage(Component.literal((int) (this.value * 100) + "%"));
        }

        @Override
        protected void applyValue() {
            updateMessage();
        }

        double getSliderValue() {
            return this.value;
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
