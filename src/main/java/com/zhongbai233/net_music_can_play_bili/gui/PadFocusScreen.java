package com.zhongbai233.net_music_can_play_bili.gui;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.PadClient;
import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapProjection;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSampler;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerMode;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import com.zhongbai233.net_music_can_play_bili.network.PadPlaybackControlPacket;
import com.zhongbai233.net_music_can_play_bili.network.PadPublishPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.UUID;

/** Pad 自定义几何表面的透明输入层。 */
public class PadFocusScreen extends Screen {
    private static final int TEXTURE_W = 448;
    private static final int TEXTURE_H = 256;
    private static final int MAP_X = 14;
    private static final int MAP_Y = 44;
    private static final int MAP_W = 270;
    private static final int MAP_H = 198;
    private static final int LOCKED_MAP_X = 0;
    private static final int LOCKED_MAP_Y = 0;
    private static final int LOCKED_MAP_W = TEXTURE_W;
    private static final int LOCKED_MAP_H = TEXTURE_H;
    private static final int MEDIA_X = 300;
    private static final int MEDIA_Y = 48;
    private static final int MEDIA_W = 132;
    private static final int MEDIA_H = 98;
    private static final int EDITOR_X = 300;
    private static final int EDITOR_Y = 130;
    private static final int EDITOR_W = 132;
    private static final int EDITOR_H = 86;
    private static final int PUBLISH_X = 300;
    private static final int PUBLISH_Y = 220;
    private static final int PUBLISH_W = 132;
    private static final int PUBLISH_H = 18;
    private final InteractionHand hand;
    private boolean draggingProgress;

    public PadFocusScreen(InteractionHand hand) {
        super(Component.translatable("gui.net_music_can_play_bili.pad.focus"));
        this.hand = hand;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        PadFocusState.activate(hand);
    }

    @Override
    public void onClose() {
        PadFocusState.deactivate();
        super.onClose();
    }

