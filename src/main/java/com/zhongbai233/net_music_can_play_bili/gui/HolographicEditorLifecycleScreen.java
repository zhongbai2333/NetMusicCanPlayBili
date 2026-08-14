package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.gizmo.GizmoCoordinateSpace;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.network.HolographicGlassesConfigPacket;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConfigPacket;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Lifecycle, autosave, roaming, and equipped-glasses persistence for the scene editor. */
abstract class HolographicEditorLifecycleScreen extends HolographicEditorScreenState {
    protected HolographicEditorLifecycleScreen(boolean bindEquippedGlasses, BlockPos controlConsolePos) {
        super(bindEquippedGlasses, controlConsolePos);
    }

    protected void restoreRoamingElements(List<ControlConsoleRoamingSession.RoamingElement> elements) {
        if (elements == null || elements.isEmpty()) {
            return;
        }
        screens.clear();
        consoleElementsLoaded = true;
        roamingHistoryPending = true;
        for (ControlConsoleRoamingSession.RoamingElement element : elements) {
            ElementType type;
            try {
                type = ElementType.valueOf(element.type());
            } catch (IllegalArgumentException invalidType) {
                type = ElementType.SCREEN;
            }
            PreviewScreenSpec restored = new PreviewScreenSpec(element.elementId(), type, element.name(), element.distance(),
                    element.offsetX(), element.offsetY(), element.height(), element.aspect(), element.roll());
            restored.yaw = element.yaw();
            restored.pitch = element.pitch();
            restored.contentMode = element.contentMode();
            restored.text = element.text();
            restored.followLyrics = element.followLyrics();
            restored.showTranslation = element.showTranslation();
            restored.textScale = element.textScale();
            restored.color = element.color();
            restored.volume = element.volume();
            restored.channelIndex = element.channelIndex();
            restored.maxDistance = element.maxDistance();
            restored.autoMixJoc = element.autoMixJoc();
            restored.translationColor = element.translationColor();
            restored.backgroundColor = element.backgroundColor();
            restored.alignment = element.alignment();
            restored.maxWidth = element.maxWidth();
            restored.wrap = element.wrap();
            restored.enabled = element.enabled();
            restored.locked = element.locked();
            restored.scaleX = element.scaleX();
            restored.scaleY = element.scaleY();
            restored.scaleZ = element.scaleZ();
            restored.pivotX = element.pivotX();
            restored.pivotY = element.pivotY();
            restored.pivotZ = element.pivotZ();
            restored.skewXByY = element.skewXByY();
            restored.skewYByX = element.skewYByX();
            screens.add(restored);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (controlConsoleMode && !validControlConsoleHost()) {
            onClose();
            return;
        }
        if (controlConsoleMode) {
            com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient.tickLease(controlConsolePos);
            if (!com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient.hasLease(controlConsolePos)) {
                onClose();
                return;
            }
        }
        ControlConsoleDocument authoritative = controlConsoleDocument();
        if (authoritative != null && consoleDraft != null && !consoleSaveConflict
            && authoritative.revision() > consoleDraft.revision()) {
            installAuthoritativeConsoleDocument(authoritative, "已同步服务器版本");
            init();
        }
        if (controlConsoleMode) {
            processConsoleAutosave();
        }
    }

    protected void processConsoleAutosave() {
        ControlConsoleDocument draft = currentConsoleDocument();
        if (draft == null || consoleSaveConflict) {
            return;
        }
        int fingerprint = consoleDraftFingerprint(draft);
        if (!consoleAutosaveFingerprintInitialized) {
            consoleSavedFingerprint = fingerprint;
            consoleObservedFingerprint = fingerprint;
            consoleAutosaveFingerprintInitialized = true;
            return;
        }
        if (fingerprint != consoleObservedFingerprint) {
            consoleObservedFingerprint = fingerprint;
            consoleAutosaveTick = 10L;
        } else if (consoleAutosaveTick > 0L) {
            consoleAutosaveTick--;
        }
        if (consoleAutosaveTick == 0L && consolePendingOperation == null && fingerprint != consoleSavedFingerprint) {
            sendConsoleAutosave();
        }
    }

    protected void sendConsoleAutosave() {
        if (controlConsolePos == null || consoleDraft == null || consolePendingOperation != null) {
            return;
        }
        UUID operationId = UUID.randomUUID();
        List<ControlConsoleElement> elements = consoleElementsSnapshot();
        consolePendingFingerprint = java.util.Objects.hash(consoleDraft.displayName(), consoleDraft.hardRangeX(),
            consoleDraft.hardRangeY(), consoleDraft.hardRangeZ(), elements);
        consoleDraft = new ControlConsoleDocument(consoleDraft.schemaVersion(), consoleDraft.consoleId(), consoleDraft.revision(),
            consoleDraft.ownerId(), consoleDraft.accessMode(), consoleDraft.trustedPlayerIds(),
            consoleDraft.displayName(), consoleDraft.sourceDimension(), consoleDraft.sourceKind(), consoleDraft.sourceX(),
            consoleDraft.sourceY(), consoleDraft.sourceZ(), consoleDraft.hardRangeX(),
            consoleDraft.hardRangeY(), consoleDraft.hardRangeZ(), elements);
        consolePendingOperation = operationId;
        consoleSaveStatus = "保存中…";
        UUID leaseId = com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient.leaseId(controlConsolePos);
        if (leaseId == null) {
            consolePendingOperation = null;
            consoleSaveStatus = "编辑租约不可用";
            return;
        }
        ClientPacketDistributor.sendToServer(new ControlConsoleConfigPacket(controlConsolePos, leaseId, operationId,
                consoleDraft.revision(), consoleDraft.displayName(), consoleDraft.hardRangeX(),
                consoleDraft.hardRangeY(), consoleDraft.hardRangeZ(), consoleElementsSnapshot()));
    }

    protected int consoleDraftFingerprint(ControlConsoleDocument draft) {
        return java.util.Objects.hash(draft.displayName(), draft.hardRangeX(), draft.hardRangeY(), draft.hardRangeZ(),
                consoleElementsSnapshot());
    }

    public static void acceptControlConsoleConfigResult(
            com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConfigResultPacket result) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof HolographicScreenConfigTestScreen screen) {
            screen.acceptConsoleConfigResult(result);
        }
    }

    protected void acceptConsoleConfigResult(
            com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConfigResultPacket result) {
        if (!controlConsoleMode || !java.util.Objects.equals(consolePendingOperation, result.operationId())) {
            return;
        }
        consolePendingOperation = null;
        switch (result.status()) {
            case APPLIED, DUPLICATE -> {
                consoleConflictAuthoritative = null;
                consoleDraft = consoleDraft.withRevision(result.revision());
                consoleSaveStatus = consoleAccessRollback != null ? "权限设置已保存" : "已自动保存";
                consoleSavedFingerprint = consolePendingFingerprint;
                consoleAutosaveTick = 10L;
                consoleAccessRollback = null;
            }
            case CONFLICT -> {
                restoreAccessRollback();
                consoleConflictAuthoritative = result.authoritativeDocument();
                consoleSaveConflict = true;
                consoleSaveStatus = "版本冲突：已收到服务器版本，本地修改未覆盖";
                init();
            }
            case READ_ONLY -> {
                consoleConflictAuthoritative = null;
                restoreAccessRollback();
                consoleSaveConflict = true;
                consoleSaveStatus = "文档版本过新：当前版本仅允许只读查看";
                init();
            }
            case REJECTED -> {
                consoleConflictAuthoritative = null;
                restoreAccessRollback();
                consoleSaveStatus = "保存被服务器拒绝";
                init();
            }
        }
    }

    protected void restoreAccessRollback() {
        if (consoleAccessRollback != null) {
            consoleDraft = consoleAccessRollback;
            consoleAccessModeDraft = consoleDraft.accessMode();
            consoleAccessRollback = null;
        }
    }

    protected void reloadAuthoritativeConsoleDocument() {
        ControlConsoleDocument authoritative = consoleConflictAuthoritative != null
                ? consoleConflictAuthoritative : controlConsoleDocument();
        if (authoritative == null) {
            return;
        }
        installAuthoritativeConsoleDocument(authoritative, "已重新加载服务器版本");
        init();
    }

    protected void installAuthoritativeConsoleDocument(ControlConsoleDocument authoritative, String status) {
        consoleDraft = authoritative;
        consoleAccessModeDraft = authoritative.accessMode();
        consoleAccessRollback = null;
        consoleConflictAuthoritative = null;
        consoleSaveConflict = false;
        consoleSaveStatus = status;
        consolePendingOperation = null;
        consoleAutosaveTick = 0L;
        consoleSavedFingerprint = documentFingerprint(authoritative);
        consoleObservedFingerprint = consoleSavedFingerprint;
        consoleAutosaveFingerprintInitialized = true;
        loadConsoleElements(authoritative);
    }

    protected boolean validControlConsoleHost() {
        if (minecraft == null || minecraft.level != controlConsoleLevel || minecraft.player != controlConsolePlayer
                || minecraft.player == null || !minecraft.player.isAlive() || controlConsolePos == null) {
            return false;
        }
        double centerX = controlConsolePos.getX() + 0.5D;
        double centerY = controlConsolePos.getY() + 0.5D;
        double centerZ = controlConsolePos.getZ() + 0.5D;
        if (minecraft.player.distanceToSqr(centerX, centerY, centerZ) > 64.0D) {
            return false;
        }
        return minecraft.level.hasChunk(Math.floorDiv(controlConsolePos.getX(), 16),
            Math.floorDiv(controlConsolePos.getZ(), 16))
                && minecraft.level.getBlockEntity(controlConsolePos) instanceof ControlConsoleBlockEntity;
    }

    /** 由 RenderFrameEvent.Pre 每个实际渲染帧调用一次，避免 20 Hz tick 飞行产生阶梯感。 */
    public void advanceCameraFrame(double deltaSeconds) {
        if (getFocused() instanceof EditBox || firstPersonPreview) {
            return;
        }
        if (flyForward || flyBackward || flyLeft || flyRight || flyDown || flyUp) {
            setPreviewCamera(navigationMode()
                    ? cameraController.walk(previewCamera, flyForward, flyBackward, flyLeft, flyRight,
                            flyDown, flyUp, deltaSeconds, flyFast, EDITOR_WORLD_UP)
                    : cameraController.fly(previewCamera, flyForward, flyBackward, flyLeft, flyRight,
                            flyDown, flyUp, deltaSeconds, flyFast, EDITOR_WORLD_UP));
        }
    }

    @Override
    public void onClose() {
        clearFlyKeys();
        if (controlConsoleMode && consoleDraft != null && consolePendingOperation == null
            && !consoleSaveConflict && consoleAutosaveFingerprintInitialized
            && consoleDraftFingerprint(consoleDraft) != consoleSavedFingerprint) {
            sendConsoleAutosave();
        }
        saveEquippedGlassesConfig();
        if (controlConsolePos != null) {
            TerrainPreviewManager.close(controlConsolePos);
            if (!transferLeaseToRoaming) {
                com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient.releaseLease(controlConsolePos);
            }
        }
        clearNumericPanelRefs();
        super.onClose();
    }

    protected void startWorldRoaming() {
        if (!controlConsoleMode || worldRoamingTransitionPending || !validControlConsoleHost()
                || minecraft == null || controlConsolePos == null) {
            return;
        }
        worldRoamingTransitionPending = true;
        transferLeaseToRoaming = true;
        Minecraft client = minecraft;
        BlockPos origin = controlConsolePos;
        Vector3d localPosition = previewCamera.position();
        Quaternionf localOrientation = previewCamera.orientation();
        onClose();
        client.execute(() -> {
            if (client.screen == null) {
                if (!ControlConsoleRoamingSession.start(origin, localPosition, localOrientation,
                        roamingElementsSnapshot())) {
                    com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient.releaseLease(origin);
                }
            } else {
                com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient.releaseLease(origin);
            }
        });
    }

    protected List<ControlConsoleRoamingSession.RoamingElement> roamingElementsSnapshot() {
        List<ControlConsoleRoamingSession.RoamingElement> snapshot = new ArrayList<>(screens.size());
        for (PreviewScreenSpec screen : screens) {
                snapshot.add(new ControlConsoleRoamingSession.RoamingElement(screen.elementId, screen.type.name(), screen.name,
                    screen.distance, screen.offsetX, screen.offsetY, screen.height, screen.aspect, screen.yaw,
                    screen.pitch, screen.roll, screen.contentMode, screen.text, screen.followLyrics,
                    screen.showTranslation, screen.textScale, screen.color, screen.volume, screen.channelIndex,
                    screen.maxDistance, screen.autoMixJoc, screen.translationColor, screen.backgroundColor,
                    screen.alignment, screen.maxWidth, screen.wrap, screen.enabled, screen.locked,
                    screen.scaleX, screen.scaleY, screen.scaleZ, screen.pivotX, screen.pivotY, screen.pivotZ,
                    screen.skewXByY, screen.skewYByX));
        }
        return List.copyOf(snapshot);
    }

    protected void loadEquippedGlassesConfig() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        ItemStack head = EquippedMediaItems.firstHolographicGlasses(player);
        if (!HolographicGlassesAbility.has(head)) {
            return;
        }
        List<HolographicGlassesItem.ScreenBinding> bindings = HolographicGlassesItem.readScreenBindings(head);
        screens.clear();
        for (int i = 0; i < bindings.size(); i++) {
            screens.add(PreviewScreenSpec.fromBinding("屏幕 " + (i + 1), bindings.get(i)));
        }
        if (screens.isEmpty()) {
            screens.add(PreviewScreenSpec.defaults());
        }
        selectedScreen = Math.max(0, Math.min(screens.size() - 1, selectedScreen));
    }

    protected void saveEquippedGlassesConfig() {
        if (!bindEquippedGlasses) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        ItemStack head = EquippedMediaItems.firstHolographicGlasses(player);
        if (!HolographicGlassesAbility.has(head)) {
            player.sendSystemMessage(Component.literal("未佩戴全息眼镜，配置未保存"));
            return;
        }
        List<HolographicGlassesItem.ScreenConfig> configs = new ArrayList<>();
        for (int i = 0; i < screens.size(); i++) {
            HolographicGlassesItem.ScreenConfig config = screens.get(i).toConfig();
            configs.add(config);
            ClientPacketDistributor.sendToServer(HolographicGlassesConfigPacket.fromConfig(i, config));
        }
        HolographicGlassesItem.writeScreenConfigs(head, configs);
        player.sendSystemMessage(Component.literal("全息眼镜配置已保存（" + configs.size() + " 屏）"));
    }

    @Override
    protected void init() {
        clearWidgets();
        clearNumericPanelRefs();
        if (controlConsoleMode) {
            ensureConsoleDocumentLoaded();
        }

        int iconY = 4;
        int buttonCount = controlConsoleMode ? 6 : 5;
        int startX = width - 8 - (ICON_W * buttonCount + ICON_GAP * (buttonCount - 1));
        int x = startX;
        addRenderableWidget(new BlackGoldButton(x, iconY, ICON_W, ICON_H,
                Component.literal("\u21F1"), btn -> activeTool = EditTool.MOVE, GOLD));
        x += ICON_W + ICON_GAP;
        addRenderableWidget(new BlackGoldButton(x, iconY, ICON_W, ICON_H,
                Component.literal("\u21BB"), btn -> activeTool = EditTool.ROTATE, GOLD));
        x += ICON_W + ICON_GAP;
        addRenderableWidget(new BlackGoldButton(x, iconY, ICON_W, ICON_H,
                Component.literal("\u21F2"), btn -> activeTool = EditTool.SCALE, GOLD));
        x += ICON_W + ICON_GAP;
        addRenderableWidget(new BlackGoldButton(x, iconY, ICON_W, ICON_H,
                Component.literal(coordinateSpace == GizmoCoordinateSpace.LOCAL ? "本" : "世"), btn -> {
                    coordinateSpace = coordinateSpace == GizmoCoordinateSpace.LOCAL
                            ? GizmoCoordinateSpace.WORLD : GizmoCoordinateSpace.LOCAL;
                    btn.setMessage(Component.literal(coordinateSpace == GizmoCoordinateSpace.LOCAL ? "本" : "世"));
                }, GOLD));
        x += ICON_W + ICON_GAP;
        if (controlConsoleMode) {
            addRenderableWidget(new BlackGoldButton(x, iconY, ICON_W, ICON_H,
                Component.literal("魂"), btn -> startWorldRoaming(), 0xFF45B7E7));
            x += ICON_W + ICON_GAP;
        }
        addRenderableWidget(new BlackGoldButton(x, iconY, ICON_W, ICON_H,
                Component.literal("\u2715"), btn -> onClose(), 0xFFD04040));

        if (showNumericPanel) {
            addNumericPanelWidgets();
        }
        if (controlConsoleMode) {
            addControlConsoleWidgets();
            addControlConsoleInspectorWidgets();
            addControlConsoleDocumentWidgets();
        }
        applyInitialElementFocus();
    }

    protected abstract ControlConsoleDocument controlConsoleDocument();
    protected abstract ControlConsoleDocument currentConsoleDocument();
    protected abstract List<ControlConsoleElement> consoleElementsSnapshot();
    protected abstract void loadConsoleElements(ControlConsoleDocument document);
    protected abstract void setPreviewCamera(EditorCameraState camera);
    protected abstract boolean navigationMode();
    protected abstract void clearFlyKeys();
    protected abstract void clearNumericPanelRefs();
    protected abstract void ensureConsoleDocumentLoaded();
    protected abstract void addNumericPanelWidgets();
    protected abstract void addControlConsoleWidgets();
    protected abstract void addControlConsoleInspectorWidgets();
    protected abstract void addControlConsoleDocumentWidgets();
    protected abstract void applyInitialElementFocus();
    protected abstract void focusControlConsoleCenter();
    protected abstract void focusSelectedScreen();
    protected abstract void syncNumericEditBoxes();
    protected abstract void selectElement(int index);
    protected abstract boolean selectedElementEditable();
    protected abstract void edit(String description, Runnable mutation);
    protected abstract EditorSceneState snapshotScene();

}
