package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.client.MP4ClientPlayback;
import com.zhongbai233.net_music_can_play_bili.client.audio.AudioDurationProbe;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaTimelineView;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistPreviewPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewActionPacket;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.OptionalLong;
import java.util.UUID;

/** 白名单审核时使用的音视频预览界面。 */
public class WhitelistPreviewScreen extends Screen {
    private static final int BOX_W = 460;
    private static final int BOX_H = 364;
    private static final int HEADER_H = 28;
    private static final int VIDEO_W = 408;
    private static final int VIDEO_H = 230;
    private static final int PROGRESS_H = 8;
    private static final int CLOSE_SIZE = 14;
    private static final int PREVIEW_QUALITY = 32;

    private WhitelistPreviewPacket payload;
    private float previewProgress;
    private long probedDurationMillis;
    private long pausedAtMillis = -1L;
    private boolean locallyPaused;
    private boolean scrubbing;
    private boolean closeHovered;
    private String durationProbeKey = "";
    private String pendingVideoSessionId = "";
    private String resolvingVideoKey = "";

    public WhitelistPreviewScreen(WhitelistPreviewPacket payload) {
        super(Component.literal("白名单视频预览"));
        this.payload = payload;
        this.previewProgress = progressFrom(payload);
        beginDurationProbe(payload);
    }