    @Override
    public void tick() {
        // 和 MP4 一样，手持动画由渲染帧推进；这里保留屏幕生命周期即可。
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
            TexturePoint point = toTexturePoint((int) event.x(), (int) event.y());
            if (point != null && insideMap(point, PadClient.cachedDocumentFor(heldPadStack()))) {
                PadTriggerPoint hitPoint = pointAt(point);
                PadDocument document = PadClient.cachedDocumentFor(heldPadStack());
                if (hitPoint != null && !document.locked()) {
                    removePoint(hitPoint.pointId());
                    return true;
                }
            }
            onClose();
            return true;
        }
        if (event.button() == 0) {
            handleLeftClick((int) event.x(), (int) event.y());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        updateHover((int) event.x(), (int) event.y());
        if (event.button() == 0 && draggingProgress) {
            TexturePoint point = toTexturePoint((int) event.x(), (int) event.y());
            if (point != null) {
                UUID deviceId = PadItem.readDeviceId(heldPadStack());
                boolean video = PadFocusState.pausedVideo()
                        || (deviceId != null && MP4HandheldVideoClient.latestFrame(deviceId) != null);
                PadFocusState.setMediaProgress(video ? videoProgressAt(point) : audioProgressAt(point));
            }
            return true;
        }
        if (event.button() == 0 && PadFocusState.draggingPoint()) {
            TexturePoint point = toTexturePoint((int) event.x(), (int) event.y());
            if (point != null && insideMap(point, PadClient.cachedDocumentFor(heldPadStack()))) {
                PadFocusState.updatePointDragPreview(point.x(), point.y());
            }
            return true;
        }
        return event.button() == 0 || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        updateHover((int) event.x(), (int) event.y());
        if (event.button() == 0 && PadFocusState.draggingMedia()) {
            TexturePoint point = toTexturePoint((int) event.x(), (int) event.y());
            if (point != null && insideMap(point, PadClient.cachedDocumentFor(heldPadStack()))) {
                createPointFromDrag(point, PadFocusState.draggingMediaId(), PadFocusState.draggingMediaName());
            }
            PadFocusState.endMediaDrag();
            return true;
        }
        if (event.button() == 0 && draggingProgress) {
            draggingProgress = false;
            PadFocusState.setScrubbingProgress(false);
            seekPlayback(currentProgressMillis());
            return true;
        }
        if (event.button() == 0 && PadFocusState.draggingPoint()) {
            TexturePoint point = toTexturePoint((int) event.x(), (int) event.y());
            if (point != null && inside(point, MAP_X, MAP_Y, MAP_W, MAP_H)) {
                moveSelectedPoint(point);
            }
            PadFocusState.endPointDrag();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        updateHover((int) mouseX, (int) mouseY);
        return true;
    }

    private void updateHover(int mouseX, int mouseY) {
        TexturePoint point = toTexturePoint(mouseX, mouseY);
        if (point == null) {
            PadFocusState.clearHoverTarget();
            return;
        }
        float localX = point.x() / (TEXTURE_W - 1.0F) * 2.0F - 1.0F;
        float localY = point.y() / (TEXTURE_H - 1.0F) * 2.0F - 1.0F;
        PadFocusState.updateHover(localX, localY);
        PadFocusState.updateHoverTarget(point.x(), point.y(), hit(point));
    }

    private void handleLeftClick(int mouseX, int mouseY) {
        TexturePoint point = toTexturePoint(mouseX, mouseY);
        if (point == null) {
            return;
        }
        String control = hit(point);
        PadFocusState.pressFeedback(point.x(), point.y(), control);
        if (handleLockedVideoControl(point, control)) {
            return;
        }
        if (handleLockedAudioControl(point, control)) {
            return;
        }
        if ("MEDIA".equals(control)) {
            PadMediaEntry entry = mediaEntryAt(point);
            if (entry != null) {
                PadFocusState.beginMediaDrag(entry.mediaId(), mediaName(entry));
            }
            return;
        }
        if ("MAP".equals(control)) {
            PadTriggerPoint hitPoint = pointAt(point);
            if (hitPoint != null) {
                PadDocument document = PadClient.cachedDocumentFor(heldPadStack());
                if (document.locked()) {
                    playPoint(hitPoint);
                } else {
                    PadFocusState.beginPointDrag(hitPoint.pointId());
                }
            } else {
                PadFocusState.selectPoint(null);
            }
            return;
        }
        if ("EDITOR".equals(control)) {
            handleEditorClick(point);
            return;
        }
        if ("PLAYBACK".equals(control)) {
            stopPlayback();
            return;
        }
        if ("PUBLISH".equals(control)) {
            publishLockedCopy();
        }
    }

    private boolean handleLockedVideoControl(TexturePoint point, String control) {
        PadDocument document = PadClient.cachedDocumentFor(heldPadStack());
        UUID deviceId = PadItem.readDeviceId(heldPadStack());
        if (!document.locked() || deviceId == null
            || (!ClientMediaPlayback.hasPlayback(deviceId) && !PadFocusState.pausedPlaybackAvailable())) {
            return false;
        }
        switch (control) {
            case "VIDEO_PROGRESS" -> {
                draggingProgress = true;
                PadFocusState.setScrubbingProgress(true);
                PadFocusState.setMediaProgress(videoProgressAt(point));
                return true;
            }
            case "VIDEO_PLAY" -> {
                togglePlayback();
                PadFocusState.showControlsTemporarily();
                return true;
            }
            case "VIDEO_STOP" -> {
                stopPlayback();
                PadFocusState.showControlsTemporarily();
                return true;
            }
            case "VIDEO_QUALITY" -> {
                PadFocusState.toggleQualityMenu();
                return true;
            }
            case "VIDEO_SUBTITLE" -> {
                PadFocusState.toggleSubtitleMenu();
                return true;
            }
            case "SUBTITLE_OFF" -> {
                PadFocusState.disableSubtitle();
                return true;
            }
            case "SUBTITLE_PRIMARY" -> {
                PadFocusState.selectSubtitleMode(0);
                return true;
            }
            case "SUBTITLE_SECONDARY" -> {
                PadFocusState.selectSubtitleMode(1);
                return true;
            }
            case "SUBTITLE_AI" -> {
                PadFocusState.toggleSubtitleAi();
                return true;
            }
            case "QUALITY_0", "QUALITY_1", "QUALITY_2", "QUALITY_3", "QUALITY_4", "QUALITY_5", "QUALITY_6",
                    "QUALITY_7" -> {
                PadFocusState.selectQualityIndex(control.charAt(control.length() - 1) - '0');
                return true;
            }
            case "MAP" -> {
                PadFocusState.toggleControls();
                return true;
            }
            default -> {
                if (PadFocusState.controlsVisible()) {
                    PadFocusState.showControlsTemporarily();
                }
                return false;
            }
        }
    }

    private boolean handleLockedAudioControl(TexturePoint point, String control) {
        PadDocument document = PadClient.cachedDocumentFor(heldPadStack());
        UUID deviceId = PadItem.readDeviceId(heldPadStack());
        boolean audioPaused = PadFocusState.pausedPlaybackAvailable() && !PadFocusState.pausedVideo();
        if (!document.locked() || deviceId == null
                || (!ClientMediaPlayback.hasPlayback(deviceId) && !audioPaused)) {
            return false;
        }
        switch (control) {
            case "AUDIO_PROGRESS" -> {
                draggingProgress = true;
                PadFocusState.setScrubbingProgress(true);
                PadFocusState.setMediaProgress(audioProgressAt(point));
                return true;
            }
            case "AUDIO_PLAY" -> {
                togglePlayback();
                return true;
            }
            case "AUDIO_STOP" -> {
                stopPlayback();
                return true;
            }
            case "AUDIO_SUBTITLE" -> {
                PadFocusState.toggleSubtitleMenu();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void publishLockedCopy() {
        ItemStack stack = heldPadStack();
        if (!PadItem.isPad(stack)) {
            return;
        }
        PadDocument document = PadClient.cachedDocumentFor(stack);
        if (document.locked()) {
            return;
        }
        UUID deviceId = PadItem.readDeviceId(stack);
        if (deviceId == null) {
            return;
        }
        writeDocument(stack, document.withLocked(false));
        ClientPacketDistributor.sendToServer(new PadPublishPacket(deviceId));
        PadFocusState.selectPoint(null);
        PadFocusState.endMediaDrag();
        PadFocusState.endPointDrag();
    }

    private void handleEditorClick(TexturePoint point) {
        PadDocument document = PadClient.cachedDocumentFor(heldPadStack());
        if (document.locked()) {
            return;
        }
        PadTriggerPoint selected = selectedPoint(document);
        if (selected == null) {
            return;
        }
        int localX = point.x() - EDITOR_X;
        int localY = point.y() - EDITOR_Y;
        if (localY >= 28 && localY < 43 && localX >= 8 && localX < 60) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks(), !selected.visible(), selected.volumePerMille(),
                    selected.loop()));
            return;
        }
        if (localY >= 28 && localY < 43 && localX >= 66 && localX < 124) {
            removePoint(selected.pointId());
            return;
        }
        if (localY >= 45 && localY < 60 && localX >= 8 && localX < 36) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks() - 1, selected.visible(),
                    selected.volumePerMille(), selected.loop()));
            return;
        }
        if (localY >= 45 && localY < 60 && localX >= 40 && localX < 68) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks() + 1, selected.visible(),
                    selected.volumePerMille(), selected.loop()));
            return;
        }
        if (localY >= 45 && localY < 60 && localX >= 74 && localX < 124) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks(), selected.visible(), selected.volumePerMille(),
                    !selected.loop()));
            return;
        }
        if (localY >= 62 && localY < 77 && localX >= 8 && localX < 60) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks(), selected.visible(), selected.volumePerMille(),
                    selected.loop(), selected.triggerMode() == PadTriggerMode.MANUAL
                            ? PadTriggerMode.ENTER_RADIUS
                            : PadTriggerMode.MANUAL));
            return;
        }
        if (localY >= 62 && localY < 77 && localX >= 66 && localX < 94) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks(), selected.visible(),
                    selected.volumePerMille() - 100, selected.loop()));
            return;
        }
        if (localY >= 62 && localY < 77 && localX >= 96 && localX < 124) {
            updatePoint(rebuildPoint(selected, selected.radiusBlocks(), selected.visible(),
                    selected.volumePerMille() + 100, selected.loop()));
        }
    }

    private void createPointFromDrag(TexturePoint point, int mediaId, String mediaName) {
        ItemStack stack = heldPadStack();
        if (!PadItem.isPad(stack)) {
            return;
        }
        PadDocument document = PadClient.cachedDocumentFor(stack);
        if (document.locked() || document.triggerPoints().size() >= PadDocument.MAX_TRIGGER_POINTS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        PadMapSnapshot map = PadMapClientCache.snapshot(minecraft.player.blockPosition().getX(),
                minecraft.player.blockPosition().getZ());
        PadMapProjection.Rect mapRect = visibleMapRect();
        PadMapProjection.Viewport viewport = PadMapProjection.viewport(map, mapRect,
                minecraft.player.getX(), minecraft.player.getZ());
        float worldX = PadMapProjection.screenToWorldX(point.x(), map, viewport);
        float worldZ = PadMapProjection.screenToWorldZ(point.y(), map, viewport);
        PadTriggerPoint created = PadTriggerPoint.createManual(mediaName, worldX, minecraft.player.getY(), worldZ,
                mediaId);
        writeDocument(stack, document.withTrigger(created));
        PadFocusState.selectPoint(created.pointId());
        PadFocusState.pressFeedback(point.x(), point.y(), "MAP");
    }

    private void moveSelectedPoint(TexturePoint point) {
        ItemStack stack = heldPadStack();
        PadDocument document = PadClient.cachedDocumentFor(stack);
        if (!PadItem.isPad(stack) || document.locked()) {
            return;
        }
        PadTriggerPoint selected = selectedPoint(document);
        if (selected == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        PadMapSnapshot map = PadMapClientCache.snapshot(minecraft.player.blockPosition().getX(),
                minecraft.player.blockPosition().getZ());
        PadMapProjection.Viewport viewport = PadMapProjection.viewport(map, visibleMapRect(),
                minecraft.player.getX(), minecraft.player.getZ());
        writeDocument(stack, document.withTrigger(new PadTriggerPoint(selected.pointId(), selected.name(),
                PadMapProjection.screenToWorldX(point.x(), map, viewport), selected.y(),
                PadMapProjection.screenToWorldZ(point.y(), map, viewport), selected.radiusBlocks(), selected.mediaId(),
                selected.triggerMode(), selected.loop(),
                selected.volumePerMille(), selected.visible())));
    }

    private PadTriggerPoint pointAt(TexturePoint point) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return null;
        }
        PadDocument document = PadClient.cachedDocumentFor(heldPadStack());
        PadMapSnapshot map = PadMapClientCache.snapshot(minecraft.player.blockPosition().getX(),
                minecraft.player.blockPosition().getZ());
        PadMapProjection.Viewport viewport = PadMapProjection.viewport(map, visibleMapRect(),
                minecraft.player.getX(), minecraft.player.getZ());
        for (int i = document.triggerPoints().size() - 1; i >= 0; i--) {
            PadTriggerPoint trigger = document.triggerPoints().get(i);
            if (document.locked() && !trigger.visible()) {
                continue;
            }
            int px = Math.round(PadMapProjection.mapScreenX((float) trigger.x(), map, viewport));
            int pz = Math.round(PadMapProjection.mapScreenY((float) trigger.z(), map, viewport));
            if (Math.abs(point.x() - px) <= 10 && Math.abs(point.y() - pz) <= 14) {
                return trigger;
            }
        }
        return null;
    }

    private PadTriggerPoint selectedPoint(PadDocument document) {
        UUID selected = PadFocusState.selectedPointId();
        if (selected == null) {
            return null;
        }
        return document.triggerPoints().stream().filter(point -> selected.equals(point.pointId())).findFirst()
                .orElse(null);
    }

    private void updatePoint(PadTriggerPoint point) {
        ItemStack stack = heldPadStack();
        PadDocument document = PadClient.cachedDocumentFor(stack);
        if (!PadItem.isPad(stack) || document.locked()) {
            return;
        }
        writeDocument(stack, document.withTrigger(point));
        PadFocusState.selectPoint(point.pointId());
    }

    private void removePoint(UUID pointId) {
        ItemStack stack = heldPadStack();
        PadDocument document = PadClient.cachedDocumentFor(stack);
        if (!PadItem.isPad(stack) || document.locked() || pointId == null) {
            return;
        }
        ArrayList<PadTriggerPoint> points = new ArrayList<>(document.triggerPoints());
        if (points.removeIf(point -> pointId.equals(point.pointId()))) {
            writeDocument(stack, new PadDocument(document.title(), document.author(), document.locked(),
                    System.currentTimeMillis(), document.sequence() + 1, document.mapSettings(),
                    document.mediaEntries(), points));
            PadFocusState.selectPoint(null);
        }
    }

    private void playPoint(PadTriggerPoint point) {
        if (point == null || point.triggerMode() != PadTriggerMode.MANUAL) {
            return;
        }
        UUID deviceId = PadItem.readDeviceId(heldPadStack());
        if (deviceId == null) {
            return;
        }
        ClientPacketDistributor.sendToServer(new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.START,
                deviceId, point.pointId(), 0L));
        PadFocusState.selectPoint(point.pointId());
    }

    private void stopPlayback() {
        UUID deviceId = PadItem.readDeviceId(heldPadStack());
        if (deviceId == null || !ClientMediaPlayback.hasPlayback(deviceId)) {
            PadFocusState.clearPausedPlayback();
            return;
        }
        PadFocusState.clearPausedPlayback();
        ClientPacketDistributor.sendToServer(new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.STOP,
                deviceId, null, ClientMediaPlayback.elapsedMillis(deviceId)));
    }

    private void pausePlayback() {
        UUID deviceId = PadItem.readDeviceId(heldPadStack());
        if (deviceId == null || !ClientMediaPlayback.hasPlayback(deviceId)) {
            return;
        }
        UUID pointId = activePlaybackPointId(deviceId);
        long elapsedMillis = ClientMediaPlayback.elapsedMillis(deviceId);
        if (pointId != null) {
            PadFocusState.rememberPausedPlayback(pointId, elapsedMillis,
                    ClientMediaPlayback.durationMillis(deviceId), MP4HandheldVideoClient.latestFrame(deviceId) != null);
        }
        ClientPacketDistributor.sendToServer(new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.PAUSE,
                deviceId, null, elapsedMillis));
    }

    private void togglePlayback() {
        UUID deviceId = PadItem.readDeviceId(heldPadStack());
        if (deviceId == null) {
            return;
        }
        if (ClientMediaPlayback.hasPlayback(deviceId)) {
            pausePlayback();
            return;
        }
        UUID pointId = PadFocusState.pausedPointId();
        if (pointId == null) {
            return;
        }
        ClientPacketDistributor.sendToServer(new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.START,
                deviceId, pointId, PadFocusState.pausedElapsedMillis()));
        PadFocusState.clearPausedPlayback();
    }

    private void seekPlayback(long targetMillis) {
        UUID deviceId = PadItem.readDeviceId(heldPadStack());
        if (deviceId == null) {
            return;
        }
        if (!ClientMediaPlayback.hasPlayback(deviceId)) {
            UUID pointId = PadFocusState.pausedPointId();
            if (pointId != null) {
                PadFocusState.rememberPausedPlayback(pointId, targetMillis,
                        PadFocusState.pausedDurationMillis(), PadFocusState.pausedVideo());
            }
            return;
        }
        ClientPacketDistributor.sendToServer(new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.SEEK,
                deviceId, null, Math.max(0L, targetMillis)));
    }

    private UUID activePlaybackPointId(UUID deviceId) {
        int mediaId = ClientMediaPlayback.queueIndex(deviceId);
        if (mediaId < 0) {
            return null;
        }
        PadDocument document = PadClient.cachedDocumentFor(heldPadStack());
        for (PadTriggerPoint point : document.triggerPoints()) {
            if (point.mediaId() == mediaId) {
                return point.pointId();
            }
        }
        return null;
    }

    private long currentProgressMillis() {
        UUID deviceId = PadItem.readDeviceId(heldPadStack());
        long duration = deviceId != null ? ClientMediaPlayback.durationMillis(deviceId) : 0L;
        if (duration <= 0L && PadFocusState.pausedPlaybackAvailable()) {
            duration = PadFocusState.pausedDurationMillis();
        }
        return Math.round(PadFocusState.mediaProgress() * Math.max(0L, duration));
    }

    private float videoProgressAt(TexturePoint point) {
        int x0 = 32;
        int w = TEXTURE_W - 64;
        return Math.max(0.0F, Math.min(1.0F, (point.x() - x0) / (float) Math.max(1, w)));
    }

    private float audioProgressAt(TexturePoint point) {
        int x0 = 36;
        int w = TEXTURE_W - 72;
        return Math.max(0.0F, Math.min(1.0F, (point.x() - x0) / (float) Math.max(1, w)));
    }

    private PadTriggerPoint rebuildPoint(PadTriggerPoint point, int radius, boolean visible, int volume, boolean loop) {
        return rebuildPoint(point, radius, visible, volume, loop, point.triggerMode());
    }

    private PadTriggerPoint rebuildPoint(PadTriggerPoint point, int radius, boolean visible, int volume, boolean loop,
            PadTriggerMode mode) {
        return new PadTriggerPoint(point.pointId(), point.name(), point.x(), point.y(), point.z(), radius,
                point.mediaId(), mode, loop, volume, visible);
    }

    private PadMediaEntry mediaEntryAt(TexturePoint point) {
        int localY = point.y() - MEDIA_Y;
        if (localY < 30) {
            return null;
        }
        int row = (localY - 30) / 16;
        if (row < 0 || row >= 4) {
            return null;
        }
        PadDocument document = PadClient.cachedDocumentFor(heldPadStack());
        return row < document.mediaEntries().size() ? document.mediaEntries().get(row) : null;
    }

    private void writeDocument(ItemStack stack, PadDocument document) {
        PadItem.writeDocument(stack, document);
        PadClient.markDocumentDirty(stack, document);
    }

    private String mediaName(PadMediaEntry entry) {
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(entry.disc());
        if (info != null && info.songName != null && !info.songName.isBlank()) {
            return info.songName;
        }
        return "歌曲 #" + entry.mediaId();
    }

    private ItemStack heldPadStack() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = minecraft.player.getItemInHand(hand);
        return PadItem.isPad(stack) ? stack : ItemStack.EMPTY;
    }

    private PadMapProjection.Rect visibleMapRect() {
        PadDocument document = PadClient.cachedDocumentFor(heldPadStack());
        int x = document.locked() ? LOCKED_MAP_X : MAP_X;
        int y = document.locked() ? LOCKED_MAP_Y : MAP_Y;
        int w = document.locked() ? LOCKED_MAP_W : MAP_W;
        int h = document.locked() ? LOCKED_MAP_H : MAP_H;
        int inset = document.locked() ? 0 : 6;
        if (document.locked()) {
            return new PadMapProjection.Rect(x, y, w, h);
        }
        return PadMapProjection.fitRect(x + inset, y + inset, w - inset * 2, h - inset * 2,
                PadMapSampler.DEFAULT_VIEW_WIDTH, PadMapSampler.DEFAULT_VIEW_HEIGHT, TEXTURE_W / (float) TEXTURE_H);
    }

    private String hit(TexturePoint point) {
        PadDocument document = PadClient.cachedDocumentFor(heldPadStack());
        UUID deviceId = PadItem.readDeviceId(heldPadStack());
        boolean hasPlayback = ClientMediaPlayback.hasPlayback(deviceId);
        if (document.locked() && (hasPlayback
                || PadFocusState.pausedPlaybackAvailable())) {
            boolean videoControls = PadFocusState.pausedVideo()
                    || (hasPlayback && MP4HandheldVideoClient.latestFrame(deviceId) != null);
            if (!videoControls) {
                if (PadFocusState.subtitleMenuOpen()) {
                    if (inside(point, 244, 72, 52, 14)) {
                        return "SUBTITLE_OFF";
                    }
                    if (inside(point, 244, 94, 52, 14)) {
                        return "SUBTITLE_PRIMARY";
                    }
                    if (inside(point, 244, 116, 52, 14)) {
                        return "SUBTITLE_SECONDARY";
                    }
                    if (inside(point, 244, 138, 82, 14)) {
                        return "SUBTITLE_AI";
                    }
                }
                if (inside(point, 36, 194, TEXTURE_W - 72, 16)) {
                    return "AUDIO_PROGRESS";
                }
                if (inside(point, 146, 210, 30, 24)) {
                    return "AUDIO_STOP";
                }
                if (inside(point, 200, 206, 46, 32)) {
                    return "AUDIO_PLAY";
                }
                if (inside(point, 270, 210, 54, 24)) {
                    return "AUDIO_SUBTITLE";
                }
            }
            if (PadFocusState.controlsVisible()) {
                if (PadFocusState.qualityMenuOpen()) {
                    for (int i = 0; i < PadFocusState.QUALITIES.length; i++) {
                        if (inside(point, 330, 65 + i * 13, 70, 11)) {
                            return "QUALITY_" + i;
                        }
                    }
                }
                if (PadFocusState.subtitleMenuOpen()) {
                    if (inside(point, 244, 72, 52, 14)) {
                        return "SUBTITLE_OFF";
                    }
                    if (inside(point, 244, 94, 52, 14)) {
                        return "SUBTITLE_PRIMARY";
                    }
                    if (inside(point, 244, 116, 52, 14)) {
                        return "SUBTITLE_SECONDARY";
                    }
                    if (inside(point, 244, 138, 82, 14)) {
                        return "SUBTITLE_AI";
                    }
                }
                if (inside(point, 278, 16, 44, 20)) {
                    return "VIDEO_SUBTITLE";
                }
                if (inside(point, 330, 14, 52, 28)) {
                    return "VIDEO_QUALITY";
                }
                if (inside(point, 32, 176, TEXTURE_W - 64, 18)) {
                    return "VIDEO_PROGRESS";
                }
                if (inside(point, 146, 204, 30, 24)) {
                    return "VIDEO_STOP";
                }
                if (inside(point, 200, 198, 46, 34)) {
                    return "VIDEO_PLAY";
                }
                if (inside(point, 270, 204, 54, 24)) {
                    return "VIDEO_QUALITY";
                }
            }
            if (insideMap(point, document)) {
                return "MAP";
            }
        }
        if (insideMap(point, document)) {
            return "MAP";
        }
        if (document.locked()) {
            return "NONE";
        }
        if (inside(point, MEDIA_X, MEDIA_Y, MEDIA_W, MEDIA_H)) {
            return "MEDIA";
        }
        if (inside(point, EDITOR_X, EDITOR_Y, EDITOR_W, EDITOR_H)) {
            return "EDITOR";
        }
        if (inside(point, 300, 166, 132, 42)) {
            return "PLAYBACK";
        }
        if (inside(point, PUBLISH_X, PUBLISH_Y, PUBLISH_W, PUBLISH_H)) {
            return "PUBLISH";
        }
        return "NONE";
    }

    private boolean insideMap(TexturePoint point, PadDocument document) {
        if (document != null && document.locked()) {
            return inside(point, LOCKED_MAP_X, LOCKED_MAP_Y, LOCKED_MAP_W, LOCKED_MAP_H);
        }
        return inside(point, MAP_X, MAP_Y, MAP_W, MAP_H);
    }

    private boolean inside(TexturePoint point, int x, int y, int w, int h) {
        return point.x() >= x && point.y() >= y && point.x() < x + w && point.y() < y + h;
    }

    private TexturePoint toTexturePoint(int mouseX, int mouseY) {
        Quad quad = projectedInputQuadOrNull();
        return quad != null ? quad.toTexturePoint(mouseX, mouseY) : null;
    }

    private Quad projectedInputQuadOrNull() {
        if (!PadFocusState.hasProjectedQuad(width, height)) {
            return null;
        }
        return new Quad(
                new ScreenPoint(PadFocusState.projectedQuadX(0), PadFocusState.projectedQuadY(0)),
                new ScreenPoint(PadFocusState.projectedQuadX(1), PadFocusState.projectedQuadY(1)),
                new ScreenPoint(PadFocusState.projectedQuadX(2), PadFocusState.projectedQuadY(2)),
                new ScreenPoint(PadFocusState.projectedQuadX(3), PadFocusState.projectedQuadY(3)));
    }

    private record TexturePoint(int x, int y) {
    }

    private record ScreenPoint(float x, float y) {
    }

    private record ProjectedSurface(int left, int top, int width, int height) {
        ProjectedSurface inflate(int amount) {
            return new ProjectedSurface(left - amount, top - amount, width + amount * 2, height + amount * 2);
        }

        boolean contains(int x, int y) {
            return x >= left && y >= top && x < left + width && y < top + height;
        }
    }

    private record Quad(ScreenPoint topLeft, ScreenPoint topRight, ScreenPoint bottomRight, ScreenPoint bottomLeft) {
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
            return new TexturePoint(Math.round(u * (TEXTURE_W - 1)), Math.round(v * (TEXTURE_H - 1)));
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
}