package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.client.MP4ClientMediaSync;
import com.zhongbai233.net_music_can_play_bili.client.audio.AudioDurationProbe;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaAudioRouting;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaTimelineView;
import com.zhongbai233.net_music_can_play_bili.gui.core.WhitelistReviewSelection;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.media.stream.MediaNetworkFailureClassifier;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistPreviewPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewActionPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewMutationResultPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewPacket;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncPacket;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaIoExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.OptionalLong;
import java.util.Objects;
import java.util.UUID;

/** 白名单审核时使用的音视频预览界面。 */
public class WhitelistPreviewScreen extends Screen {
    private static final int PREFERRED_BOX_W = 460;
    private static final int PREFERRED_BOX_H = 364;
    private static final int MIN_BOX_W = 300;
    private static final int MIN_BOX_H = 220;
    private static final int HEADER_H = 28;
    private static final int MAX_VIDEO_W = 408;
    private static final int MAX_VIDEO_H = 230;
    private static final int VIDEO_TOP = 52;
    private static final int PROGRESS_H = 8;
    private static final int CLOSE_SIZE = 14;
    private static final int PREVIEW_QUALITY = 32;
    private static final long DELETE_TIMEOUT_MILLIS = 10_000L;
    private static final long PREVIEW_TIMEOUT_MILLIS = 10_000L;

    private WhitelistPreviewPacket payload;
    private float previewProgress;
    private long probedDurationMillis;
    private long pausedAtMillis = -1L;
    private boolean locallyPaused;
    private boolean scrubbing;
    private boolean closeHovered;
    private String durationProbeKey = "";
    private CancellableTaskFuture<OptionalLong> durationProbeTask;
    private String resolvingVideoKey = "";
    private boolean videoResolveFailed;
    private boolean videoResolveNetworkFailure;
    private boolean promptingDelete;
    private boolean deletionPending;
    private EditBox deleteNoteField;
    private BlackGoldButton deleteSubmitButton;
    private String deleteNoteDraft = "";
    private String deleteTargetId = "";
    private String deleteExpectedAddedAt = "";
    private String deleteStatus = "";
    private long deletionRequestStartedAt;
    private long deletionRequestId;
    private String pendingPreviewTarget = "";
    private long pendingPreviewRequestId;
    private long previewRequestStartedAt;
    private String previewRequestStatus = "";

    public WhitelistPreviewScreen(WhitelistPreviewPacket payload) {
        super(Component.literal("白名单视频预览"));
        this.payload = payload;
        this.previewProgress = progressFrom(payload);
        beginDurationProbe(payload);
    }