    public static void openOrUpdate(WhitelistPreviewPacket payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof WhitelistPreviewScreen screen) {
            screen.update(payload);
        } else {
            minecraft.setScreen(new WhitelistPreviewScreen(payload));
        }
    }

    private void update(WhitelistPreviewPacket next) {
        stopCurrentPlayback();
        this.payload = next;
        this.probedDurationMillis = 0L;
        this.durationProbeKey = "";
        this.previewProgress = progressFrom(next);
        this.pausedAtMillis = -1L;
        this.locallyPaused = !next.playing();
        this.resolvingVideoKey = "";
        beginDurationProbe(next);
        startPlayback(next);
        rebuildButtons();
    }

    @Override
    protected void init() {
        beginDurationProbe(payload);
        rebuildButtons();
        startPlayback(payload);
    }

    private void rebuildButtons() {
        clearWidgets();
        int bx = boxX();
        int by = boxY();
        BlackGoldButton previous = new BlackGoldButton(bx + 112, controlY(by), 64, 20,
                Component.literal("上一个"), button -> previewSibling(-1), BlackGoldUi.GOLD);
        previous.active = siblingId(-1) != null;
        addRenderableWidget(previous);
        addRenderableWidget(new BlackGoldButton(bx + 198, controlY(by), 64, 20,
                Component.literal(locallyPaused ? "继续" : "暂停"), button -> togglePause(), BlackGoldUi.GOLD));
        BlackGoldButton next = new BlackGoldButton(bx + 284, controlY(by), 64, 20,
                Component.literal("下一个"), button -> previewSibling(1), BlackGoldUi.GOLD);
        next.active = siblingId(1) != null;
        addRenderableWidget(next);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        stopCurrentPlayback();
        returnToReviewMenu();
    }

    @Override
    public void tick() {
        maybeStartPendingVideo();
        if (!scrubbing) {
            float live = liveProgress();
            if (live >= 0.0F) {
                previewProgress = live;
            }
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        BlackGoldUi.drawBackground(g, width, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        int bx = boxX();
        int by = boxY();
        BlackGoldUi.drawPanel(g, bx, by, BOX_W, BOX_H);
        drawHeader(g, bx, by, mx, my);
        drawVideo(g, bx, by);
        drawProgress(g, bx, by, mx, my);
        drawFooter(g, bx, by);
        super.extractRenderState(g, mx, my, pt);
    }

    private void drawHeader(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        BlackGoldUi.drawHeader(g, font, getTitle(), bx, by, BOX_W, HEADER_H);
        int cx = bx + BOX_W - CLOSE_SIZE - 8;
        int cy = by + (HEADER_H - CLOSE_SIZE) / 2;
        closeHovered = mx >= cx && mx <= cx + CLOSE_SIZE && my >= cy && my <= cy + CLOSE_SIZE;
        g.centeredText(font, Component.literal("✕"), cx + CLOSE_SIZE / 2, cy + 4,
                closeHovered ? BlackGoldUi.GOLD : BlackGoldUi.TEXT_SECONDARY);
        g.text(font, Component.literal(trim(payload.title(), 58)), bx + 16, by + 34, BlackGoldUi.TEXT_SECONDARY, false);
    }

    private void drawVideo(GuiGraphicsExtractor g, int bx, int by) {
        String sessionId = WhitelistPreviewPacket.sessionId(payload.previewId(), payload.elapsedMillis());
        int x = videoX(bx);
        int y = by + 52;
        g.fillGradient(x - 2, y - 2, x + VIDEO_W + 2, y + VIDEO_H + 2, BlackGoldUi.GOLD_DIM, BlackGoldUi.GOLD_DIM);
        g.fillGradient(x, y, x + VIDEO_W, y + VIDEO_H, 0xFF050505, 0xFF101010);
        if (!hasVideo()) {
            g.centeredText(font, Component.literal("纯音频预览"), x + VIDEO_W / 2, y + VIDEO_H / 2 - 14,
                    BlackGoldUi.GOLD);
            g.centeredText(font, Component.literal("此条目没有视频画面，正在播放音频"),
                    x + VIDEO_W / 2, y + VIDEO_H / 2 + 2, BlackGoldUi.TEXT_DIM);
            return;
        }
        VideoBillboardPreview.pumpPreviewFrame(sessionId);
        VideoBillboardPreview.ProjectorFrameSnapshot frame = VideoBillboardPreview.currentPreviewFrame(sessionId);
        if (frame.hasFrame() && !frame.yuv() && frame.rgbaTexture() != null) {
            g.blit(frame.rgbaTexture(), x, y, x + VIDEO_W, y + VIDEO_H, 0.0F, 1.0F, 0.0F, 1.0F);
            return;
        }
        String text = frame.hasFrame() && frame.yuv()
                ? "正在准备视频画面..."
                : "正在加载视频画面...";
        g.centeredText(font, Component.literal(text), x + VIDEO_W / 2, y + VIDEO_H / 2 - 6, BlackGoldUi.TEXT_DIM);
    }

    private void drawProgress(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        int x = progressX(bx);
        int y = progressY(by);
        int w = progressW();
        boolean hovered = mx >= x && mx <= x + w && my >= y - 5 && my <= y + PROGRESS_H + 5;
        g.fillGradient(x, y, x + w, y + PROGRESS_H, 0xFF273044, 0xFF273044);
        float renderedProgress = scrubbing ? previewProgress : displayProgress();
        int filled = Math.round(w * clamp01(renderedProgress));
        g.fillGradient(x, y, x + filled, y + PROGRESS_H, BlackGoldUi.GOLD, BlackGoldUi.GOLD);
        int knob = x + filled;
        int radius = hovered || scrubbing ? 5 : 4;
        g.fillGradient(knob - radius, y + PROGRESS_H / 2 - radius, knob + radius, y + PROGRESS_H / 2 + radius,
                0xFFE8C46B, 0xFFE8C46B);
        String totalText = totalMillis() > 0L ? timeText(totalMillis()) : "--:--";
        g.text(font, Component.literal(timeText(displayMillis()) + " / " + totalText),
                x, y + 14, BlackGoldUi.TEXT_SECONDARY, false);
    }

    private void drawFooter(GuiGraphicsExtractor g, int bx, int by) {
        g.text(font, Component.literal("可拖动进度条调整播放位置"),
                bx + 16, by + BOX_H - 20, BlackGoldUi.TEXT_DIM, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean cancelled) {
        if (cancelled) {
            return false;
        }
        int bx = boxX();
        int by = boxY();
        int cx = bx + BOX_W - CLOSE_SIZE - 8;
        int cy = by + (HEADER_H - CLOSE_SIZE) / 2;
        if (event.x() >= cx && event.x() <= cx + CLOSE_SIZE && event.y() >= cy && event.y() <= cy + CLOSE_SIZE) {
            onClose();
            return true;
        }
        if (hitProgress(bx, by, event.x(), event.y())) {
            scrubbing = true;
            updateScrub(bx, event.x());
            return true;
        }
        return super.mouseClicked(event, cancelled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (scrubbing) {
            updateScrub(boxX(), event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (scrubbing) {
            scrubbing = false;
            ClientPacketDistributor.sendToServer(new WhitelistReviewActionPacket(
                    WhitelistReviewActionPacket.Action.PREVIEW_SEEK, payload.rawUrl(), currentMillis()));
            return true;
        }
        return super.mouseReleased(event);
    }

    private void startPlayback(WhitelistPreviewPacket packet) {
        if (packet == null) {
            return;
        }
        if (locallyPaused || !packet.playing()) {
            return;
        }
        MP4ClientPlayback.handleSync(toLocalAudioSync(packet));
        if (!hasVideo(packet)) {
            return;
        }
        String sessionId = WhitelistPreviewPacket.sessionId(packet.previewId(), packet.elapsedMillis());
        pendingVideoSessionId = sessionId;
        if (!MP4ClientPlayback.hasStartedSound(packet.previewId(), sessionId)) {
            return;
        }
        startPreviewVideo(packet, sessionId);
    }

    private void maybeStartPendingVideo() {
        if (payload == null || locallyPaused || !payload.playing() || !hasVideo(payload)
                || pendingVideoSessionId == null || pendingVideoSessionId.isBlank()) {
            return;
        }
        String sessionId = WhitelistPreviewPacket.sessionId(payload.previewId(), payload.elapsedMillis());
        if (!sessionId.equals(pendingVideoSessionId)
                || !MP4ClientPlayback.hasStartedSound(payload.previewId(), sessionId)) {
            return;
        }
        startPreviewVideo(payload, sessionId);
    }

    private void startPreviewVideo(WhitelistPreviewPacket packet, String sessionId) {
        pendingVideoSessionId = "";
        if (BiliVideoStreamResolver.isStoredVideoSelection(packet.videoUrl())) {
            resolveAndStartBiliPreviewVideo(packet, sessionId);
            return;
        }
        VideoBillboardPreview.startRgbaPreviewAt(packet.videoUrl(), packet.videoWidth(), packet.videoHeight(),
                packet.fps(), packet.codecId(), sessionId, packet.elapsedMillis(), totalMillis(), true, null,
                packet.previewId());
    }

    private void resolveAndStartBiliPreviewVideo(WhitelistPreviewPacket packet, String sessionId) {
        String key = sessionId + ':' + packet.videoUrl();
        if (key.equals(resolvingVideoKey)) {
            return;
        }
        resolvingVideoKey = key;
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return BiliVideoStreamResolver.resolve(packet.videoUrl(), PREVIEW_QUALITY);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).whenComplete((stream, error) -> Minecraft.getInstance().execute(() -> {
            if (payload == null || !packet.previewId().equals(payload.previewId())
                    || !sessionId
                            .equals(WhitelistPreviewPacket.sessionId(payload.previewId(), payload.elapsedMillis()))) {
                return;
            }
            resolvingVideoKey = "";
            if (error != null || stream == null) {
                return;
            }
            VideoBillboardPreview.startRgbaPreviewAt(stream.url(), stream.sourceWidth(), stream.sourceHeight(),
                    stream.fps(), stream.codecId(), sessionId, packet.elapsedMillis(), totalMillis(), true, null,
                    packet.previewId());
        }));
    }

    private void returnToReviewMenu() {
        WhitelistReviewPacket last = WhitelistReviewScreen.lastPayload();
        if (last != null) {
            Minecraft.getInstance().setScreen(new WhitelistReviewScreen(last));
        } else {
            Minecraft.getInstance().setScreen(null);
        }
    }

    private MP4PlaybackSyncPacket toLocalAudioSync(WhitelistPreviewPacket packet) {
        int playerEntityId = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getId() : -1;
        return new MP4PlaybackSyncPacket(packet.previewId(), packet.previewId(), MP4PlaybackSyncPacket.SOURCE_PLAYER,
                playerEntityId, 0.0D, 0.0D, 0.0D, packet.playing(), 0, packet.audioUrl(), packet.rawUrl(),
                packet.title(), packet.durationSeconds(), 850,
                WhitelistPreviewPacket.sessionId(packet.previewId(), packet.elapsedMillis()),
                packet.elapsedMillis(), false);
    }

    private void stopCurrentPlayback() {
        UUID previewId = payload != null ? payload.previewId() : null;
        if (previewId != null) {
            String sessionId = WhitelistPreviewPacket.sessionId(previewId, payload.elapsedMillis());
            pendingVideoSessionId = "";
            MP4ClientPlayback.handleSync(WhitelistPreviewPacket.stopAudio(previewId));
            VideoBillboardPreview.stopIfSession(sessionId);
        }
    }

    private float liveProgress() {
        long duration = timelineDurationMillis();
        long elapsed = displayMillis();
        if (duration <= 0L || elapsed < 0L) {
            return -1.0F;
        }
        return clamp01(elapsed / (float) duration);
    }

    private float displayProgress() {
        long duration = timelineDurationMillis();
        if (duration <= 0L) {
            return previewProgress;
        }
        return clamp01(displayMillis() / (float) duration);
    }

    private long currentMillis() {
        return Math.round(previewProgress * totalMillis());
    }

    private long displayMillis() {
        if (locallyPaused && pausedAtMillis >= 0L) {
            return pausedAtMillis;
        }
        String sessionId = WhitelistPreviewPacket.sessionId(payload.previewId(), payload.elapsedMillis());
        return timelineView(sessionId).mediaMillis();
    }

    private long timelineDurationMillis() {
        String sessionId = WhitelistPreviewPacket.sessionId(payload.previewId(), payload.elapsedMillis());
        long total = timelineView(sessionId).totalMillis();
        return total > 0L ? total : totalMillis();
    }

    private ClientMediaTimelineView timelineView(String sessionId) {
        return ClientMediaTimelineView.forMp4Owner(payload.previewId(), sessionId, payload.elapsedMillis(),
                totalMillis());
    }

    private long totalMillis() {
        long packetDuration = Math.max(0L, payload.durationSeconds()) * 1000L;
        return packetDuration > 0L ? packetDuration : Math.max(0L, probedDurationMillis);
    }

    private void beginDurationProbe(WhitelistPreviewPacket packet) {
        if (packet == null || hasVideo(packet) || packet.durationSeconds() > 0) {
            return;
        }
        String probeUrl = packet.audioUrl() != null && !packet.audioUrl().isBlank()
                ? packet.audioUrl()
                : packet.rawUrl();
        if (probeUrl == null || probeUrl.isBlank()) {
            return;
        }
        String key = packet.previewId() + ":" + packet.elapsedMillis() + ":" + probeUrl;
        if (key.equals(durationProbeKey)) {
            return;
        }
        durationProbeKey = key;
        AudioDurationProbe.probeMillisAsync(probeUrl).whenComplete((duration, error) -> {
            if (error != null || duration == null || duration.isEmpty()) {
                return;
            }
            Minecraft.getInstance().execute(() -> applyProbedDuration(packet.previewId(), key, duration));
        });
    }

    private void applyProbedDuration(UUID previewId, String key, OptionalLong duration) {
        if (payload == null || !payload.previewId().equals(previewId) || !key.equals(durationProbeKey)) {
            return;
        }
        long millis = duration.orElse(0L);
        if (millis <= 0L) {
            return;
        }
        probedDurationMillis = millis;
        if (!scrubbing) {
            previewProgress = progressForMillis(displayMillis());
        }
    }

    private boolean hasVideo() {
        return hasVideo(payload);
    }

    private static boolean hasVideo(WhitelistPreviewPacket packet) {
        return packet != null && packet.videoUrl() != null && !packet.videoUrl().isBlank();
    }

    private void updateScrub(int bx, double mouseX) {
        previewProgress = clamp01((float) ((mouseX - progressX(bx)) / progressW()));
    }

    private boolean hitProgress(int bx, int by, double mouseX, double mouseY) {
        if (totalMillis() <= 0L) {
            return false;
        }
        int x = progressX(bx);
        int y = progressY(by);
        return mouseX >= x && mouseX <= x + progressW() && mouseY >= y - 6 && mouseY <= y + PROGRESS_H + 8;
    }

    private int boxX() {
        return (width - BOX_W) / 2;
    }

    private int boxY() {
        return (height - BOX_H) / 2;
    }

    private int videoX(int bx) {
        return bx + (BOX_W - VIDEO_W) / 2;
    }

    private int progressX(int bx) {
        return videoX(bx);
    }

    private int progressY(int by) {
        return by + 292;
    }

    private int controlY(int by) {
        return by + 318;
    }

    private int progressW() {
        return VIDEO_W;
    }

    private static float progressFrom(WhitelistPreviewPacket payload) {
        long total = Math.max(0L, payload.durationSeconds()) * 1000L;
        return total <= 0L ? 0.0F : clamp01(payload.elapsedMillis() / (float) total);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static String trim(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(0, Math.max(1, maxLength - 1)) + "…";
    }

    private static String timeText(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return (seconds / 60L) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60L);
    }

    private void togglePause() {
        if (locallyPaused) {
            locallyPaused = false;
            long resumeMillis = Math.max(0L, pausedAtMillis >= 0L ? pausedAtMillis : displayMillis());
            previewProgress = progressForMillis(resumeMillis);
            ClientPacketDistributor.sendToServer(new WhitelistReviewActionPacket(
                    WhitelistReviewActionPacket.Action.PREVIEW_SEEK, payload.rawUrl(), resumeMillis));
            rebuildButtons();
            return;
        }
        pausedAtMillis = displayMillis();
        previewProgress = progressForMillis(pausedAtMillis);
        locallyPaused = true;
        stopCurrentPlayback();
        rebuildButtons();
    }

    private void previewSibling(int direction) {
        String id = siblingId(direction);
        if (id == null) {
            return;
        }
        stopCurrentPlayback();
        locallyPaused = false;
        pausedAtMillis = -1L;
        ClientPacketDistributor.sendToServer(new WhitelistReviewActionPacket(
                WhitelistReviewActionPacket.Action.PREVIEW, id));
    }

    private String siblingId(int direction) {
        WhitelistReviewPacket last = WhitelistReviewScreen.lastPayload();
        if (last == null || last.entries() == null || last.entries().isEmpty() || payload == null) {
            return null;
        }
        int current = -1;
        for (int i = 0; i < last.entries().size(); i++) {
            if (matchesCurrentEntry(last.entries().get(i))) {
                current = i;
                break;
            }
        }
        int next = current + direction;
        if (current < 0 || next < 0 || next >= last.entries().size()) {
            return null;
        }
        return last.entries().get(next).id();
    }

    private boolean matchesCurrentEntry(WhitelistReviewPacket.Entry entry) {
        if (entry == null || payload == null) {
            return false;
        }
        String id = entry.id() == null ? "" : entry.id().trim();
        String raw = payload.rawUrl() == null ? "" : payload.rawUrl().trim();
        if (id.equals(raw)) {
            return true;
        }
        if (id.regionMatches(true, 0, "url:", 0, 4)) {
            return id.substring(4).equals(raw);
        }
        if (id.regionMatches(true, 0, "bili:", 0, 5)) {
            String biliId = id.substring(5);
            return raw.equals(biliId) || raw.startsWith(biliId + "|p=");
        }
        return false;
    }

    private float progressForMillis(long millis) {
        long total = totalMillis();
        return total <= 0L ? 0.0F : clamp01(millis / (float) total);
    }
}