    public static void openOrUpdate(WhitelistPreviewPacket payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof WhitelistPreviewScreen screen) {
            if (screen.acceptPreviewResponse(payload)) {
                screen.update(payload);
            }
        } else if (minecraft.screen instanceof WhitelistReviewScreen review
                && review.acceptPreviewResponse(payload)) {
            minecraft.setScreen(new WhitelistPreviewScreen(payload));
        }
    }

    private void update(WhitelistPreviewPacket next) {
        cancelDurationProbe();
        stopCurrentPlayback();
        this.payload = next;
        this.probedDurationMillis = 0L;
        this.durationProbeKey = "";
        this.previewProgress = progressFrom(next);
        this.pausedAtMillis = -1L;
        this.locallyPaused = !next.playing();
        this.resolvingVideoKey = "";
        this.videoResolveFailed = false;
        this.videoResolveNetworkFailure = false;
        WhitelistReviewPacket.Entry promptedEntry = currentEntry();
        if (promptingDelete && (promptedEntry == null || !promptedEntry.id().equals(deleteTargetId)
                || !Objects.equals(promptedEntry.addedAt(), deleteExpectedAddedAt))) {
            clearDeletePromptState();
        }
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
        if (deleteNoteField != null) {
            deleteNoteDraft = deleteNoteField.getValue();
        }
        clearWidgets();
        deleteNoteField = null;
        deleteSubmitButton = null;
        int bx = boxX();
        int by = boxY();
        if (promptingDelete) {
            int fieldX = bx + 14;
            int submitWidth = 52;
            int cancelWidth = 40;
            int gap = 6;
            int fieldWidth = boxWidth() - 28 - submitWidth - cancelWidth - gap * 2;
            deleteNoteField = new EditBox(font, fieldX, controlY(by), fieldWidth, 20,
                    Component.literal("移除备注"));
            deleteNoteField.setMaxLength(256);
            deleteNoteField.setValue(deleteNoteDraft);
            deleteNoteField.setHint(Component.literal("移除备注（必填）"));
            deleteNoteField.setEditable(!deletionPending);
            addRenderableWidget(deleteNoteField);
            int submitX = fieldX + fieldWidth + gap;
            deleteSubmitButton = new BlackGoldButton(submitX, controlY(by), submitWidth, 20,
                    Component.literal(deletionPending ? "处理中" : "提交"),
                    button -> confirmDelete(), BlackGoldUi.GOLD);
            deleteSubmitButton.active = canSubmitDelete();
            addRenderableWidget(deleteSubmitButton);
            BlackGoldButton cancel = new BlackGoldButton(submitX + submitWidth + gap,
                    controlY(by), cancelWidth, 20,
                    Component.literal("取消"), button -> cancelDelete(), BlackGoldUi.GOLD_DIM);
            cancel.active = !deletionPending;
            addRenderableWidget(cancel);
            deleteNoteField.setResponder(value -> {
                deleteNoteDraft = value;
                deleteStatus = "";
                if (!deletionPending) {
                    deletionRequestId = 0L;
                }
                if (deleteSubmitButton != null) {
                    deleteSubmitButton.active = canSubmitDelete();
                }
            });
            if (!deletionPending) {
                setInitialFocus(deleteNoteField);
            }
            return;
        }
        int gap = 6;
        int buttonWidth = Math.min(76, (boxWidth() - 28 - gap * 3) / 4);
        int buttonX = bx + (boxWidth() - (buttonWidth * 4 + gap * 3)) / 2;
        boolean navigationEnabled = pendingPreviewRequestId <= 0L;
        BlackGoldButton previous = new BlackGoldButton(buttonX, controlY(by), buttonWidth, 20,
                Component.literal("上一个"), button -> previewSibling(-1), BlackGoldUi.GOLD);
        previous.active = navigationEnabled && siblingId(-1) != null;
        addRenderableWidget(previous);
        buttonX += buttonWidth + gap;
        BlackGoldButton pause = new BlackGoldButton(buttonX, controlY(by), buttonWidth, 20,
                Component.literal(locallyPaused ? "继续" : "暂停"), button -> togglePause(), BlackGoldUi.GOLD);
        pause.active = navigationEnabled;
        addRenderableWidget(pause);
        buttonX += buttonWidth + gap;
        BlackGoldButton next = new BlackGoldButton(buttonX, controlY(by), buttonWidth, 20,
                Component.literal("下一个"), button -> previewSibling(1), BlackGoldUi.GOLD);
        next.active = navigationEnabled && siblingId(1) != null;
        addRenderableWidget(next);
        buttonX += buttonWidth + gap;
        BlackGoldButton delete = new BlackGoldButton(buttonX, controlY(by), buttonWidth, 20,
            Component.literal("删除"), button -> requestDelete(), 0xFFFF6B6B);
        delete.active = navigationEnabled && currentEntry() != null;
        addRenderableWidget(delete);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (deletionPending) {
            return;
        }
        if (promptingDelete) {
            cancelDelete();
            return;
        }
        cancelDurationProbe();
        stopCurrentPlayback();
        returnToReviewMenu();
    }

    @Override
    public void removed() {
        cancelDurationProbe();
        clearPendingPreviewResponse();
        stopCurrentPlayback();
    }

    @Override
    public void tick() {
        super.tick();
        if (deletionPending && System.currentTimeMillis() - deletionRequestStartedAt >= DELETE_TIMEOUT_MILLIS) {
            deletionPending = false;
            deletionRequestStartedAt = 0L;
            deleteStatus = "未收到服务器确认，备注已保留，可重试";
            WhitelistReviewScreen.cancelPreparedPreviewRemoval();
            rebuildButtons();
        }
        if (pendingPreviewRequestId > 0L
                && System.currentTimeMillis() - previewRequestStartedAt >= PREVIEW_TIMEOUT_MILLIS) {
            clearPendingPreviewResponse();
            previewRequestStatus = "预览请求超时，仍保留当前页面，可重试";
            rebuildButtons();
        }
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
        BlackGoldUi.drawPanel(g, bx, by, boxWidth(), boxHeight());
        drawHeader(g, bx, by, mx, my);
        drawVideo(g, bx, by);
        drawProgress(g, bx, by, mx, my);
        drawFooter(g, bx, by);
        super.extractRenderState(g, mx, my, pt);
    }

    private void drawHeader(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        BlackGoldUi.drawHeader(g, font, getTitle(), bx, by, boxWidth(), HEADER_H);
        int cx = bx + boxWidth() - CLOSE_SIZE - 8;
        int cy = by + (HEADER_H - CLOSE_SIZE) / 2;
        closeHovered = mx >= cx && mx <= cx + CLOSE_SIZE && my >= cy && my <= cy + CLOSE_SIZE;
        g.centeredText(font, Component.literal("✕"), cx + CLOSE_SIZE / 2, cy + 4,
                closeHovered ? BlackGoldUi.GOLD : BlackGoldUi.TEXT_SECONDARY);
        g.text(font, Component.literal(BlackGoldUi.ellipsize(font, payload.title(), boxWidth() - 54)),
                bx + 16, by + 34, BlackGoldUi.TEXT_SECONDARY, false);
    }

    private void drawVideo(GuiGraphicsExtractor g, int bx, int by) {
        String sessionId = WhitelistPreviewPacket.sessionId(payload.previewId(), payload.elapsedMillis());
        int x = videoX(bx);
        int y = videoY(by);
        g.fillGradient(x - 2, y - 2, x + videoWidth() + 2, y + videoHeight() + 2, BlackGoldUi.GOLD_DIM, BlackGoldUi.GOLD_DIM);
        g.fillGradient(x, y, x + videoWidth(), y + videoHeight(), 0xFF050505, 0xFF101010);
        if (!hasVideo()) {
            g.centeredText(font, Component.literal("纯音频预览"), x + videoWidth() / 2, y + videoHeight() / 2 - 14,
                    BlackGoldUi.GOLD);
            g.centeredText(font, Component.literal("此条目仅播放音频"),
                    x + videoWidth() / 2, y + videoHeight() / 2 + 2, BlackGoldUi.TEXT_DIM);
            return;
        }
        VideoBillboardPreview.pumpPreviewFrame(sessionId);
        VideoBillboardPreview.ProjectorFrameSnapshot frame = VideoBillboardPreview.currentPreviewFrame(sessionId);
        if (frame.hasFrame() && !frame.yuv() && frame.rgbaTexture() != null) {
            g.blit(frame.rgbaTexture(), x, y, x + videoWidth(), y + videoHeight(), 0.0F, 1.0F, 0.0F, 1.0F);
            if (VideoBillboardPreview.hasNetworkFailure(sessionId)) {
                g.centeredText(font, Component.literal("点击画面重新连接"), x + videoWidth() / 2,
                        y + videoHeight() - 20, 0xFFFFD166);
            }
            return;
        }
        if (videoResolveFailed) {
            g.centeredText(font, Component.literal(videoResolveNetworkFailure
                    ? "视频网络连接失败"
                    : "视频信息解析失败"), x + videoWidth() / 2,
                    y + videoHeight() / 2 - 14, 0xFFFF8A78);
            g.centeredText(font, Component.literal("点击画面重新连接"), x + videoWidth() / 2,
                    y + videoHeight() / 2 + 4, 0xFFFFD166);
            return;
        }
        String text = frame.hasFrame() && frame.yuv()
                ? "正在准备视频画面..."
                : "正在加载视频画面...";
        g.centeredText(font, Component.literal(text), x + videoWidth() / 2, y + videoHeight() / 2 - 6, BlackGoldUi.TEXT_DIM);
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
        String footer;
        if (deletionPending) {
            footer = "正在等待服务器确认删除……";
        } else if (!deleteStatus.isBlank()) {
            footer = deleteStatus;
        } else {
            footer = promptingDelete
                    ? "正在删除 " + deleteTargetId + "；请输入必填备注"
                    : !previewRequestStatus.isBlank()
                            ? previewRequestStatus
                            : "拖动进度条，或用左右键调整播放位置";
        }
        g.text(font, Component.literal(BlackGoldUi.ellipsize(font, footer, boxWidth() - 32)),
                bx + 16, by + boxHeight() - 20, BlackGoldUi.TEXT_DIM, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean cancelled) {
        if (cancelled) {
            return false;
        }
        int bx = boxX();
        int by = boxY();
        int cx = bx + boxWidth() - CLOSE_SIZE - 8;
        int cy = by + (HEADER_H - CLOSE_SIZE) / 2;
        if (event.x() >= cx && event.x() <= cx + CLOSE_SIZE && event.y() >= cy && event.y() <= cy + CLOSE_SIZE) {
            onClose();
            return true;
        }
        if (promptingDelete) {
            return super.mouseClicked(event, cancelled);
        }
        if (pendingPreviewRequestId > 0L) {
            return super.mouseClicked(event, cancelled);
        }
        String sessionId = WhitelistPreviewPacket.sessionId(payload.previewId(), payload.elapsedMillis());
        if (hitVideo(bx, by, event.x(), event.y()) && videoResolveFailed) {
            videoResolveFailed = false;
            videoResolveNetworkFailure = false;
            resolvingVideoKey = "";
            startPreviewVideo(payload, sessionId);
            return true;
        }
        if (hitVideo(bx, by, event.x(), event.y()) && VideoBillboardPreview.retryNetworkFailure(sessionId)) {
            return true;
        }
        if (hitProgress(bx, by, event.x(), event.y())) {
            scrubbing = true;
            updateScrub(bx, event.x());
            return true;
        }
        return super.mouseClicked(event, cancelled);
    }

    private boolean hitVideo(int bx, int by, double mouseX, double mouseY) {
        int x = videoX(bx);
        int y = videoY(by);
        return mouseX >= x && mouseX <= x + videoWidth() && mouseY >= y && mouseY <= y + videoHeight();
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
            requestPreviewResponse(WhitelistReviewActionPacket.Action.PREVIEW_SEEK, payload.rawUrl(), currentMillis());
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (promptingDelete) {
            if (deletionPending) {
                return event.key() == 256 || super.keyPressed(event);
            }
            if (event.key() == 256) {
                cancelDelete();
                return true;
            }
            if ((event.key() == 257 || event.key() == 335) && getFocused() == deleteNoteField) {
                confirmDelete();
                return true;
            }
            return super.keyPressed(event);
        }
        if (pendingPreviewRequestId > 0L) {
            if (event.key() == 256) {
                onClose();
                return true;
            }
            return super.keyPressed(event);
        }
        switch (event.key()) {
            case 263 -> seekBy(-5_000L);
            case 262 -> seekBy(5_000L);
            case 265 -> previewSibling(-1);
            case 264 -> previewSibling(1);
            case 32 -> togglePause();
            default -> {
                return super.keyPressed(event);
            }
        }
        return true;
    }

    private void startPlayback(WhitelistPreviewPacket packet) {
        if (packet == null) {
            return;
        }
        if (locallyPaused || !packet.playing()) {
            return;
        }
        ClientMediaAudioRouting.registerLocalPrivateSource(packet.previewId());
        MP4ClientMediaSync.handleSync(toLocalAudioSync(packet));
        if (!hasVideo(packet)) {
            return;
        }
        String sessionId = WhitelistPreviewPacket.sessionId(packet.previewId(), packet.elapsedMillis());
        startPreviewVideo(packet, sessionId);
    }

    private void startPreviewVideo(WhitelistPreviewPacket packet, String sessionId) {
        videoResolveFailed = false;
        videoResolveNetworkFailure = false;
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
        MediaIoExecutor.supply(() -> {
            try {
                return BiliVideoStreamResolver.resolve(packet.videoUrl(), PREVIEW_QUALITY);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).whenComplete((stream, error) -> Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen != this || payload == null
                    || !packet.previewId().equals(payload.previewId())
                    || !sessionId
                            .equals(WhitelistPreviewPacket.sessionId(payload.previewId(), payload.elapsedMillis()))) {
                return;
            }
            resolvingVideoKey = "";
            if (error != null || stream == null) {
                videoResolveFailed = true;
                videoResolveNetworkFailure = error != null
                        && MediaNetworkFailureClassifier.isNetworkFailure(error);
                return;
            }
            VideoBillboardPreview.startRgbaPreviewAt(stream.url(), stream.sourceWidth(), stream.sourceHeight(),
                    stream.fps(), stream.codecId(), sessionId, packet.elapsedMillis(), totalMillis(), true, null,
                    packet.previewId());
        }));
    }

    private void returnToReviewMenu() {
        WhitelistReviewPacket last = WhitelistReviewScreen.lastPayload();
        WhitelistReviewPacket.Entry current = currentEntry();
        WhitelistReviewScreen.resumeFromPreview(last, current == null ? "" : current.id());
    }

    private MP4PlaybackSyncPacket toLocalAudioSync(WhitelistPreviewPacket packet) {
        int playerEntityId = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getId() : -1;
        return new MP4PlaybackSyncPacket(packet.previewId(), packet.previewId(), ClientMediaSyncPayload.SOURCE_PLAYER,
                playerEntityId, 0.0D, 0.0D, 0.0D, packet.playing(), 0, packet.audioUrl(), packet.rawUrl(),
                packet.title(), packet.durationSeconds(), 850,
                WhitelistPreviewPacket.sessionId(packet.previewId(), packet.elapsedMillis()),
                packet.elapsedMillis(), false);
    }

    private void stopCurrentPlayback() {
        UUID previewId = payload != null ? payload.previewId() : null;
        if (previewId != null) {
            String sessionId = WhitelistPreviewPacket.sessionId(previewId, payload.elapsedMillis());
            MP4ClientMediaSync.handleSync(WhitelistPreviewPacket.stopAudio(previewId));
            ClientMediaAudioRouting.unregisterLocalPrivateSource(previewId);
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
        return ClientMediaTimelineView.forMediaOwner(payload.previewId(), sessionId, payload.elapsedMillis(),
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
        cancelDurationProbe();
        durationProbeKey = key;
        CancellableTaskFuture<OptionalLong> task = AudioDurationProbe.probeMillisAsync(probeUrl);
        durationProbeTask = task;
        task.whenComplete((duration, error) -> Minecraft.getInstance().execute(() -> {
            if (durationProbeTask != task) {
                return;
            }
            durationProbeTask = null;
            if (error == null && duration != null && duration.isPresent()) {
                applyProbedDuration(packet.previewId(), key, duration);
            }
        }));
    }

    private void cancelDurationProbe() {
        CancellableTaskFuture<OptionalLong> task = durationProbeTask;
        durationProbeTask = null;
        durationProbeKey = "";
        if (task != null) {
            task.cancel(true);
        }
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

    private int boxWidth() {
        return Math.min(PREFERRED_BOX_W, Math.max(MIN_BOX_W, width - 16));
    }

    private int boxHeight() {
        return Math.min(PREFERRED_BOX_H, Math.max(MIN_BOX_H, height - 16));
    }

    private int boxX() {
        return (width - boxWidth()) / 2;
    }

    private int boxY() {
        return (height - boxHeight()) / 2;
    }

    private int videoWidth() {
        int maxWidth = Math.min(MAX_VIDEO_W, Math.max(128, boxWidth() - 32));
        int maxHeight = Math.min(MAX_VIDEO_H, Math.max(72, boxHeight() - 134));
        return Math.max(128, Math.min(maxWidth, maxHeight * 16 / 9));
    }

    private int videoHeight() {
        int maxHeight = Math.min(MAX_VIDEO_H, Math.max(72, boxHeight() - 134));
        return Math.max(72, Math.min(maxHeight, videoWidth() * 9 / 16));
    }

    private int videoX(int bx) {
        return bx + (boxWidth() - videoWidth()) / 2;
    }

    private int videoY(int by) {
        return by + VIDEO_TOP;
    }

    private int progressX(int bx) {
        return videoX(bx);
    }

    private int progressY(int by) {
        return videoY(by) + videoHeight() + 10;
    }

    private int controlY(int by) {
        return progressY(by) + 26;
    }

    private int progressW() {
        return videoWidth();
    }

    private static float progressFrom(WhitelistPreviewPacket payload) {
        long total = Math.max(0L, payload.durationSeconds()) * 1000L;
        return total <= 0L ? 0.0F : clamp01(payload.elapsedMillis() / (float) total);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
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
            requestPreviewResponse(WhitelistReviewActionPacket.Action.PREVIEW_SEEK,
                    payload.rawUrl(), resumeMillis);
            rebuildButtons();
            return;
        }
        clearPendingPreviewResponse();
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
        cancelDurationProbe();
        stopCurrentPlayback();
        locallyPaused = false;
        pausedAtMillis = -1L;
        requestPreviewResponse(WhitelistReviewActionPacket.Action.PREVIEW, id, 0L);
    }

    private void seekBy(long deltaMillis) {
        long total = totalMillis();
        if (total <= 0L) {
            return;
        }
        long target = Math.max(0L, Math.min(total, displayMillis() + deltaMillis));
        previewProgress = progressForMillis(target);
        requestPreviewResponse(WhitelistReviewActionPacket.Action.PREVIEW_SEEK, payload.rawUrl(), target);
    }

    private void requestPreviewResponse(WhitelistReviewActionPacket.Action action, String target, long targetMillis) {
        pendingPreviewTarget = target == null ? "" : target;
        pendingPreviewRequestId = WhitelistReviewActionPacket.nextClientRequestId();
        previewRequestStartedAt = System.currentTimeMillis();
        previewRequestStatus = action == WhitelistReviewActionPacket.Action.PREVIEW
                ? "正在切换预览……"
                : "正在调整播放位置……";
        rebuildButtons();
        ClientPacketDistributor.sendToServer(new WhitelistReviewActionPacket(
                action, target, targetMillis, pendingPreviewRequestId));
    }

    private boolean acceptPreviewResponse(WhitelistPreviewPacket next) {
        if (next == null || pendingPreviewTarget.isBlank()
                || pendingPreviewRequestId <= 0L || pendingPreviewRequestId != next.requestId()
                || !WhitelistReviewSelection.matchesPreview(pendingPreviewTarget, next.rawUrl())) {
            return false;
        }
        clearPendingPreviewResponse();
        previewRequestStatus = "";
        return true;
    }

    private void clearPendingPreviewResponse() {
        pendingPreviewTarget = "";
        pendingPreviewRequestId = 0L;
        previewRequestStartedAt = 0L;
        previewRequestStatus = "";
    }

    private void requestDelete() {
        WhitelistReviewPacket.Entry entry = currentEntry();
        if (entry == null) {
            return;
        }
        clearPendingPreviewResponse();
        promptingDelete = true;
        deleteTargetId = entry.id();
        deleteExpectedAddedAt = entry.addedAt();
        deleteNoteDraft = "";
        deletionRequestId = 0L;
        deleteStatus = "";
        rebuildButtons();
    }

    private void cancelDelete() {
        if (deletionPending) {
            return;
        }
        WhitelistReviewScreen.cancelPreparedPreviewRemoval();
        clearDeletePromptState();
        deleteStatus = "";
        rebuildButtons();
    }

    private void confirmDelete() {
        WhitelistReviewPacket.Entry entry = entryById(deleteTargetId);
        if (entry == null || !matchesCurrentEntry(entry)
                || !Objects.equals(entry.addedAt(), deleteExpectedAddedAt)) {
            cancelDelete();
            return;
        }
        String note = deleteNoteField == null ? "" : deleteNoteField.getValue().trim();
        deleteNoteDraft = note;
        if (note.isBlank() || deletionPending) {
            return;
        }
        String targetId = entry.id();
        String expectedAddedAt = deleteExpectedAddedAt;
        deletionPending = true;
        deletionRequestStartedAt = System.currentTimeMillis();
        if (deletionRequestId <= 0L) {
            deletionRequestId = WhitelistReviewActionPacket.nextClientRequestId();
        }
        deleteStatus = "";
        WhitelistReviewScreen.preparePreviewRemoval(targetId);
        rebuildButtons();
        WhitelistReviewPacket review = WhitelistReviewScreen.lastPayload();
        int entryOffset = review == null ? 0 : review.entryOffset();
        int removalOffset = review == null ? 0 : review.removalOffset();
        ClientPacketDistributor.sendToServer(new WhitelistReviewActionPacket(
                WhitelistReviewActionPacket.Action.REMOVE, targetId, 0L, note, expectedAddedAt,
                entryOffset, removalOffset, deletionRequestId));
    }

    private boolean canSubmitDelete() {
        if (deletionPending || deleteNoteDraft == null || deleteNoteDraft.trim().isEmpty()) {
            return false;
        }
        WhitelistReviewPacket.Entry entry = entryById(deleteTargetId);
        return entry != null
                && matchesCurrentEntry(entry)
                && Objects.equals(entry.addedAt(), deleteExpectedAddedAt);
    }

    void handleMutationResult(WhitelistReviewMutationResultPacket result) {
        if (result == null) {
            return;
        }
        if (result.mutation() == WhitelistReviewMutationResultPacket.Mutation.PREVIEW
                || result.mutation() == WhitelistReviewMutationResultPacket.Mutation.PREVIEW_SEEK) {
            if (pendingPreviewRequestId > 0L
                    && pendingPreviewRequestId == result.requestId()
                    && Objects.equals(pendingPreviewTarget, result.targetId())) {
                clearPendingPreviewResponse();
                previewRequestStatus = result.message();
                rebuildButtons();
            }
            return;
        }
        if (!deletionPending
                || result.mutation() != WhitelistReviewMutationResultPacket.Mutation.REMOVE
                || !Objects.equals(deleteTargetId, result.targetId())
                || deletionRequestId != result.requestId()) {
            return;
        }
        deletionPending = false;
        deletionRequestStartedAt = 0L;
        deletionRequestId = 0L;
        deleteStatus = result.message();
        if (!result.successful()) {
            WhitelistReviewScreen.cancelPreparedPreviewRemoval();
            rebuildButtons();
            return;
        }
        clearDeletePromptState();
        cancelDurationProbe();
        stopCurrentPlayback();
        WhitelistReviewScreen.resumeFromPreview(WhitelistReviewScreen.lastPayload(), "");
    }

    private void clearDeletePromptState() {
        promptingDelete = false;
        deletionPending = false;
        deletionRequestId = 0L;
        deleteNoteField = null;
        deleteSubmitButton = null;
        deleteNoteDraft = "";
        deleteTargetId = "";
        deleteExpectedAddedAt = "";
    }

    private WhitelistReviewPacket.Entry entryById(String id) {
        WhitelistReviewPacket last = WhitelistReviewScreen.lastPayload();
        if (id == null || id.isBlank() || last == null || last.entries() == null) {
            return null;
        }
        return last.entries().stream().filter(entry -> id.equals(entry.id())).findFirst().orElse(null);
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

    private WhitelistReviewPacket.Entry currentEntry() {
        WhitelistReviewPacket last = WhitelistReviewScreen.lastPayload();
        if (last == null || last.entries() == null || payload == null) {
            return null;
        }
        for (WhitelistReviewPacket.Entry entry : last.entries()) {
            if (matchesCurrentEntry(entry)) {
                return entry;
            }
        }
        return null;
    }

    private boolean matchesCurrentEntry(WhitelistReviewPacket.Entry entry) {
        return entry != null && payload != null
                && WhitelistReviewSelection.matchesPreview(entry.id(), payload.rawUrl());
    }

    private float progressForMillis(long millis) {
        long total = totalMillis();
        return total <= 0L ? 0.0F : clamp01(millis / (float) total);
    }
}
