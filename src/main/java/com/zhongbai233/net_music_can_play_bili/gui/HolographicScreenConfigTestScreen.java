package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import com.zhongbai233.net_music_can_play_bili.client.renderer.gui.HolographicPreviewPipRenderState;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.editor.core.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.core.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.core.camera.CameraFrame;
import com.zhongbai233.net_music_can_play_bili.editor.core.camera.CameraMatrices;
import com.zhongbai233.net_music_can_play_bili.editor.core.camera.EditorCameraController;
import com.zhongbai233.net_music_can_play_bili.editor.core.camera.EditorCameraMode;
import com.zhongbai233.net_music_can_play_bili.editor.core.camera.EditorCameraState;
import com.zhongbai233.net_music_can_play_bili.editor.core.camera.StandardCameraView;
import com.zhongbai233.net_music_can_play_bili.editor.core.projection.EditorProjection;
import com.zhongbai233.net_music_can_play_bili.editor.core.projection.EditorViewport;
import com.zhongbai233.net_music_can_play_bili.editor.core.projection.PickingRay;
import com.zhongbai233.net_music_can_play_bili.editor.core.projection.ProjectedPoint;
import com.zhongbai233.net_music_can_play_bili.editor.core.media.SubtitleLayout;
import com.zhongbai233.net_music_can_play_bili.editor.core.selection.BlankClickSelectionPolicy;
import com.zhongbai233.net_music_can_play_bili.editor.core.gizmo.GizmoConstraint;
import com.zhongbai233.net_music_can_play_bili.editor.core.gizmo.GizmoDragMath;
import com.zhongbai233.net_music_can_play_bili.editor.core.command.CommandStack;
import com.zhongbai233.net_music_can_play_bili.editor.core.transaction.DragTransaction;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.link.HolographicScreenSettings;
import com.zhongbai233.net_music_can_play_bili.mixin.GuiGraphicsExtractorAccessor;
import com.zhongbai233.net_music_can_play_bili.network.HolographicGlassesConfigPacket;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConfigPacket;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleAccessPacket;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 全息眼镜屏幕配置界面。
 */
public class HolographicScreenConfigTestScreen extends Screen {
    private static final int GOLD = BlackGoldScreen.GOLD;
    private static final int GOLD_DIM = BlackGoldScreen.GOLD_DIM;
    private static final int BG_HEADER = BlackGoldScreen.BG_HEADER;
    private static final int TEXT_SECONDARY = BlackGoldScreen.TEXT_SECONDARY;
    private static final int TEXT_DIM = BlackGoldScreen.TEXT_DIM;

    private static final float DEFAULT_PREVIEW_SCALE = HolographicScreenSettings.DEFAULT_PREVIEW_SCALE;
    private static final double MIN_CAMERA_SCALE = 1.0e-4D;
    private static final double MAX_CAMERA_SCALE = 1.0e6D;
    private static final float EDITOR_FAR_PLANE = 1.0e6F;
    private static final float PLAYER_HEAD_RELATIVE_YAW = 0.0F;
    private static final float DEFAULT_PREVIEW_YAW = 180.0F;
    private static final float DEFAULT_PREVIEW_PITCH = 35.0F;
    private static final double ORBIT_FOV_DEGREES = HolographicScreenSettings.ORBIT_FOV_DEGREES;
    private static final double ORBIT_DEFAULT_CAMERA_DISTANCE = HolographicScreenSettings.ORBIT_DEFAULT_CAMERA_DISTANCE;
    private static final double ORBIT_TARGET_Y = HolographicScreenSettings.ORBIT_TARGET_Y;
    private static final int ICON_W = 22;
    private static final int ICON_H = 18;
    private static final int ICON_GAP = 3;
    private static final int GIZMO_HIT_RADIUS = 7;
    private static final double GIZMO_AXIS_WORLD_LEN = HolographicScreenSettings.GIZMO_AXIS_WORLD_LEN;
    private static final int GIZMO_RING_SEGMENTS = 48;
    private static final double ORBIT_YAW_DEGREES_PER_PIXEL = 0.35D;
    private static final double ORBIT_PITCH_DEGREES_PER_PIXEL = 0.30D;
    private static final double PAN_SENSITIVITY = 0.82D;
    private static final int ORIENTATION_WIDGET_RADIUS = 18;
    private static final int ORIENTATION_WIDGET_MARGIN_RIGHT = 30;
    private static final int ORIENTATION_WIDGET_MARGIN_BOTTOM = 40;
    private static final int CONTROL_LEFT_PANEL_W = 156;
    private static final int CONTROL_RIGHT_PANEL_W = 226;
    private static final int CONTROL_PANEL_GAP = 4;
    private static final Vector3d EDITOR_WORLD_UP = new Vector3d(0.0D, 1.0D, 0.0D);
    private static final Vector3d PREVIEW_PLAYER_BOUNDS_MIN = new Vector3d(-0.345D, -0.03D, -0.345D);
    private static final Vector3d PREVIEW_PLAYER_BOUNDS_MAX = new Vector3d(0.345D, 1.88D, 0.345D);

    private final List<PreviewScreenSpec> screens = new ArrayList<>(List.of(PreviewScreenSpec.defaults()));
    private int selectedScreen;
    private int consoleElementScroll;
    private final boolean bindEquippedGlasses;
    private final BlockPos controlConsolePos;
    private final boolean controlConsoleMode;
    private final Level controlConsoleLevel;
    private final Player controlConsolePlayer;
    private EditTool activeTool = EditTool.MOVE;
    private DragMode dragMode = DragMode.NONE;
    private GizmoHandle activeHandle = GizmoHandle.NONE;

    private float previewScale = DEFAULT_PREVIEW_SCALE;
    private final EditorCameraController cameraController = new EditorCameraController(
            new EditorCameraController.Settings(0.08D,
                MIN_CAMERA_SCALE, MAX_CAMERA_SCALE,
                MIN_CAMERA_SCALE, MAX_CAMERA_SCALE, 8.0D, 2.0D, 0.05D, 1.15D));
    private EditorCameraState previewCamera = legacyOrbitCamera(DEFAULT_PREVIEW_YAW, DEFAULT_PREVIEW_PITCH,
            ORBIT_DEFAULT_CAMERA_DISTANCE, new Vector3d(0.0D, ORBIT_TARGET_Y, 0.0D));
    private EditorCameraState modelingCamera = previewCamera;
    private EditorCameraState navigationCamera;
    private boolean draggingPreview;
    private int previewDragButton = -1;
    private boolean firstPersonPreview;
    private double lastMouseX;
    private double lastMouseY;
    private double previewClickX;
    private double previewClickY;
    private boolean previewClickHitPlayer;
    private boolean previewDragStartedWithoutElement;
    private CameraFrame lastOrbitCameraFrame;
    private boolean flyForward;
    private boolean flyBackward;
    private boolean flyLeft;
    private boolean flyRight;
    private boolean flyDown;
    private boolean flyUp;
    private boolean flyFast;
    private GizmoDragSession gizmoDragSession;
    private final CommandStack<ScreenEditState> editHistory = new CommandStack<>(128);
    private DragTransaction<ScreenEditState> gizmoTransaction;

    private boolean showNumericPanel;
    private EditBox numericDistanceBox;
    private EditBox numericOffsetXBox;
    private EditBox numericOffsetYBox;
    private EditBox numericHeightBox;
    private EditBox numericAspectBox;
    private EditBox numericRollBox;
    private EditBox numericYawBox;
    private EditBox numericPitchBox;
    private EditBox elementTextBox;
    private EditBox elementTextScaleBox;
    private EditBox elementVolumeBox;
    private EditBox elementMaxDistanceBox;
    private EditBox elementColorBox;
    private EditBox elementTranslationColorBox;
    private EditBox elementBackgroundColorBox;
    private EditBox elementMaxWidthBox;
    private EditBox consoleTrustedPlayersBox;
    private boolean syncingNumericEditBoxes;
    private ControlConsoleDocument.AccessMode consoleAccessModeDraft;
    private ControlConsoleDocument consoleAccessRollback;
    private ControlConsoleDocument consoleDraft;
    private boolean consoleElementsLoaded;
    private long consoleAutosaveTick;
    private int consoleSavedFingerprint;
    private int consolePendingFingerprint;
    private int consoleObservedFingerprint;
    private boolean consoleAutosaveFingerprintInitialized;
    private UUID consolePendingOperation;
    private boolean consoleSaveConflict;
    private String consoleSaveStatus = "";
    private boolean worldRoamingTransitionPending;
    private boolean transferLeaseToRoaming;
    private int initialFocusElement = -1;
    private Vector3d terrainPreviewCenterLocal = new Vector3d(0.0D, 0.5D, 0.0D);

    public HolographicScreenConfigTestScreen() {
        this(false, null);
    }

    public HolographicScreenConfigTestScreen(boolean bindEquippedGlasses) {
        this(bindEquippedGlasses, null);
    }

    private HolographicScreenConfigTestScreen(boolean bindEquippedGlasses, BlockPos controlConsolePos) {
        super(Component.literal(controlConsolePos != null ? "中控台场景建模"
                : bindEquippedGlasses ? "全息眼镜配置" : "全息屏幕配置测试"));
        this.bindEquippedGlasses = bindEquippedGlasses;
        this.controlConsolePos = controlConsolePos != null ? controlConsolePos.immutable() : null;
        this.controlConsoleMode = controlConsolePos != null;
        Minecraft minecraft = Minecraft.getInstance();
        this.controlConsoleLevel = controlConsoleMode ? minecraft.level : null;
        this.controlConsolePlayer = controlConsoleMode ? minecraft.player : null;
        if (bindEquippedGlasses) {
            loadEquippedGlassesConfig();
        }
    }

    public static HolographicScreenConfigTestScreen forControlConsole(BlockPos pos) {
        HolographicScreenConfigTestScreen screen = new HolographicScreenConfigTestScreen(false,
                java.util.Objects.requireNonNull(pos, "pos"));
        // 直接从中控台进入时，先以中控台本体为建模中心，而不是抢焦到主屏。
        screen.initialFocusElement = -2;
        return screen;
    }

    public static HolographicScreenConfigTestScreen forControlConsole(BlockPos pos, int selectedElement) {
        HolographicScreenConfigTestScreen screen = forControlConsole(pos);
        screen.selectedScreen = Math.max(0, Math.min(screen.screens.size() - 1, selectedElement));
        screen.initialFocusElement = screen.selectedScreen;
        return screen;
    }

    public static HolographicScreenConfigTestScreen forControlConsole(BlockPos pos,
            List<ControlConsoleRoamingSession.RoamingElement> elements) {
        HolographicScreenConfigTestScreen screen = forControlConsole(pos);
        screen.restoreRoamingElements(elements);
        return screen;
    }

    public static HolographicScreenConfigTestScreen forControlConsole(BlockPos pos, int selectedElement,
            List<ControlConsoleRoamingSession.RoamingElement> elements) {
        HolographicScreenConfigTestScreen screen = forControlConsole(pos);
        screen.restoreRoamingElements(elements);
        screen.selectedScreen = Math.max(0, Math.min(screen.screens.size() - 1, selectedElement));
        screen.initialFocusElement = screen.selectedScreen;
        return screen;
    }

    private void restoreRoamingElements(List<ControlConsoleRoamingSession.RoamingElement> elements) {
        if (elements == null || elements.isEmpty()) {
            return;
        }
        screens.clear();
        consoleElementsLoaded = true;
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
            consoleDraft = authoritative;
            consoleAccessModeDraft = authoritative.accessMode();
            consoleAccessRollback = null;
            loadConsoleElements(authoritative);
            init();
        }
        if (controlConsoleMode) {
            processConsoleAutosave();
        }
    }

    private void processConsoleAutosave() {
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

    private void sendConsoleAutosave() {
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

    private int consoleDraftFingerprint(ControlConsoleDocument draft) {
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

    private void acceptConsoleConfigResult(
            com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConfigResultPacket result) {
        if (!controlConsoleMode || !java.util.Objects.equals(consolePendingOperation, result.operationId())) {
            return;
        }
        consolePendingOperation = null;
        switch (result.status()) {
            case APPLIED, DUPLICATE -> {
                consoleDraft = consoleDraft.withRevision(result.revision());
                consoleSaveStatus = consoleAccessRollback != null ? "权限设置已保存" : "已自动保存";
                consoleSavedFingerprint = consolePendingFingerprint;
                consoleAutosaveTick = 10L;
                consoleAccessRollback = null;
            }
            case CONFLICT -> {
                restoreAccessRollback();
                consoleSaveConflict = true;
                consoleSaveStatus = "版本冲突：本地修改未覆盖服务器版本";
                init();
            }
            case READ_ONLY -> {
                restoreAccessRollback();
                consoleSaveConflict = true;
                consoleSaveStatus = "文档版本过新：当前版本仅允许只读查看";
                init();
            }
            case REJECTED -> {
                restoreAccessRollback();
                consoleSaveStatus = "保存被服务器拒绝";
                init();
            }
        }
    }

    private void restoreAccessRollback() {
        if (consoleAccessRollback != null) {
            consoleDraft = consoleAccessRollback;
            consoleAccessModeDraft = consoleDraft.accessMode();
            consoleAccessRollback = null;
        }
    }

    private void reloadAuthoritativeConsoleDocument() {
        ControlConsoleDocument authoritative = controlConsoleDocument();
        if (authoritative == null) {
            return;
        }
        consoleDraft = authoritative;
        consoleAccessModeDraft = authoritative.accessMode();
        consoleAccessRollback = null;
        consoleSaveConflict = false;
        consoleSaveStatus = "已重新加载服务器版本";
        consolePendingOperation = null;
        consoleAutosaveTick = 0L;
        consoleSavedFingerprint = consoleDraftFingerprint(authoritative);
        consoleObservedFingerprint = consoleSavedFingerprint;
        consoleAutosaveFingerprintInitialized = true;
        loadConsoleElements(authoritative);
        init();
    }

    private boolean validControlConsoleHost() {
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

    private void startWorldRoaming() {
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

    private List<ControlConsoleRoamingSession.RoamingElement> roamingElementsSnapshot() {
        List<ControlConsoleRoamingSession.RoamingElement> snapshot = new ArrayList<>(screens.size());
        for (PreviewScreenSpec screen : screens) {
                snapshot.add(new ControlConsoleRoamingSession.RoamingElement(screen.elementId, screen.type.name(), screen.name,
                    screen.distance, screen.offsetX, screen.offsetY, screen.height, screen.aspect, screen.yaw,
                    screen.pitch, screen.roll, screen.contentMode, screen.text, screen.followLyrics,
                    screen.showTranslation, screen.textScale, screen.color, screen.volume, screen.channelIndex,
                    screen.maxDistance, screen.autoMixJoc, screen.translationColor, screen.backgroundColor,
                    screen.alignment, screen.maxWidth, screen.wrap, screen.enabled, screen.locked));
        }
        return List.copyOf(snapshot);
    }

    private void loadEquippedGlassesConfig() {
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

    private void saveEquippedGlassesConfig() {
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
        int buttonCount = controlConsoleMode ? 5 : 4;
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

    private void applyInitialElementFocus() {
        int target = initialFocusElement;
        initialFocusElement = -1;
        if (!controlConsoleMode) {
            return;
        }
        if (target == -2) {
            terrainPreviewCenterLocal = new Vector3d(0.0D, 0.5D, 0.0D);
            focusControlConsoleCenter();
            syncNumericEditBoxes();
            return;
        }
        if (target < 0 || target >= screens.size()) {
            return;
        }
        selectElement(target);
        PreviewScreenSpec centered = screens.get(target);
        terrainPreviewCenterLocal = new Vector3d(centered.offsetX, 1.55D + centered.offsetY, centered.distance);
        focusSelectedScreen();
        syncNumericEditBoxes();
    }

    private void addControlConsoleInspectorWidgets() {
        if (selectedScreen < 0) {
            return;
        }
        int panelX = width - CONTROL_RIGHT_PANEL_W;
        int leftX = panelX + 62;
        int rightX = panelX + 164;
        int y = 112;
        int boxW = 54;
        numericDistanceBox = addUnboundedInspectorBox(leftX, y, boxW, "距离", screen().distance, false,
            v -> screen().distance = v);
        numericOffsetXBox = addUnboundedInspectorBox(rightX, y, boxW, "位置X", screen().offsetX, false,
            v -> screen().offsetX = v);
        numericOffsetYBox = addUnboundedInspectorBox(leftX, y + 22, boxW, "位置Y", screen().offsetY, false,
            v -> screen().offsetY = v);
        numericHeightBox = addUnboundedInspectorBox(rightX, y + 22, boxW, "高度", screen().height, true,
            v -> screen().height = v);
        numericAspectBox = addUnboundedInspectorBox(leftX, y + 44, boxW, "比例", screen().aspect, true,
            v -> screen().aspect = v);
        numericYawBox = addUnboundedInspectorBox(rightX, y + 44, boxW, "Yaw", screen().yaw, false,
            v -> screen().yaw = v);
        numericPitchBox = addUnboundedInspectorBox(leftX, y + 66, boxW, "Pitch", screen().pitch, false,
            v -> screen().pitch = v);
        numericRollBox = addUnboundedInspectorBox(rightX, y + 66, boxW, "Roll", screen().roll, false,
            v -> screen().roll = v);
        boolean editable = selectedElementEditable();
        for (EditBox box : List.of(numericDistanceBox, numericOffsetXBox, numericOffsetYBox,
                numericHeightBox, numericAspectBox, numericYawBox, numericPitchBox, numericRollBox)) {
            box.active = editable;
        }
        PreviewScreenSpec selected = screen();
        addRenderableWidget(new BlackGoldButton(panelX + 78, y - 18, 132, 18,
                Component.literal(selected.locked ? "🔒 已锁定（点击解锁）" : "🔓 未锁定（点击锁定）"), button -> {
                    selected.locked = !selected.locked;
                    init();
                }, GOLD_DIM));
        addElementContentWidgets(panelX + 78, y + 94, screen());
    }

        private void addElementContentWidgets(int x, int y, PreviewScreenSpec selected) {
        if (selected.type == ElementType.SUBTITLE) {
            elementTextBox = addConsoleTextBox(x, y, 132, selected.text,
                value -> { if (!selected.locked) selected.text = value; });
            elementTextBox.active = !selected.locked;
            BlackGoldButton lyricsButton = new BlackGoldButton(x, y + 22, 64, 18,
                Component.literal(selected.followLyrics ? "歌词：开" : "歌词：关"),
                button -> { if (!selected.locked) { selected.followLyrics = !selected.followLyrics; init(); } }, GOLD_DIM);
            lyricsButton.active = !selected.locked;
            addRenderableWidget(lyricsButton);
            BlackGoldButton translationButton = new BlackGoldButton(x + 68, y + 22, 64, 18,
                Component.literal(selected.showTranslation ? "翻译：开" : "翻译：关"),
                button -> { if (!selected.locked) { selected.showTranslation = !selected.showTranslation; init(); } }, GOLD_DIM);
            translationButton.active = !selected.locked;
            addRenderableWidget(translationButton);
            BlackGoldButton modeButton = new BlackGoldButton(x, y + 66, 64, 18,
                Component.literal(subtitleModeLabel(selected.contentMode)), button -> {
                    if (!selected.locked) { cycleSubtitleMode(selected); init(); }
                }, GOLD_DIM);
            BlackGoldButton trackButton = new BlackGoldButton(x + 68, y + 66, 64, 18,
                Component.literal(subtitleTrackLabel(selected.contentMode)), button -> {
                    if (!selected.locked && SubtitleLayout.isScrollingMode(selected.contentMode)) {
                        selected.contentMode = SubtitleLayout.toggleScrollingTrack(selected.contentMode);
                        selected.followLyrics = true;
                        init();
                    }
                }, GOLD_DIM);
            modeButton.active = !selected.locked;
            trackButton.active = !selected.locked && SubtitleLayout.isScrollingMode(selected.contentMode);
            addRenderableWidget(modeButton);
            addRenderableWidget(trackButton);
            elementTextScaleBox = addUnboundedInspectorBox(x, y + 44, 54, "字号", selected.textScale, true,
                value -> { if (!selected.locked) selected.textScale = value; });
            elementTextScaleBox.active = !selected.locked;
                elementColorBox = addColorBox(x, y + 88, 64, selected.color,
                    value -> { if (!selected.locked) selected.color = value; });
                elementTranslationColorBox = addColorBox(x + 68, y + 88, 64, selected.translationColor,
                    value -> { if (!selected.locked) selected.translationColor = value; });
                elementBackgroundColorBox = addColorBox(x, y + 110, 64, selected.backgroundColor,
                    value -> { if (!selected.locked) selected.backgroundColor = value; });
                elementMaxWidthBox = addUnboundedInspectorBox(x + 68, y + 110, 64, "宽度", selected.maxWidth,
                    false, value -> { if (!selected.locked && value >= 0.0F) selected.maxWidth = value; });
                BlackGoldButton alignmentButton = new BlackGoldButton(x, y + 132, 64, 18,
                    Component.literal(alignmentLabel(selected.alignment)), button -> {
                    if (!selected.locked) { selected.alignment = nextAlignment(selected.alignment); init(); }
                    }, GOLD_DIM);
                BlackGoldButton wrapButton = new BlackGoldButton(x + 68, y + 132, 64, 18,
                    Component.literal(selected.wrap ? "换行：开" : "换行：关"), button -> {
                    if (!selected.locked) { selected.wrap = !selected.wrap; init(); }
                    }, GOLD_DIM);
                alignmentButton.active = wrapButton.active = !selected.locked;
                elementColorBox.active = elementTranslationColorBox.active = elementBackgroundColorBox.active
                    = elementMaxWidthBox.active = !selected.locked;
                addRenderableWidget(alignmentButton);
                addRenderableWidget(wrapButton);
        } else if (selected.type == ElementType.AUDIO) {
            elementVolumeBox = addInspectorBox(x, y, 48, 54, "音量", selected.volume,
                    0.0F, 1.0F, value -> { if (!selected.locked) selected.volume = value; });
            BlackGoldButton channelButton = new BlackGoldButton(x + 62, y, 70, 18,
                Component.literal("声道：" + com.zhongbai233.net_music_can_play_bili.editor.core.media
                    .ControlConsoleMediaSettings.audioChannelLabel(selected.channelIndex)), button -> {
                    if (!selected.locked) {
                        selected.channelIndex = com.zhongbai233.net_music_can_play_bili.editor.core.media
                            .ControlConsoleMediaSettings.nextAudioChannel(selected.channelIndex);
                        init();
                    }
                }, GOLD_DIM);
            elementMaxDistanceBox = addUnboundedInspectorBox(x, y + 22, 54, "距离", selected.maxDistance, true,
                value -> { if (!selected.locked) selected.maxDistance = value; });
            elementVolumeBox.active = elementMaxDistanceBox.active = channelButton.active = !selected.locked;
            addRenderableWidget(channelButton);
            BlackGoldButton audioEnabledButton = new BlackGoldButton(x + 62, y + 22, 70, 18,
                Component.literal(selected.enabled ? "音源：开" : "音源：关"),
                button -> { if (!selected.locked) { selected.enabled = !selected.enabled; init(); } }, GOLD_DIM);
            audioEnabledButton.active = !selected.locked;
            addRenderableWidget(audioEnabledButton);
            BlackGoldButton autoMixButton = new BlackGoldButton(x, y + 44, 132, 18,
                Component.literal(selected.autoMixJoc ? "自动混合：开" : "自动混合：关"),
                button -> { if (!selected.locked) { selected.autoMixJoc = !selected.autoMixJoc; init(); } }, GOLD_DIM);
            autoMixButton.active = !selected.locked;
            addRenderableWidget(autoMixButton);
        } else {
            BlackGoldButton sourceButton = new BlackGoldButton(x, y, 64, 18,
                Component.literal("视频：绑定源"), button -> {
                    if (!selected.locked) { selected.contentMode = "SOURCE"; init(); }
                }, GOLD_DIM);
            sourceButton.active = !selected.locked;
            addRenderableWidget(sourceButton);
            BlackGoldButton screenEnabledButton = new BlackGoldButton(x + 68, y, 64, 18,
                Component.literal(selected.enabled ? "屏幕：开" : "屏幕：关"),
                button -> { if (!selected.locked) { selected.enabled = !selected.enabled; init(); } }, GOLD_DIM);
            screenEnabledButton.active = !selected.locked;
            addRenderableWidget(screenEnabledButton);
            BlackGoldButton qualityButton = new BlackGoldButton(x, y + 22, 132, 18,
                Component.literal("画质：" + com.zhongbai233.net_music_can_play_bili.editor.core.media
                    .ControlConsoleMediaSettings.videoQualityLabel(selected.channelIndex)), button -> {
                    if (!selected.locked) {
                        selected.channelIndex = com.zhongbai233.net_music_can_play_bili.editor.core.media
                            .ControlConsoleMediaSettings.nextVideoQualityIndex(selected.channelIndex);
                        init();
                    }
                }, GOLD_DIM);
            qualityButton.active = !selected.locked;
            addRenderableWidget(qualityButton);
        }
        }

        private EditBox addColorBox(int x, int y, int width, int value,
                java.util.function.IntConsumer responder) {
            EditBox box = new EditBox(font, x, y, width, 18, Component.literal("ARGB"));
            box.setValue(String.format(java.util.Locale.ROOT, "%08X", value));
            box.setResponder(text -> {
                String normalized = text.trim().replaceFirst("^(?i)#|0x", "");
                if (normalized.length() == 8) {
                    try {
                        responder.accept((int) Long.parseLong(normalized, 16));
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
            addRenderableWidget(box);
            return box;
        }

        private static String alignmentLabel(ControlConsoleElement.Alignment alignment) {
            return switch (alignment) {
                case LEFT -> "对齐：左";
                case CENTER -> "对齐：中";
                case RIGHT -> "对齐：右";
            };
        }

        private static ControlConsoleElement.Alignment nextAlignment(ControlConsoleElement.Alignment alignment) {
            return switch (alignment) {
                case LEFT -> ControlConsoleElement.Alignment.CENTER;
                case CENTER -> ControlConsoleElement.Alignment.RIGHT;
                case RIGHT -> ControlConsoleElement.Alignment.LEFT;
            };
        }

        private static String subtitleModeLabel(String mode) {
        return switch (mode) {
            case "FIXED" -> "模式：固定";
            case "SCROLL_MAIN", "SCROLL_TRANSLATION" -> "模式：滚动";
            default -> "模式：静态";
        };
        }

        private static String subtitleTrackLabel(String mode) {
        return switch (mode) {
            case "SCROLL_TRANSLATION" -> "轨道：翻译";
            case "SCROLL_MAIN" -> "轨道：主歌词";
            default -> "轨道：--";
        };
        }

        private static void cycleSubtitleMode(PreviewScreenSpec selected) {
        selected.contentMode = SubtitleLayout.nextDisplayMode(selected.contentMode);
        if ("FIXED".equals(selected.contentMode)) {
            selected.followLyrics = false;
        } else {
            selected.followLyrics = true;
        }
        }

        @SuppressWarnings("unused")
        private EditBox addConsoleIntBox(int x, int y, String label, int value,
            java.util.function.IntConsumer responder) {
        EditBox box = new EditBox(font, x, y, 54, 18, Component.literal(label));
        box.setValue(Integer.toString(value));
        box.setResponder(text -> {
            if (syncingNumericEditBoxes) {
                return;
            }
            try {
            responder.accept(Math.clamp(Integer.parseInt(text.trim()), -1, 11));
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(box);
        return box;
        }

    private void addControlConsoleDocumentWidgets() {
        if (selectedScreen >= 0) {
            return;
        }
        ControlConsoleDocument document = currentConsoleDocument();
        if (document == null) {
            return;
        }
        int panelX = width - CONTROL_RIGHT_PANEL_W;
        int x = panelX + 78;
        addConsoleTextBox(x, 62, 132, document.displayName(), text -> {
            String name = text.trim();
            if (!name.isEmpty() && name.length() <= 64) {
                updateConsoleDraft(name, consoleDraft.hardRangeX(), consoleDraft.hardRangeY(), consoleDraft.hardRangeZ());
            }
        });
        int rangeX = panelX + 12;
        addConsoleRangeBox(rangeX, 108, "X", document.hardRangeX(),
                value -> updateConsoleDraft(consoleDraft.displayName(), value, consoleDraft.hardRangeY(), consoleDraft.hardRangeZ()));
        addConsoleRangeBox(rangeX + 62, 108, "Y", document.hardRangeY(),
                value -> updateConsoleDraft(consoleDraft.displayName(), consoleDraft.hardRangeX(), value, consoleDraft.hardRangeZ()));
        addConsoleRangeBox(rangeX + 124, 108, "Z", document.hardRangeZ(),
                value -> updateConsoleDraft(consoleDraft.displayName(), consoleDraft.hardRangeX(), consoleDraft.hardRangeY(), value));
        consoleAccessModeDraft = consoleAccessModeDraft != null ? consoleAccessModeDraft : document.accessMode();
        addRenderableWidget(new BlackGoldButton(rangeX, 152, 94, 20,
                Component.literal(accessModeLabel(consoleAccessModeDraft)), button -> {
                    consoleAccessModeDraft = nextAccessMode(consoleAccessModeDraft);
                    button.setMessage(Component.literal(accessModeLabel(consoleAccessModeDraft)));
                }, GOLD));
        consoleTrustedPlayersBox = addConsoleTextBox(rangeX, 216, 202,
                document.trustedPlayerIds().stream().map(id -> id.toString()).sorted()
                        .collect(java.util.stream.Collectors.joining(",")), ignored -> {
                });
        addRenderableWidget(new BlackGoldButton(rangeX + 102, 152, 100, 20,
                Component.literal("应用权限"), button -> sendConsoleAccessUpdate(), GOLD));
        if (consoleSaveConflict) {
            addRenderableWidget(new BlackGoldButton(rangeX, 242, 132, 20,
                Component.literal("重新加载服务器版"), button -> reloadAuthoritativeConsoleDocument(), GOLD));
        }
    }

    private void sendConsoleAccessUpdate() {
        if (controlConsolePos == null || consoleDraft == null || consolePendingOperation != null
                || consoleTrustedPlayersBox == null || consoleAccessModeDraft == null) {
            return;
        }
        Set<UUID> trusted;
        try {
            trusted = parseTrustedPlayerIds(consoleTrustedPlayersBox.getValue());
        } catch (IllegalArgumentException invalid) {
            consoleSaveStatus = "可信玩家 UUID 格式无效";
            return;
        }
        UUID operationId = UUID.randomUUID();
        consoleAccessRollback = consoleDraft;
        consoleDraft = new ControlConsoleDocument(consoleDraft.schemaVersion(), consoleDraft.consoleId(), consoleDraft.revision(),
                consoleDraft.ownerId(), consoleAccessModeDraft, trusted, consoleDraft.displayName(),
                consoleDraft.sourceDimension(), consoleDraft.sourceKind(), consoleDraft.sourceX(), consoleDraft.sourceY(), consoleDraft.sourceZ(),
                consoleDraft.hardRangeX(), consoleDraft.hardRangeY(), consoleDraft.hardRangeZ(),
                consoleElementsSnapshot());
        consolePendingOperation = operationId;
        consolePendingFingerprint = consoleDraftFingerprint(consoleDraft);
        consoleSaveStatus = "正在保存权限…";
        UUID leaseId = com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient.leaseId(controlConsolePos);
        if (leaseId == null) {
            restoreAccessRollback();
            consolePendingOperation = null;
            consoleSaveStatus = "编辑租约不可用";
            return;
        }
        ClientPacketDistributor.sendToServer(new ControlConsoleAccessPacket(controlConsolePos, leaseId, operationId,
                consoleDraft.revision(), consoleAccessModeDraft, trusted));
    }

    private static Set<UUID> parseTrustedPlayerIds(String text) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (String value : text.trim().split("[,;\\s]+")) {
            if (!value.isBlank()) {
                result.add(UUID.fromString(value));
            }
        }
        if (result.size() > ControlConsoleDocument.MAX_TRUSTED_PLAYERS) {
            throw new IllegalArgumentException("too many trusted players");
        }
        return Set.copyOf(result);
    }

    private static ControlConsoleDocument.AccessMode nextAccessMode(ControlConsoleDocument.AccessMode mode) {
        return switch (mode) {
            case OWNER_ONLY -> ControlConsoleDocument.AccessMode.TRUSTED;
            case TRUSTED -> ControlConsoleDocument.AccessMode.PUBLIC_EDIT;
            case PUBLIC_EDIT -> ControlConsoleDocument.AccessMode.OWNER_ONLY;
        };
    }

    private static String accessModeLabel(ControlConsoleDocument.AccessMode mode) {
        return switch (mode) {
            case OWNER_ONLY -> "仅所有者";
            case TRUSTED -> "可信玩家";
            case PUBLIC_EDIT -> "公开编辑";
        };
    }

    private EditBox addConsoleTextBox(int x, int y, int boxWidth, String value,
            java.util.function.Consumer<String> responder) {
        EditBox box = new EditBox(font, x, y, boxWidth, 18, Component.literal("中控台名称"));
        box.setValue(value);
        box.setResponder(responder);
        addRenderableWidget(box);
        return box;
    }

    private EditBox addConsoleRangeBox(int x, int y, String axis, double value,
            java.util.function.Consumer<Double> responder) {
        EditBox box = new EditBox(font, x, y, 54, 18, Component.literal("范围" + axis));
        box.setValue(fmt((float) value));
        box.setResponder(text -> {
            if (syncingNumericEditBoxes) {
                return;
            }
            try {
                double parsed = Double.parseDouble(text.trim());
                if (Double.isFinite(parsed) && parsed > 0.0D) {
                    responder.accept(parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(box);
        return box;
    }

    private EditBox addUnboundedInspectorBox(int x, int y, int boxW, String label, float value,
            boolean positive, java.util.function.Consumer<Float> onApply) {
        EditBox box = new EditBox(font, x, y, boxW, 18, Component.literal(label));
        box.setValue(fmt(value));
        box.setResponder(text -> {
            if (syncingNumericEditBoxes) {
                return;
            }
            try {
                float parsed = Float.parseFloat(text.trim());
                if (Float.isFinite(parsed) && (!positive || parsed > 0.0F)) {
                    onApply.accept(parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(box);
        return box;
    }

    private ControlConsoleDocument currentConsoleDocument() {
        ControlConsoleDocument document = controlConsoleDocument();
        if (consoleDraft == null && document != null) {
            consoleDraft = document;
            if (!consoleElementsLoaded) {
                loadConsoleElements(document);
            }
        }
        return consoleDraft != null ? consoleDraft : document;
    }

    private void ensureConsoleDocumentLoaded() {
        currentConsoleDocument();
    }

    private void loadConsoleElements(ControlConsoleDocument document) {
        screens.clear();
        for (ControlConsoleElement element : document.elements()) {
            ElementType type = switch (element.type()) {
                case SCREEN -> ElementType.SCREEN;
                case SUBTITLE -> ElementType.SUBTITLE;
                case AUDIO -> ElementType.AUDIO;
            };
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
            screens.add(restored);
        }
        consoleElementsLoaded = true;
        selectedScreen = screens.isEmpty() ? -1 : Math.min(Math.max(selectedScreen, 0), screens.size() - 1);
    }

    private void updateConsoleDraft(String name, double rangeX, double rangeY, double rangeZ) {
        ControlConsoleDocument base = currentConsoleDocument();
        if (base == null) {
            return;
        }
        try {
                consoleDraft = new ControlConsoleDocument(base.schemaVersion(), base.consoleId(), base.revision(),
                    base.ownerId(), base.accessMode(), base.trustedPlayerIds(), name,
                    base.sourceDimension(), base.sourceKind(), base.sourceX(), base.sourceY(), base.sourceZ(), rangeX, rangeY, rangeZ,
                    base.elements());
        } catch (IllegalArgumentException ignored) {
        }
    }

    private List<ControlConsoleElement> consoleElementsSnapshot() {
        List<ControlConsoleElement> elements = new ArrayList<>(screens.size());
        for (PreviewScreenSpec screen : screens) {
            ControlConsoleElement.Type type = switch (screen.type) {
                case SCREEN -> ControlConsoleElement.Type.SCREEN;
                case SUBTITLE -> ControlConsoleElement.Type.SUBTITLE;
                case AUDIO -> ControlConsoleElement.Type.AUDIO;
            };
            elements.add(new ControlConsoleElement(screen.elementId, type, screen.name, screen.distance, screen.offsetX,
                    screen.offsetY, screen.height, screen.aspect, screen.yaw, screen.pitch, screen.roll,
                    screen.contentMode, screen.text, screen.followLyrics, screen.showTranslation,
                    screen.textScale, screen.color, screen.volume, screen.channelIndex, screen.maxDistance,
                    screen.autoMixJoc, screen.translationColor, screen.backgroundColor, screen.alignment,
                    screen.maxWidth, screen.wrap, screen.enabled, screen.locked));
        }
        return List.copyOf(elements);
    }

        private EditBox addInspectorBox(int x, int y, int labelW, int boxW, String label, float value,
            float min, float max, java.util.function.Consumer<Float> onApply) {
        EditBox box = new EditBox(font, x, y, boxW, 18, Component.literal(label));
        box.setValue(fmt(value));
        box.setResponder(text -> {
            if (syncingNumericEditBoxes) {
                return;
            }
            try {
            onApply.accept(HolographicScreenSettings.clamp(Float.parseFloat(text.trim()), min, max));
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(box);
        return box;
        }

    private void addControlConsoleWidgets() {
        int x = 8;
        int listTop = 34;
        int actionTop = Math.max(listTop + 24, height - 60);
        int visibleRows = Math.max(1, (actionTop - listTop - 4) / 24);
        ensureSelectedConsoleElementVisible(visibleRows);
        int first = Math.min(consoleElementScroll, Math.max(0, screens.size() - 1));
        int last = Math.min(screens.size(), first + visibleRows);
        int y = listTop;
        int addButtonWidth = 44;
        int addButtonGap = 2;
        for (int i = first; i < last; i++) {
            final int index = i;
            addRenderableWidget(new BlackGoldButton(x, y, CONTROL_LEFT_PANEL_W - 16, 20,
                    Component.literal((i == selectedScreen ? "◆ " : "  ") + screens.get(i).type.symbol
                        + " " + screens.get(i).name),
                    button -> {
                        selectElement(index);
                        init();
                    }, GOLD));
            y += 24;
        }
        boolean canAdd = canAddConsoleElement();
        BlackGoldButton addScreen = new BlackGoldButton(x, actionTop, addButtonWidth, 20,
                Component.literal("+ 屏幕"), button -> addConsoleElement(ElementType.SCREEN), GOLD);
        addScreen.active = canAdd;
        addRenderableWidget(addScreen);
        BlackGoldButton addSubtitle = new BlackGoldButton(x + addButtonWidth + addButtonGap, actionTop,
                addButtonWidth, 20, Component.literal("+ 字幕"),
                button -> addConsoleElement(ElementType.SUBTITLE), GOLD);
        addSubtitle.active = canAdd;
        addRenderableWidget(addSubtitle);
        BlackGoldButton addAudio = new BlackGoldButton(x + (addButtonWidth + addButtonGap) * 2, actionTop,
                addButtonWidth, 20, Component.literal("+ 音频"),
                button -> addConsoleElement(ElementType.AUDIO), GOLD);
        addAudio.active = canAdd;
        addRenderableWidget(addAudio);
        BlackGoldButton copy = new BlackGoldButton(x, actionTop + 24, 64, 20, Component.literal("复制"), button -> {
            PreviewScreenSpec selected = selectedScreenOrNull();
            if (selected == null || selected.locked || !canAddConsoleElement()) {
                return;
            }
            PreviewScreenSpec duplicate = selected.copyWithName(nextElementName(selected.type));
            screens.add(duplicate);
            selectElement(screens.size() - 1);
            init();
        }, GOLD_DIM);
        PreviewScreenSpec selectedForCopy = selectedScreenOrNull();
        copy.active = selectedForCopy != null && !selectedForCopy.locked && canAddConsoleElement();
        addRenderableWidget(copy);
    }

    private void ensureSelectedConsoleElementVisible(int visibleRows) {
        int maxScroll = Math.max(0, screens.size() - visibleRows);
        consoleElementScroll = Math.clamp(consoleElementScroll, 0, maxScroll);
        if (selectedScreen >= 0 && selectedScreen < consoleElementScroll) {
            consoleElementScroll = selectedScreen;
        } else if (selectedScreen >= consoleElementScroll + visibleRows) {
            consoleElementScroll = Math.min(maxScroll, selectedScreen - visibleRows + 1);
        }
    }

    private void addConsoleElement(ElementType type) {
        if (!canAddConsoleElement()) {
            return;
        }
        screens.add(PreviewScreenSpec.defaultsWithName(type, nextElementName(type)));
        selectElement(screens.size() - 1);
        init();
    }

    private boolean canAddConsoleElement() {
        if (!controlConsoleMode) {
            return true;
        }
        if (screens.size() >= ControlConsoleDocument.MAX_ELEMENTS) {
            return false;
        }
        return true;
    }

    private String nextElementName(ElementType type) {
        long count = screens.stream().filter(element -> element.type == type).count() + 1L;
        return type.displayName + " " + count;
    }

    private void clearNumericPanelRefs() {
        numericDistanceBox = null;
        numericOffsetXBox = null;
        numericOffsetYBox = null;
        numericHeightBox = null;
        numericAspectBox = null;
        numericRollBox = null;
        numericYawBox = null;
        numericPitchBox = null;
        elementColorBox = null;
        elementTranslationColorBox = null;
        elementBackgroundColorBox = null;
        elementMaxWidthBox = null;
        consoleTrustedPlayersBox = null;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, 0xD0000000, 0xE0050505);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (controlConsoleMode) {
            drawControlConsolePanels(g, mouseX, mouseY);
        }
        drawPreview(g, previewX(), previewY(), previewW(), previewH(), mouseX, mouseY);
        drawEditorHud(g);
        if (showNumericPanel) {
            drawNumericPanel(g, mouseX, mouseY);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawControlConsolePanels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int rightX = width - CONTROL_RIGHT_PANEL_W;
        g.fillGradient(0, 0, CONTROL_LEFT_PANEL_W, height, 0xE0080A0D, 0xE0111318);
        g.fillGradient(rightX, 0, width, height, 0xE0080A0D, 0xE0111318);
        g.fillGradient(CONTROL_LEFT_PANEL_W - 1, 0, CONTROL_LEFT_PANEL_W, height, GOLD_DIM, GOLD_DIM);
        g.fillGradient(rightX, 0, rightX + 1, height, GOLD_DIM, GOLD_DIM);
        g.text(font, Component.literal("元素层级"), 10, 10, GOLD);
        g.text(font, Component.literal("场景元素"), 10, 21, TEXT_DIM);

        int x = rightX + 12;
        g.text(font, Component.literal("检查器"), x, 10, GOLD);
        ControlConsoleDocument document = currentConsoleDocument();
        PreviewScreenSpec screen = selectedScreenOrNull();
        if (screen != null) {
            g.text(font, Component.literal(BlackGoldUi.ellipsize(font,
                screen.type.displayName + " / " + screen.type.englishName, CONTROL_RIGHT_PANEL_W - 24)), x, 34,
                    TEXT_SECONDARY);
            g.text(font, Component.literal(BlackGoldUi.ellipsize(font,
                "名称：" + screen.name, CONTROL_RIGHT_PANEL_W - 24)), x, 52, TEXT_DIM);
            String typeHint = switch (screen.type) {
                case SCREEN -> "媒体画面元素";
                case SUBTITLE -> "字幕覆盖元素";
                case AUDIO -> "空间音频元素";
            };
            g.text(font, Component.literal(typeHint), x, 68, TEXT_DIM);
            g.text(font, Component.literal("位置 / 尺寸 / 三轴旋转"), x, 82, TEXT_DIM);
            String[][] labels = { { "距离", "位置X" }, { "位置Y", "高度" },
                    { "比例", "Yaw" }, { "Pitch", "Roll" } };
            for (int row = 0; row < labels.length; row++) {
                g.text(font, Component.literal(labels[row][0]), rightX + 12, 117 + row * 22, TEXT_SECONDARY);
                g.text(font, Component.literal(labels[row][1]), rightX + 122, 117 + row * 22, TEXT_SECONDARY);
            }
            g.text(font, Component.literal("元素内容"), x, 194, TEXT_DIM);
        } else if (document != null) {
            g.text(font, Component.literal("中控台 / Console"), x, 34, TEXT_SECONDARY);
            g.text(font, Component.literal("名称"), x, 67, TEXT_SECONDARY);
            g.text(font, Component.literal(document.hasSourceBinding() ? "媒体源：已绑定" : "媒体源：未绑定"),
                    x, 86, document.hasSourceBinding() ? 0xFF75D6A0 : TEXT_DIM);
            g.text(font, Component.literal("硬范围 X / Y / Z"), x, 96, TEXT_DIM);
            g.text(font, Component.literal("访问权限"), x, 138, TEXT_DIM);
            g.text(font, Component.literal("文档 Rev：" + document.revision()), x, 178, TEXT_SECONDARY);
            g.text(font, Component.literal("所有者：" + (document.ownerId() != null
                    ? document.ownerId().toString().substring(0, 8) : "未认领")), x, 190, TEXT_DIM);
            g.text(font, Component.literal("可信玩家 UUID（逗号分隔）"), x, 204, TEXT_DIM);
        }
            if (!consoleSaveStatus.isEmpty()) {
                int statusColor = consoleSaveConflict ? 0xFFFF8A75 : TEXT_SECONDARY;
                g.text(font, Component.literal(BlackGoldUi.ellipsize(font, consoleSaveStatus,
                        CONTROL_RIGHT_PANEL_W - 24)), x, consoleSaveConflict ? 268 : 242, statusColor);
            }
    }

    private void drawPreview(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY) {
        g.fillGradient(x, y, x + w, y + h, 0xFF080A0D, 0xFF111318);
        g.fillGradient(x, y, x + w, y + 1, GOLD_DIM, GOLD_DIM);
        g.fillGradient(x, y + h - 1, x + w, y + h, 0xFF2A2312, 0xFF2A2312);
        String editorTitle = controlConsolePos != null ? "中控台场景建模" : "全息屏幕编辑";
        String modeTitle = navigationMode()
            ? "视口导航：WASD移动  Space/C升降  拖动画面观察  点击元素进入建模"
                : editorTitle + "：右上角选择移动/旋转/缩放工具";
        g.text(font, Component.literal(BlackGoldUi.ellipsize(font,
                firstPersonPreview ? "第一人称预览：点击画面返回" : modeTitle, Math.max(0, w - 20))), x + 10, y + 8,
                TEXT_SECONDARY);
        if (!firstPersonPreview && controlConsolePos != null) {
            g.text(font, Component.literal(BlackGoldUi.ellipsize(font, controlConsoleSourceLabel(),
                    Math.max(0, w - 20))), x + 10, y + 17, TEXT_DIM);
        }

        int pipX = x + 1;
        int pipY = y + 26;
        int pipW = w - 2;
        int pipH = h - 54;
        CameraFrame cameraFrame = firstPersonPreview
            ? createFirstPersonCameraFrame(pipX, pipY, pipW, pipH)
            : createOrbitCameraFrame(pipX, pipY, pipW, pipH);
        SceneHit sceneHit = firstPersonPreview ? SceneHit.none() : sceneHitAt(mouseX, mouseY, cameraFrame);
        boolean playerHovered = sceneHit.type == SceneHitType.PLAYER;
        submitPipPreview(g, pipX, pipY, pipW, pipH, mouseX, mouseY, playerHovered, cameraFrame);
        if (playerHovered && !controlConsoleMode) {
            g.centeredText(font, Component.literal("点击进入第一人称"), x + w / 2, y + 31, 0xFFBFF7FF);
        }
        if (firstPersonPreview) {
            drawFirstPersonCrosshair(g, pipX, pipY, pipW, pipH);
        } else {
            drawOrientationWidget(g, pipX + pipW - ORIENTATION_WIDGET_MARGIN_RIGHT,
                    pipY + pipH - ORIENTATION_WIDGET_MARGIN_BOTTOM);
        }
    }

    private void drawEditorHud(GuiGraphicsExtractor g) {
        int buttonCount = controlConsoleMode ? 5 : 4;
        int barW = (ICON_W + ICON_GAP) * buttonCount - ICON_GAP + 6;
        int barX = width - barW - 4;
        g.fillGradient(barX, 2, barX + barW + 2, ICON_H + 6, 0xD0080A0D, 0xD0111318);
        g.outline(barX, 2, barW, ICON_H + 4, 0x8045E7FF);
        int activeIndex = activeTool.ordinal();
        int ax = barX + 2 + activeIndex * (ICON_W + ICON_GAP);
        if (!navigationMode()) {
            g.outline(ax - 1, 3, ICON_W + 2, ICON_H + 2, 0xFF45E7FF);
        }

        PreviewScreenSpec screen = selectedScreenOrNull();
        String toolTip = navigationMode() ? "漫游：WASD 移动 · Space/C 升降 · Shift 加速 · V 世界漫游"
            : switch (activeTool) {
            case MOVE -> "移动：拖动轴 · 空白处旋转视角 · 右键平移视角";
            case ROTATE -> "旋转：拖动圆环 · 空白处旋转视角 · 右键平移视角";
            case SCALE -> "缩放：拖动轴 · 空白处旋转视角 · 右键平移视角";
        };
        int hudX = controlConsoleMode ? previewX() + 10 : 10;
        int hudWidth = controlConsoleMode ? Math.max(0, previewW() - 20) : Math.max(0, width - 20);
        g.text(font, Component.literal(BlackGoldUi.ellipsize(font, toolTip, hudWidth)), hudX, height - 34,
            TEXT_SECONDARY);
        String projection = previewCamera.mode() == EditorCameraMode.ORTHOGRAPHIC ? "正交" : "透视";
        String selectionInfo = navigationMode() ? "场景漫游"
            : screen == null ? "中控台"
            : screen.type.displayName + " · " + screen.name + " · X " + fmt(screen.offsetX)
                + " · Y " + fmt(screen.offsetY) + " · 距离 " + fmt(screen.distance)
                + " · 高 " + fmt(screen.height);
        g.text(font, Component.literal(BlackGoldUi.ellipsize(font,
                projection + " · F 聚焦 · 1-6 视图 · O/P 投影  |  " + selectionInfo,
            hudWidth)), hudX, height - 18, TEXT_DIM);
    }

    private String controlConsoleSourceLabel() {
        ControlConsoleDocument document = controlConsoleDocument();
        if (document == null || !document.hasSourceBinding()) {
            return "媒体源：未绑定（拿中控台右键唱片机/直播机后再放置）";
        }
        return "媒体源：" + document.sourceDimension() + "  " + document.sourceX() + ", "
                + document.sourceY() + ", " + document.sourceZ();
    }

    @SuppressWarnings("unused")
    private String controlConsoleDocumentLabel() {
        ControlConsoleDocument document = controlConsoleDocument();
        if (document == null) {
            return "  中控台=" + controlConsolePos.toShortString() + "  文档不可用";
        }
        return "  中控台=" + controlConsolePos.toShortString() + "  Rev=" + document.revision()
                + "  硬范围=" + fmt((float) document.hardRangeX()) + "×"
                + fmt((float) document.hardRangeY()) + "×" + fmt((float) document.hardRangeZ());
    }

    private ControlConsoleDocument controlConsoleDocument() {
        if (controlConsolePos == null || minecraft == null || minecraft.level == null) {
            return null;
        }
        var blockEntity = minecraft.level.getBlockEntity(controlConsolePos);
        return blockEntity instanceof ControlConsoleBlockEntity console ? console.document() : null;
    }

    private void drawFirstPersonCrosshair(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int cx = x + w / 2;
        int cy = y + h / 2;
        int arm = 7;
        int gap = 3;
        int shadow = 0xC0000000;
        int line = 0xE8FFFFFF;
        int accent = 0xFF45E7FF;

        g.fillGradient(cx - arm - 1, cy - 1, cx - gap + 1, cy + 2, shadow, shadow);
        g.fillGradient(cx + gap - 1, cy - 1, cx + arm + 2, cy + 2, shadow, shadow);
        g.fillGradient(cx - 1, cy - arm - 1, cx + 2, cy - gap + 1, shadow, shadow);
        g.fillGradient(cx - 1, cy + gap - 1, cx + 2, cy + arm + 2, shadow, shadow);

        g.fillGradient(cx - arm, cy, cx - gap, cy + 1, line, line);
        g.fillGradient(cx + gap, cy, cx + arm + 1, cy + 1, line, line);
        g.fillGradient(cx, cy - arm, cx + 1, cy - gap, line, line);
        g.fillGradient(cx, cy + gap, cx + 1, cy + arm + 1, line, line);
        g.fillGradient(cx, cy, cx + 1, cy + 1, accent, accent);
    }

    private void drawOrientationWidget(GuiGraphicsExtractor g, int centerX, int centerY) {
        int panelRadius = ORIENTATION_WIDGET_RADIUS + 7;
        drawDisc(g, centerX, centerY, panelRadius, 0xA03A4658);
        drawDisc(g, centerX, centerY, panelRadius - 1, 0xD00B0E14);

        Matrix4f view = CameraMatrices.create(previewCamera, new EditorViewport(0, 0, 1, 1)).view();
        List<OrientationAxis> axes = new ArrayList<>(6);
        addOrientationAxis(axes, view, new Vector3f(1.0F, 0.0F, 0.0F), "X", 0xFFD34242, true);
        addOrientationAxis(axes, view, new Vector3f(-1.0F, 0.0F, 0.0F), "", 0xFFD34242, false);
        addOrientationAxis(axes, view, new Vector3f(0.0F, 1.0F, 0.0F), "Y", 0xFF3EC45B, true);
        addOrientationAxis(axes, view, new Vector3f(0.0F, -1.0F, 0.0F), "", 0xFF3EC45B, false);
        addOrientationAxis(axes, view, new Vector3f(0.0F, 0.0F, 1.0F), "Z", 0xFF3B70D4, true);
        addOrientationAxis(axes, view, new Vector3f(0.0F, 0.0F, -1.0F), "", 0xFF3B70D4, false);
        axes.sort(java.util.Comparator.comparingDouble(axis -> axis.depth()));
        for (OrientationAxis axis : axes) {
            if (axis.depth < 0.0D) {
                drawOrientationAxis(g, centerX, centerY, axis, false);
            }
        }
        drawDisc(g, centerX, centerY, 3, 0xFF252B36);
        for (OrientationAxis axis : axes) {
            if (axis.depth >= 0.0D) {
                drawOrientationAxis(g, centerX, centerY, axis, true);
            }
        }
    }

    private void drawOrientationAxis(GuiGraphicsExtractor g, int centerX, int centerY, OrientationAxis axis,
            boolean front) {
        int endX = centerX + (int) Math.round(axis.screenX * ORIENTATION_WIDGET_RADIUS);
        int endY = centerY + (int) Math.round(axis.screenY * ORIENTATION_WIDGET_RADIUS);
        int color = front ? axis.color : dimColor(axis.color);
        drawLine(g, centerX, centerY, endX, endY, dimColor(color));
        drawDisc(g, endX, endY, front ? 5 : 3, color);
        if (!axis.label.isEmpty()) {
            int labelColor = front ? 0xFF101318 : 0xFF667080;
            g.centeredText(font, Component.literal(axis.label), endX, endY - 3, labelColor);
        }
    }

    private static void addOrientationAxis(List<OrientationAxis> axes, Matrix4f view, Vector3f worldAxis,
            String label, int color, boolean positive) {
        Vector3f cameraAxis = view.transformDirection(new Vector3f(worldAxis)).normalize();
        axes.add(new OrientationAxis(cameraAxis.x, -cameraAxis.y, cameraAxis.z, label, color, positive));
    }

    private static void drawLine(GuiGraphicsExtractor g, int startX, int startY, int endX, int endY, int color) {
        int x = startX;
        int y = startY;
        int dx = Math.abs(endX - startX);
        int sx = startX < endX ? 1 : -1;
        int dy = -Math.abs(endY - startY);
        int sy = startY < endY ? 1 : -1;
        int error = dx + dy;
        while (true) {
            g.fillGradient(x, y, x + 1, y + 1, color, color);
            if (x == endX && y == endY) {
                return;
            }
            int doubled = error * 2;
            if (doubled >= dy) {
                error += dy;
                x += sx;
            }
            if (doubled <= dx) {
                error += dx;
                y += sy;
            }
        }
    }

    private static void drawDisc(GuiGraphicsExtractor g, int centerX, int centerY, int radius, int color) {
        for (int y = -radius; y <= radius; y++) {
            int halfWidth = (int) Math.floor(Math.sqrt(radius * radius - y * y));
            g.fillGradient(centerX - halfWidth, centerY + y, centerX + halfWidth + 1, centerY + y + 1,
                    color, color);
        }
    }

    private static int dimColor(int color) {
        return (color & 0xFF000000) | (((color >>> 16) & 0xFF) / 2 << 16)
                | (((color >>> 8) & 0xFF) / 2 << 8) | ((color & 0xFF) / 2);
    }

    private double orbitSensitivityScale() {
        double visibleScale;
        if (previewCamera.mode() == EditorCameraMode.ORTHOGRAPHIC) {
            double defaultOrthoScale = ORBIT_DEFAULT_CAMERA_DISTANCE
                    * Math.tan(Math.toRadians(ORBIT_FOV_DEGREES) * 0.5D);
            visibleScale = previewCamera.orthoScale() / defaultOrthoScale;
        } else {
            visibleScale = previewCamera.position().distance(previewCamera.focus())
                    / ORBIT_DEFAULT_CAMERA_DISTANCE;
        }
        double sensitivity = Math.sqrt(Math.max(MIN_CAMERA_SCALE, visibleScale));
        return Double.isFinite(sensitivity) ? sensitivity : 1.0D;
    }

    private void submitPipPreview(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY,
            boolean playerHovered, CameraFrame cameraFrame) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        var renderer = minecraft.getEntityRenderDispatcher().getRenderer(minecraft.player);
        EntityRenderState state = renderer.createRenderState(minecraft.player, 1.0F);
        state.shadowPieces.clear();
        state.outlineColor = 0;
        if (state instanceof LivingEntityRenderState living) {
            living.bodyRot = 0.0F;
            living.yRot = PLAYER_HEAD_RELATIVE_YAW;
            living.xRot = 0.0F;
            living.boundingBoxWidth /= living.scale;
            living.boundingBoxHeight /= living.scale;
            living.scale = 1.0F;
        }
        float[] distances = new float[screens.size()];
        float[] offsetXs = new float[screens.size()];
        float[] offsetYs = new float[screens.size()];
        float[] heights = new float[screens.size()];
        float[] aspects = new float[screens.size()];
        float[] yaws = new float[screens.size()];
        float[] pitches = new float[screens.size()];
        float[] rolls = new float[screens.size()];
        int[] elementTypes = new int[screens.size()];
        for (int i = 0; i < screens.size(); i++) {
            PreviewScreenSpec spec = screens.get(i);
            distances[i] = spec.distance;
            offsetXs[i] = spec.offsetX;
            offsetYs[i] = spec.offsetY;
            heights[i] = spec.height;
            aspects[i] = spec.aspect;
            yaws[i] = spec.yaw;
            pitches[i] = spec.pitch;
            rolls[i] = spec.roll;
            elementTypes[i] = spec.type.ordinal();
        }
        GuiRenderState guiState = ((GuiGraphicsExtractorAccessor) g).net_music_can_play_bili$guiRenderState();
        float fov = minecraft.options.fov().get();
        float pipScale = Math.min(w, h) * (previewScale / 200.0F);
        lastOrbitCameraFrame = cameraFrame;
        int hoveredHandle = firstPersonPreview || !selectedElementEditable() ? GizmoHandle.NONE.ordinal()
                : gizmoHandleAt(mouseX, mouseY, x, y, w, h, cameraFrame).ordinal();
        int selectedHandle = dragMode == DragMode.GIZMO ? activeHandle.ordinal() : hoveredHandle;
        ControlConsoleDocument terrainDocument = currentConsoleDocument();
        if (controlConsoleMode && minecraft.level != null && controlConsolePos != null && terrainDocument != null) {
            TerrainBounds terrainBounds = com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainHardRangeBounds
                    .around(controlConsolePos.getX(), controlConsolePos.getY(), controlConsolePos.getZ(),
                        terrainDocument.hardRangeX(), terrainDocument.hardRangeY(),
                        terrainDocument.hardRangeZ(), minecraft.level.getMinY(), minecraft.level.getMaxY());
            TerrainPreviewManager.update(minecraft.level, controlConsolePos, terrainBounds,
                terrainPreviewCenterLocal);
        }
        int gizmoType = selectedScreenOrNull() == null ? 0 : selectedScreenOrNull().type.ordinal();
        int encodedGizmoTool = activeTool.ordinal() | (gizmoType << 8);
        guiState.addPicturesInPictureState(new HolographicPreviewPipRenderState(state,
                new Vector3f(0.0F, 0.0F, 0.0F), 1.0F, DEFAULT_PREVIEW_YAW, DEFAULT_PREVIEW_PITCH,
                firstPersonPreview, fov,
                playerHovered && !controlConsoleMode, selectedScreen, distances, offsetXs, offsetYs, heights, aspects,
                yaws, pitches, rolls, elementTypes,
                encodedGizmoTool, selectedHandle,
                true, controlConsoleMode, controlConsoleMode && controlConsolePos != null,
                controlConsolePos != null ? controlConsolePos.getX() : 0,
                controlConsolePos != null ? controlConsolePos.getY() : 0,
                controlConsolePos != null ? controlConsolePos.getZ() : 0,
                terrainDocument != null ? saturatedPositiveFloat(terrainDocument.hardRangeX()) : 0.0F,
                terrainDocument != null ? saturatedPositiveFloat(terrainDocument.hardRangeY()) : 0.0F,
                terrainDocument != null ? saturatedPositiveFloat(terrainDocument.hardRangeZ()) : 0.0F,
                TerrainPreviewManager.frame(),
                cameraFrame, x, y, x + w, y + h, pipScale,
                new ScreenRectangle(x, y, w, h)));
        g.outline(x, y, w, h, 0x6045E7FF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean cancelled) {
        if (cancelled) {
            return false;
        }
        if (isCloseButton(event.x(), event.y())) {
            onClose();
            return true;
        }
        if (super.mouseClicked(event, cancelled)) {
            return true;
        }
        if ((event.button() == 0 || event.button() == 1) && inPreview(event.x(), event.y())) {
            int pipX = previewX() + 1;
            int pipY = previewY() + 26;
            int pipW = previewW() - 2;
            int pipH = previewH() - 54;
            CameraFrame cameraFrame = orbitCameraFrameFor(pipX, pipY, pipW, pipH);
            activeHandle = firstPersonPreview || event.button() == 1 ? GizmoHandle.NONE
                    : gizmoHandleAt(event.x(), event.y(), pipX, pipY, pipW, pipH, cameraFrame);
            SceneHit sceneHit = firstPersonPreview ? SceneHit.player(0.0D)
                    : sceneHitAt(event.x(), event.y(), cameraFrame);
            previewClickHitPlayer = event.button() == 0 && activeHandle == GizmoHandle.NONE
                    && sceneHit.type == SceneHitType.PLAYER && !controlConsoleMode;
            if (!firstPersonPreview && event.button() == 0 && activeHandle == GizmoHandle.NONE
                    && sceneHit.type == SceneHitType.SCREEN) {
                if (sceneHit.screenIndex >= 0) {
                    selectElement(sceneHit.screenIndex);
                    // 从无元素选中的导航状态返回建模状态时，右侧检查器的控件集合也必须重建。
                    // 仅同步数值框会让之前的中控台文档控件继续覆盖在元素控件之上。
                    init();
                }
            }
            previewDragStartedWithoutElement = controlConsoleMode && event.button() == 0
                    && activeHandle == GizmoHandle.NONE
                    && sceneHit.type == SceneHitType.NONE;
            draggingPreview = true;
            previewDragButton = event.button();
                dragMode = event.button() == 1 && !navigationMode() ? DragMode.PAN
                    : activeHandle == GizmoHandle.NONE ? DragMode.CAMERA : DragMode.GIZMO;
            if (dragMode == DragMode.GIZMO) {
                gizmoDragSession = createGizmoDragSession(event.x(), event.y(), cameraFrame, activeHandle);
                if (gizmoDragSession == null) {
                    dragMode = DragMode.CAMERA;
                    activeHandle = GizmoHandle.NONE;
                } else {
                    gizmoTransaction = new DragTransaction<>(
                            new ScreenEditState(selectedScreen, gizmoDragSession.start), "拖动全息屏幕");
                }
            }
            previewClickX = event.x();
            previewClickY = event.y();
            lastMouseX = event.x();
            lastMouseY = event.y();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingPreview && event.button() == previewDragButton) {
            double dx = event.x() - lastMouseX;
            double dy = event.y() - lastMouseY;
            if (dragMode == DragMode.CAMERA) {
                double sensitivity = orbitSensitivityScale();
                setPreviewCamera(cameraController.orbit(previewCamera,
                    Math.toRadians(dx * ORBIT_YAW_DEGREES_PER_PIXEL * sensitivity),
                    Math.toRadians(-dy * ORBIT_PITCH_DEGREES_PER_PIXEL * sensitivity), EDITOR_WORLD_UP));
            } else if (dragMode == DragMode.PAN && !firstPersonPreview) {
                setPreviewCamera(cameraController.panPixels(previewCamera, dx * PAN_SENSITIVITY,
                    dy * PAN_SENSITIVITY, currentPreviewViewport()));
            } else if (dragMode == DragMode.GIZMO) {
                applyGizmoDrag(event.x(), event.y());
                syncNumericEditBoxes();
            }
            lastMouseX = event.x();
            lastMouseY = event.y();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingPreview && event.button() == previewDragButton) {
            double dx = event.x() - previewClickX;
            double dy = event.y() - previewClickY;
            if (BlankClickSelectionPolicy.shouldDeselect(previewDragStartedWithoutElement, event.button(),
                    previewClickX, previewClickY, event.x(), event.y()) && selectedScreen >= 0) {
                enterNavigationMode();
                init();
            }
            if (event.button() == 0 && dragMode != DragMode.GIZMO && dx * dx + dy * dy < 16.0D
                    && previewClickHitPlayer) {
                firstPersonPreview = !firstPersonPreview;
                clearFlyKeys();
            }
            if (dragMode == DragMode.GIZMO && gizmoTransaction != null) {
                int index = gizmoDragSession != null ? gizmoDragSession.screenIndex : selectedScreen;
                ScreenEditState current = new ScreenEditState(index, snapshot(screens.get(index)));
                gizmoTransaction.update(ignored -> current);
                gizmoTransaction.commit(editHistory);
            }
            draggingPreview = false;
            previewDragButton = -1;
            dragMode = DragMode.NONE;
            activeHandle = GizmoHandle.NONE;
            gizmoDragSession = null;
            gizmoTransaction = null;
            previewClickHitPlayer = false;
            previewDragStartedWithoutElement = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (getFocused() instanceof EditBox || firstPersonPreview) {
            return super.keyPressed(event);
        }
        if (controlConsoleMode && event.key() == GLFW.GLFW_KEY_V) {
            startWorldRoaming();
            return true;
        }
        if (setFlyKey(event.key(), true)) {
            return true;
        }
        if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_Z) {
            ScreenEditState current = currentEditState();
            if (current != null) {
                applyEditState(editHistory.undo(current));
            }
            return true;
        }
        if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_Y) {
            ScreenEditState current = currentEditState();
            if (current != null) {
                applyEditState(editHistory.redo(current));
            }
            return true;
        }
        if (!navigationMode()) {
            StandardCameraView standardView = standardViewForKey(event.key());
            if (standardView != null) {
                setPreviewCamera(cameraController.standardView(previewCamera, standardView, EDITOR_WORLD_UP));
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_F) {
                focusSelectedScreen();
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_O) {
                switchProjection(EditorCameraMode.ORTHOGRAPHIC);
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_P) {
                switchProjection(EditorCameraMode.ORBIT);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (setFlyKey(event.key(), false)) {
            return true;
        }
        return super.keyReleased(event);
    }

    private boolean setFlyKey(int key, boolean pressed) {
        if (navigationMode()) {
            switch (key) {
                case GLFW.GLFW_KEY_W -> flyForward = pressed;
                case GLFW.GLFW_KEY_S -> flyBackward = pressed;
                case GLFW.GLFW_KEY_A -> flyLeft = pressed;
                case GLFW.GLFW_KEY_D -> flyRight = pressed;
                case GLFW.GLFW_KEY_C -> flyDown = pressed;
                case GLFW.GLFW_KEY_SPACE -> flyUp = pressed;
                case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> flyFast = pressed;
                default -> {
                    return false;
                }
            }
            return true;
        }
        switch (key) {
            case GLFW.GLFW_KEY_W -> flyUp = pressed;
            case GLFW.GLFW_KEY_S -> flyDown = pressed;
            case GLFW.GLFW_KEY_A -> flyLeft = pressed;
            case GLFW.GLFW_KEY_D -> flyRight = pressed;
            case GLFW.GLFW_KEY_Q -> flyForward = pressed;
            case GLFW.GLFW_KEY_E -> flyBackward = pressed;
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> flyFast = pressed;
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean navigationMode() {
        return controlConsoleMode && selectedScreen < 0 && !firstPersonPreview;
    }

    private void enterNavigationMode() {
        clearFlyKeys();
        modelingCamera = previewCamera;
        selectedScreen = -1;
        EditorCameraState target = navigationCamera != null ? navigationCamera : modelingCamera;
        if (target.mode() == EditorCameraMode.ORTHOGRAPHIC) {
            target = cameraController.switchProjection(target, EditorCameraMode.ORBIT);
        }
        setPreviewCamera(target);
    }

    private void selectElement(int index) {
        if (index < 0 || index >= screens.size()) {
            return;
        }
        clearFlyKeys();
        if (navigationMode()) {
            navigationCamera = previewCamera;
        }
        selectedScreen = index;
        setPreviewCamera(modelingCamera != null ? modelingCamera : previewCamera);
    }

    private void clearFlyKeys() {
        flyForward = false;
        flyBackward = false;
        flyLeft = false;
        flyRight = false;
        flyDown = false;
        flyUp = false;
        flyFast = false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (controlConsoleMode && mouseX >= 0.0D && mouseX < CONTROL_LEFT_PANEL_W && scrollY != 0.0D) {
            int listTop = 34;
            int actionTop = Math.max(listTop + 24, height - 60);
            int visibleRows = Math.max(1, (actionTop - listTop - 4) / 24);
            int maxScroll = Math.max(0, screens.size() - visibleRows);
            int previous = consoleElementScroll;
            consoleElementScroll = Math.clamp(consoleElementScroll + (scrollY < 0.0D ? 1 : -1), 0, maxScroll);
            if (consoleElementScroll != previous) {
                init();
                return true;
            }
        }
        if (inPreview(mouseX, mouseY)) {
            setPreviewCamera(cameraController.dolly(previewCamera, scrollY));
            syncLegacyPreviewScale();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void applyGizmoDrag(double mouseX, double mouseY) {
        GizmoDragSession session = gizmoDragSession;
        if (session == null || session.screenIndex < 0 || session.screenIndex >= screens.size()
            || screens.get(session.screenIndex).locked) {
            return;
        }
        PickingRay ray = EditorProjection.rayFromScreen(mouseX, mouseY, session.cameraFrame.matrices(),
                session.cameraFrame.viewport());
        Vector3d currentHit = GizmoDragMath.intersectConstraint(ray, session.origin, session.constraintVector,
                session.constraint).orElse(null);
        if (currentHit == null) {
            return;
        }
        PreviewScreenSpec screen = screens.get(session.screenIndex);
        Vector3d worldDelta = new Vector3d(currentHit).sub(session.startHit);
        double axisDelta = session.axis != null
                ? GizmoDragMath.signedAxisDelta(session.axis, session.startHit, currentHit) : 0.0D;
        switch (session.tool) {
            case MOVE -> {
                screen.offsetX = finiteOrPrevious(session.start.offsetX + (float) worldDelta.x, screen.offsetX);
                screen.offsetY = finiteOrPrevious(session.start.offsetY + (float) worldDelta.y, screen.offsetY);
                if (session.handle == GizmoHandle.Z) {
                    screen.distance = finiteOrPrevious(session.start.distance + (float) worldDelta.z,
                        screen.distance);
                }
            }
            case ROTATE -> {
                if (session.handle.isRotationRing()) {
                    float delta = GizmoDragMath.rotationDeltaDegrees(session.origin, session.axis,
                            session.startHit, currentHit);
                    switch (session.handle) {
                        case RING_X -> screen.pitch = finiteOrPrevious(session.start.pitch + delta, screen.pitch);
                        case RING_Y -> screen.yaw = finiteOrPrevious(session.start.yaw + delta, screen.yaw);
                        case RING_Z -> screen.roll = finiteOrPrevious(session.start.roll + delta, screen.roll);
                        default -> { }
                    }
                }
            }
            case SCALE -> {
                float startWidth = session.start.height * session.start.aspect;
                if (session.handle == GizmoHandle.X) {
                    float width = startWidth + (float) axisDelta;
                    screen.aspect = positiveFiniteOrPrevious(width / session.start.height, screen.aspect);
                } else if (session.handle == GizmoHandle.Y || session.handle == GizmoHandle.CENTER) {
                    double delta = session.handle == GizmoHandle.CENTER
                            ? worldDelta.dot(session.localY) : axisDelta;
                    screen.height = positiveFiniteOrPrevious(session.start.height + (float) delta, screen.height);
                    screen.aspect = positiveFiniteOrPrevious(startWidth / screen.height, screen.aspect);
                } else if (session.handle == GizmoHandle.Z) {
                    screen.height = positiveFiniteOrPrevious(
                            session.start.height + (float) (axisDelta * 0.7D), screen.height);
                }
            }
        }
    }

    private static float finiteOrPrevious(float candidate, float previous) {
        return Float.isFinite(candidate) ? candidate : previous;
    }

    private static float positiveFiniteOrPrevious(float candidate, float previous) {
        return Float.isFinite(candidate) && candidate > 0.0F ? candidate : previous;
    }

    private static float saturatedPositiveFloat(double value) {
        return value >= Float.MAX_VALUE ? Float.MAX_VALUE : (float) value;
    }

    private GizmoDragSession createGizmoDragSession(double mouseX, double mouseY, CameraFrame cameraFrame,
            GizmoHandle handle) {
        PreviewScreenSpec screen = screen();
        if (screen.locked) {
            return null;
        }
        ScreenSnapshot start = snapshot(screen);
        Vector3d origin = new Vector3d(screen.offsetX, 1.55D + screen.offsetY, screen.distance);
        Quaternionf rotation = new Quaternionf().rotateYXZ((float) Math.toRadians(screen.yaw),
            (float) Math.toRadians(screen.pitch), (float) Math.toRadians(screen.roll));
        Vector3d localX = new Vector3d(rotation.transform(new Vector3f(1.0F, 0.0F, 0.0F)));
        Vector3d localY = new Vector3d(rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F)));
        Vector3d localZ = new Vector3d(rotation.transform(new Vector3f(0.0F, 0.0F, 1.0F)));
        Vector3d axis = switch (handle) {
            case X -> localX;
            case Y -> localY;
            case Z -> localZ;
            case RING_X -> localX;
            case RING_Y -> localY;
            case RING_Z -> localZ;
            default -> null;
        };
        GizmoConstraint constraint;
        Vector3d constraintVector;
        if (handle == GizmoHandle.CENTER && activeTool != EditTool.ROTATE) {
            Matrix4f cameraWorld = cameraFrame.matrices().view().invert(new Matrix4f());
            Vector3f forward = cameraWorld.transformDirection(new Vector3f(0.0F, 0.0F, -1.0F)).normalize();
            constraint = GizmoConstraint.VIEW_PLANE;
            constraintVector = new Vector3d(forward);
        } else if (activeTool == EditTool.ROTATE) {
            if (axis == null) {
                return null;
            }
            constraint = GizmoConstraint.XY_PLANE;
            constraintVector = axis;
        } else {
            constraint = switch (handle) {
                case X -> GizmoConstraint.X_AXIS;
                case Y -> GizmoConstraint.Y_AXIS;
                case Z -> GizmoConstraint.Z_AXIS;
                default -> GizmoConstraint.VIEW_PLANE;
            };
            constraintVector = axis != null ? axis : localZ;
        }
        PickingRay startRay = EditorProjection.rayFromScreen(mouseX, mouseY, cameraFrame.matrices(),
                cameraFrame.viewport());
        Vector3d startHit = GizmoDragMath.intersectConstraint(startRay, origin, constraintVector, constraint)
                .orElse(null);
        return startHit == null ? null : new GizmoDragSession(selectedScreen, activeTool, handle, cameraFrame,
                start, origin, localY, axis, constraint, constraintVector, startHit);
    }

    private ScreenEditState currentEditState() {
        if (selectedScreen < 0) {
            return null;
        }
        return new ScreenEditState(selectedScreen, snapshot(screen()));
    }

    private void applyEditState(ScreenEditState state) {
        if (state == null || state.screenIndex < 0 || state.screenIndex >= screens.size()) {
            return;
        }
        selectElement(state.screenIndex);
        PreviewScreenSpec target = screens.get(selectedScreen);
        if (target.locked) {
            return;
        }
        ScreenSnapshot value = state.snapshot;
        target.distance = value.distance;
        target.offsetX = value.offsetX;
        target.offsetY = value.offsetY;
        target.height = value.height;
        target.aspect = value.aspect;
        target.yaw = value.yaw;
        target.pitch = value.pitch;
        target.roll = value.roll;
        syncNumericEditBoxes();
    }

    private static ScreenSnapshot snapshot(PreviewScreenSpec screen) {
        return new ScreenSnapshot(screen.distance, screen.offsetX, screen.offsetY, screen.height, screen.aspect,
            screen.yaw, screen.pitch, screen.roll);
    }

    private void presetRight() {
        PreviewScreenSpec screen = screen();
        if (screen.locked) return;
        screen.distance = HolographicScreenSettings.DEFAULT_DISTANCE;
        screen.offsetX = 0.65F;
        screen.offsetY = HolographicScreenSettings.DEFAULT_OFFSET_Y;
        screen.height = HolographicScreenSettings.DEFAULT_HEIGHT;
        screen.aspect = HolographicScreenSettings.DEFAULT_ASPECT;
        screen.roll = HolographicScreenSettings.DEFAULT_ROLL;
    }

    private void presetLeft() {
        PreviewScreenSpec screen = screen();
        if (screen.locked) return;
        screen.distance = HolographicScreenSettings.DEFAULT_DISTANCE;
        screen.offsetX = -0.65F;
        screen.offsetY = HolographicScreenSettings.DEFAULT_OFFSET_Y;
        screen.height = HolographicScreenSettings.DEFAULT_HEIGHT;
        screen.aspect = HolographicScreenSettings.DEFAULT_ASPECT;
        screen.roll = HolographicScreenSettings.DEFAULT_ROLL;
    }

    private void presetCinema() {
        PreviewScreenSpec screen = screen();
        if (screen.locked) return;
        screen.distance = 2.1F;
        screen.offsetX = 0.0F;
        screen.offsetY = 0.0F;
        screen.height = 1.8F;
        screen.aspect = HolographicScreenSettings.DEFAULT_ASPECT;
        screen.roll = HolographicScreenSettings.DEFAULT_ROLL;
    }

    private void resetDefaults() {
        PreviewScreenSpec screen = screen();
        if (screen.locked) return;
        screen.distance = HolographicScreenSettings.DEFAULT_DISTANCE;
        screen.offsetX = HolographicScreenSettings.DEFAULT_OFFSET_X;
        screen.offsetY = HolographicScreenSettings.DEFAULT_OFFSET_Y;
        screen.height = HolographicScreenSettings.DEFAULT_HEIGHT;
        screen.aspect = HolographicScreenSettings.DEFAULT_ASPECT;
        screen.roll = HolographicScreenSettings.DEFAULT_ROLL;
        firstPersonPreview = false;
        previewScale = DEFAULT_PREVIEW_SCALE;
        setPreviewCamera(legacyOrbitCamera(DEFAULT_PREVIEW_YAW, DEFAULT_PREVIEW_PITCH,
            ORBIT_DEFAULT_CAMERA_DISTANCE, new Vector3d(0.0D, ORBIT_TARGET_Y, 0.0D)));
    }

    private boolean inPreview(double mouseX, double mouseY) {
        int x = previewX();
        int y = previewY();
        return mouseX >= x && mouseX <= x + previewW() && mouseY >= y && mouseY <= y + previewH();
    }

    @SuppressWarnings("unused")
    private GizmoProjection gizmoProjection(int x, int y, int w, int h) {
        return gizmoProjection(x, y, w, h, orbitCameraFrameFor(x, y, w, h));
    }

    private GizmoProjection gizmoProjection(int x, int y, int w, int h, CameraFrame cameraFrame) {
        PreviewScreenSpec screen = selectedScreenOrNull();
        if (screen == null) {
            GizmoPoint hidden = new GizmoPoint(0.0D, 0.0D, 0.0D, false);
                return new GizmoProjection(hidden, hidden, hidden, hidden, new GizmoPoint[0],
                    new GizmoPoint[0], new GizmoPoint[0]);
        }
        double centerX = screen.offsetX;
        double centerY = 1.55D + screen.offsetY;
        double centerZ = screen.distance;
        Quaternionf rotation = screenRotation(screen);
        Vector3f xDirection = rotation.transform(new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f yDirection = rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F));
        Vector3f zDirection = rotation.transform(new Vector3f(0.0F, 0.0F, 1.0F));

        GizmoPoint center = projectGizmoPoint(centerX, centerY, centerZ, cameraFrame);
        GizmoPoint xAxis = projectGizmoPoint(centerX + xDirection.x * GIZMO_AXIS_WORLD_LEN,
            centerY + xDirection.y * GIZMO_AXIS_WORLD_LEN, centerZ + xDirection.z * GIZMO_AXIS_WORLD_LEN,
            cameraFrame);
        GizmoPoint yAxis = projectGizmoPoint(centerX + yDirection.x * GIZMO_AXIS_WORLD_LEN,
            centerY + yDirection.y * GIZMO_AXIS_WORLD_LEN, centerZ + yDirection.z * GIZMO_AXIS_WORLD_LEN,
            cameraFrame);
        GizmoPoint zAxis = projectGizmoPoint(centerX + zDirection.x * GIZMO_AXIS_WORLD_LEN,
            centerY + zDirection.y * GIZMO_AXIS_WORLD_LEN, centerZ + zDirection.z * GIZMO_AXIS_WORLD_LEN,
            cameraFrame);
        GizmoPoint[] ringX = projectGizmoRing(centerX, centerY, centerZ, rotation, cameraFrame, 0);
        GizmoPoint[] ringY = projectGizmoRing(centerX, centerY, centerZ, rotation, cameraFrame, 1);
        GizmoPoint[] ringZ = projectGizmoRing(centerX, centerY, centerZ, rotation, cameraFrame, 2);
        return new GizmoProjection(center, xAxis, yAxis, zAxis, ringX, ringY, ringZ);
    }

    private GizmoPoint[] projectGizmoRing(double centerX, double centerY, double centerZ, Quaternionf rotation,
            CameraFrame cameraFrame, int axis) {
        GizmoPoint[] ring = new GizmoPoint[GIZMO_RING_SEGMENTS];
        double radius = GIZMO_AXIS_WORLD_LEN * 0.66D;
        for (int i = 0; i < ring.length; i++) {
            double angle = Math.PI * 2.0D * i / ring.length;
            float a = (float) (Math.cos(angle) * radius);
            float b = (float) (Math.sin(angle) * radius);
            Vector3f local = switch (axis) {
                case 0 -> new Vector3f(0.0F, a, b);
                case 1 -> new Vector3f(a, 0.0F, b);
                default -> new Vector3f(a, b, 0.0F);
            };
            Vector3f point = rotation.transform(local);
            ring[i] = projectGizmoPoint(centerX + point.x, centerY + point.y, centerZ + point.z, cameraFrame);
        }
        return ring;
    }

    private GizmoPoint projectGizmoPoint(double worldX, double worldY, double worldZ, CameraFrame cameraFrame) {
        ProjectedPoint point = EditorProjection.project(new Vector3d(worldX, worldY, worldZ),
                cameraFrame.matrices(), cameraFrame.viewport());
        return new GizmoPoint(point.screenX(), point.screenY(), point.depth(), point.visible());
    }

    private SceneHit sceneHitAt(double mouseX, double mouseY, CameraFrame cameraFrame) {
        PickingRay ray;
        try {
            ray = EditorProjection.rayFromScreen(mouseX, mouseY, cameraFrame.matrices(),
                    cameraFrame.viewport());
        } catch (IllegalArgumentException | IllegalStateException invalidCameraMatrix) {
            return SceneHit.none();
        }
        var playerIntersection = ray.intersectAabb(PREVIEW_PLAYER_BOUNDS_MIN, PREVIEW_PLAYER_BOUNDS_MAX);
        SceneHit nearest = playerIntersection.isPresent()
            ? SceneHit.player(playerIntersection.orElseThrow()) : SceneHit.none();
        double nearestDistance = nearest.distance;
        for (int i = 0; i < screens.size(); i++) {
            PreviewScreenSpec screen = screens.get(i);
            Vector3d center = new Vector3d(screen.offsetX, 1.55D + screen.offsetY, screen.distance);
            Quaternionf rotation = screenRotation(screen);
            Vector3d xAxis = new Vector3d(rotation.transform(new Vector3f(1.0F, 0.0F, 0.0F)));
            Vector3d yAxis = new Vector3d(rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F)));
            double halfHeight = screen.height * 0.5D;
            var intersection = ray.intersectRectangle(center, xAxis, yAxis,
                    halfHeight * screen.aspect, halfHeight);
            if (intersection.isPresent()) {
                double distance = intersection.orElseThrow().distance();
                // 同深度时元素优先，避免贴着玩家表面的屏幕被“进入第一人称”抢走点击。
                if (distance <= nearestDistance + 1.0e-7D) {
                    nearest = SceneHit.screen(i, distance);
                    nearestDistance = distance;
                }
            }
        }
        return nearest;
    }

    private static Quaternionf screenRotation(PreviewScreenSpec screen) {
        return new Quaternionf().rotateYXZ((float) Math.toRadians(screen.yaw),
                (float) Math.toRadians(screen.pitch), (float) Math.toRadians(screen.roll));
    }

    private GizmoHandle gizmoHandleAt(double mouseX, double mouseY, int x, int y, int w, int h,
            CameraFrame cameraFrame) {
        if (!selectedElementEditable()) {
            return GizmoHandle.NONE;
        }
        return gizmoHandleAt(mouseX, mouseY, gizmoProjection(x, y, w, h, cameraFrame));
    }

    private boolean selectedElementEditable() {
        PreviewScreenSpec selected = selectedScreenOrNull();
        return selected != null && !selected.locked;
    }

    private GizmoHandle gizmoHandleAt(double mouseX, double mouseY, GizmoProjection gizmo) {
        GizmoPoint center = gizmo.center;
        if (!center.visible) {
            return GizmoHandle.NONE;
        }
        double cx = center.x;
        double cy = center.y;
        if (activeTool == EditTool.ROTATE) {
            if (hitsRing(mouseX, mouseY, gizmo.ringX)) return GizmoHandle.RING_X;
            if (hitsRing(mouseX, mouseY, gizmo.ringY)) return GizmoHandle.RING_Y;
            if (hitsRing(mouseX, mouseY, gizmo.ringZ)) return GizmoHandle.RING_Z;
        }
        if (Math.hypot(mouseX - cx, mouseY - cy) <= GIZMO_HIT_RADIUS) {
            return GizmoHandle.CENTER;
        }
        if (gizmo.xAxis.visible
                && distanceToSegment(mouseX, mouseY, cx, cy, gizmo.xAxis.x, gizmo.xAxis.y) <= GIZMO_HIT_RADIUS) {
            return GizmoHandle.X;
        }
        if (gizmo.yAxis.visible
                && distanceToSegment(mouseX, mouseY, cx, cy, gizmo.yAxis.x, gizmo.yAxis.y) <= GIZMO_HIT_RADIUS) {
            return GizmoHandle.Y;
        }
        if (gizmo.zAxis.visible
                && distanceToSegment(mouseX, mouseY, cx, cy, gizmo.zAxis.x, gizmo.zAxis.y) <= GIZMO_HIT_RADIUS) {
            return GizmoHandle.Z;
        }
        return GizmoHandle.NONE;
    }

    private static boolean hitsRing(double mouseX, double mouseY, GizmoPoint[] ring) {
        if (ring.length == 0) {
            return false;
        }
        for (int i = 1; i < ring.length; i++) {
            if (ring[i - 1].visible && ring[i].visible
                    && distanceToSegment(mouseX, mouseY, ring[i - 1].x, ring[i - 1].y,
                    ring[i].x, ring[i].y) <= GIZMO_HIT_RADIUS) {
                return true;
            }
        }
        GizmoPoint first = ring[0];
        GizmoPoint last = ring[ring.length - 1];
        return last.visible && first.visible
                && distanceToSegment(mouseX, mouseY, last.x, last.y, first.x, first.y) <= GIZMO_HIT_RADIUS;
    }

    private static double distanceToSegment(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lenSq = dx * dx + dy * dy;
        if (lenSq <= 0.0001D) {
            return Math.hypot(px - x1, py - y1);
        }
        double t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
        t = Math.max(0.0D, Math.min(1.0D, t));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    private boolean isCloseButton(double mouseX, double mouseY) {
        int x = width - 8 - ICON_W;
        return mouseX >= x && mouseX <= x + ICON_W && mouseY >= 4 && mouseY <= 4 + ICON_H;
    }

    @SuppressWarnings("unused")
    private void toggleNumericPanel() {
        showNumericPanel = !showNumericPanel;
        init();
    }

    private void addNumericPanelWidgets() {
        PreviewScreenSpec screen = screen();
        int px = numericPanelX();
        int py = numericPanelY();
        int lblW = 28;
        int boxW = 38;
        int rstW = 14;
        int rowH = 16;
        int cellW = lblW + boxW + rstW + 4; // ~84
        int row1y = py + 20;
        int row2y = row1y + rowH + 4;

        numericDistanceBox = addNumericRow(px + 6, row1y, lblW, boxW, rstW, rowH, "前后",
                screen.distance, HolographicScreenSettings.MIN_DISTANCE, HolographicScreenSettings.MAX_DISTANCE,
                v -> screen.distance = v, HolographicScreenSettings.DEFAULT_DISTANCE);
        numericOffsetXBox = addNumericRow(px + 6 + cellW, row1y, lblW, boxW, rstW, rowH, "左右",
                screen.offsetX, HolographicScreenSettings.MIN_OFFSET_X, HolographicScreenSettings.MAX_OFFSET_X,
                v -> screen.offsetX = v, HolographicScreenSettings.DEFAULT_OFFSET_X);
        numericOffsetYBox = addNumericRow(px + 6 + cellW * 2, row1y, lblW, boxW, rstW, rowH, "上下",
                screen.offsetY, HolographicScreenSettings.MIN_OFFSET_Y, HolographicScreenSettings.MAX_OFFSET_Y,
                v -> screen.offsetY = v, HolographicScreenSettings.DEFAULT_OFFSET_Y);

        int pbtnW = 30;
        int pbtnH = rowH;
        int pbtnGap = 3;
        int presetX1 = px + 6 + cellW * 3 + 6;
        addRenderableWidget(new BlackGoldButton(presetX1, row1y, pbtnW, pbtnH,
                Component.literal("右窗"), btn -> {
                    presetRight();
                    syncNumericEditBoxes();
                }, GOLD));
        addRenderableWidget(new BlackGoldButton(presetX1 + pbtnW + pbtnGap, row1y, pbtnW, pbtnH,
                Component.literal("左窗"), btn -> {
                    presetLeft();
                    syncNumericEditBoxes();
                }, GOLD));
        addRenderableWidget(new BlackGoldButton(presetX1 + (pbtnW + pbtnGap) * 2, row1y, pbtnW, pbtnH,
                Component.literal("影院"), btn -> {
                    presetCinema();
                    syncNumericEditBoxes();
                }, GOLD));

        numericHeightBox = addNumericRow(px + 6, row2y, lblW, boxW, rstW, rowH, "高度",
                screen.height, HolographicScreenSettings.MIN_HEIGHT, HolographicScreenSettings.MAX_HEIGHT,
                v -> screen.height = v, HolographicScreenSettings.DEFAULT_HEIGHT);
        numericAspectBox = addNumericRow(px + 6 + cellW, row2y, lblW, boxW, rstW, rowH, "比例",
                screen.aspect, HolographicScreenSettings.MIN_ASPECT, HolographicScreenSettings.MAX_ASPECT,
                v -> screen.aspect = v, HolographicScreenSettings.DEFAULT_ASPECT);
        numericRollBox = addNumericRow(px + 6 + cellW * 2, row2y, lblW, boxW, rstW, rowH, "倾角",
                screen.roll, HolographicScreenSettings.MIN_ROLL, HolographicScreenSettings.MAX_ROLL,
                v -> screen.roll = v, HolographicScreenSettings.DEFAULT_ROLL);

        addRenderableWidget(new BlackGoldButton(presetX1, row2y, pbtnW, pbtnH,
                Component.literal("正面"), btn -> {
                    setPreviewCamera(cameraController.standardView(previewCamera, StandardCameraView.FRONT,
                        EDITOR_WORLD_UP));
                }, GOLD));
        addRenderableWidget(new BlackGoldButton(presetX1 + pbtnW + pbtnGap, row2y, pbtnW, pbtnH,
                Component.literal("侧面"), btn -> {
                    setPreviewCamera(cameraController.standardView(previewCamera, StandardCameraView.LEFT,
                        EDITOR_WORLD_UP));
                }, GOLD));
        addRenderableWidget(new BlackGoldButton(presetX1 + (pbtnW + pbtnGap) * 2, row2y, pbtnW, pbtnH,
                Component.literal("重置"), btn -> {
                    resetDefaults();
                    syncNumericEditBoxes();
                }, GOLD));
    }

    private EditBox addNumericRow(int px, int y, int labelW, int boxW, int rstW, int rowH, String label,
            float value, float min, float max, java.util.function.Consumer<Float> onApply, float defaultVal) {
        EditBox box = new EditBox(font, px + labelW + 2, y, boxW, rowH, Component.literal(label));
        box.setValue(fmt(value));
        box.setResponder(text -> {
            if (syncingNumericEditBoxes) {
                return;
            }
            try {
                float parsed = Float.parseFloat(text.trim());
                if (controlConsoleMode) {
                    boolean positive = "高度".equals(label) || "比例".equals(label);
                    if (Float.isFinite(parsed) && (!positive || parsed > 0.0F)) {
                        onApply.accept(parsed);
                    }
                } else {
                    onApply.accept(HolographicScreenSettings.clamp(parsed, min, max));
                }
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(box);
        int rstX = px + labelW + boxW + 6;
        addRenderableWidget(new BlackGoldButton(rstX, y, rstW, rowH,
                Component.literal("\u21BA"), btn -> {
                    onApply.accept(defaultVal);
                    box.setValue(fmt(defaultVal));
                }, GOLD));
        return box;
    }

    private void syncNumericEditBoxes() {
        PreviewScreenSpec screen = screen();
        syncingNumericEditBoxes = true;
        try {
            if (numericDistanceBox != null)
                numericDistanceBox.setValue(fmt(screen.distance));
            if (numericOffsetXBox != null)
                numericOffsetXBox.setValue(fmt(screen.offsetX));
            if (numericOffsetYBox != null)
                numericOffsetYBox.setValue(fmt(screen.offsetY));
            if (numericHeightBox != null)
                numericHeightBox.setValue(fmt(screen.height));
            if (numericAspectBox != null)
                numericAspectBox.setValue(fmt(screen.aspect));
            if (numericYawBox != null)
                numericYawBox.setValue(fmt(screen.yaw));
            if (numericPitchBox != null)
                numericPitchBox.setValue(fmt(screen.pitch));
            if (numericRollBox != null)
                numericRollBox.setValue(fmt(screen.roll));
        } finally {
            syncingNumericEditBoxes = false;
        }
    }

    private int numericPanelX() {
        return 8;
    }

    private int numericPanelY() {
        return 24;
    }

    private int numericPanelH() {
        return showNumericPanel ? 60 : 0;
    }

    private void drawNumericPanel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int px = numericPanelX();
        int py = numericPanelY();
        int pw = width - 16;
        int ph = numericPanelH();
        g.fillGradient(px - 2, py - 2, px + pw + 2, py + ph + 2, 0x30D4A843, 0x30D4A843);
        g.fillGradient(px, py, px + pw, py + ph, 0xE0080A0D, 0xE0111318);
        g.fillGradient(px, py, px + pw, py + 18, BG_HEADER, BG_HEADER);
        g.fillGradient(px + 6, py + 17, px + pw - 6, py + 18, GOLD_DIM, GOLD_DIM);
        g.text(font, Component.literal("屏幕属性"), px + 8, py + 5, GOLD);
        g.text(font, Component.literal("数值 / 预设"), px + pw - 68, py + 5, TEXT_DIM);

        if (!showNumericPanel)
            return;
        int lblW = 28;
        int boxW = 38;
        int rstW = 14;
        int cellW = lblW + boxW + rstW + 4;
        int row1y = py + 20;
        int row2y = row1y + 20;
        String[] labels1 = { "前后", "左右", "上下" };
        String[] labels2 = { "高度", "比例", "倾角" };
        for (int i = 0; i < 3; i++) {
            g.text(font, Component.literal(labels1[i]), px + 6 + cellW * i, row1y + 5, TEXT_SECONDARY);
            g.text(font, Component.literal(labels2[i]), px + 6 + cellW * i, row2y + 5, TEXT_SECONDARY);
        }
        int presetX1 = px + 6 + cellW * 3 + 6;
        g.text(font, Component.literal("预设"), presetX1, row1y - 3, TEXT_DIM);
    }

    private int previewX() {
        return controlConsoleMode ? CONTROL_LEFT_PANEL_W + CONTROL_PANEL_GAP : 0;
    }

    private int previewY() {
        return showNumericPanel ? numericPanelY() + numericPanelH() + 4 : 0;
    }

    private int previewW() {
        return controlConsoleMode
            ? Math.max(1, width - CONTROL_LEFT_PANEL_W - CONTROL_RIGHT_PANEL_W - CONTROL_PANEL_GAP * 2)
            : width;
    }

    private int previewH() {
        return Math.max(1, height - previewY());
    }

    private CameraFrame orbitCameraFrameFor(int x, int y, int w, int h) {
        EditorViewport viewport = new EditorViewport(x, y, Math.max(1, w), Math.max(1, h));
        if (lastOrbitCameraFrame != null && lastOrbitCameraFrame.viewport().equals(viewport)) {
            return lastOrbitCameraFrame;
        }
        return createOrbitCameraFrame(x, y, w, h);
    }

    private CameraFrame createOrbitCameraFrame(int x, int y, int w, int h) {
        EditorViewport viewport = new EditorViewport(x, y, Math.max(1, w), Math.max(1, h));
        return new CameraFrame(CameraMatrices.create(previewCamera, viewport), viewport, previewCamera.mode());
    }

        private CameraFrame createFirstPersonCameraFrame(int x, int y, int w, int h) {
        EditorViewport viewport = new EditorViewport(x, y, Math.max(1, w), Math.max(1, h));
        float fov = Minecraft.getInstance().options.fov().get();
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(fov),
            (float) viewport.aspectRatio(), 0.05F, 100.0F);
        Matrix4f view = new Matrix4f().translate(0.0F, 0.0F, -0.001F)
            .scale(1.0F, -1.0F, -1.0F)
            .translate(0.0F, -1.62F, 0.0F);
        return new CameraFrame(CameraMatrices.from(view, projection), viewport, EditorCameraMode.FIRST_PERSON);
        }

    private void setPreviewCamera(EditorCameraState camera) {
        previewCamera = java.util.Objects.requireNonNull(camera, "camera");
        if (navigationMode()) {
            navigationCamera = previewCamera;
        } else {
            modelingCamera = previewCamera;
        }
        lastOrbitCameraFrame = null;
    }

    private void focusSelectedScreen() {
        PreviewScreenSpec selected = selectedScreenOrNull();
        if (selected == null) {
            setPreviewCamera(cameraController.focus(previewCamera, new Vector3d(0.0D, 0.5D, 0.0D), 1.0D,
                    currentPreviewViewport(), EDITOR_WORLD_UP));
            return;
        }
        double halfHeight = selected.height * 0.5D;
        double halfWidth = halfHeight * selected.aspect;
        double radius = Math.hypot(halfWidth, halfHeight);
        setPreviewCamera(cameraController.focus(previewCamera,
                new Vector3d(selected.offsetX, 1.55D + selected.offsetY, selected.distance), radius,
                currentPreviewViewport(), EDITOR_WORLD_UP));
        syncLegacyPreviewScale();
    }

    /** 直接进入中控台建模时的默认目标点，位于中控台局部中心。 */
    private void focusControlConsoleCenter() {
        setPreviewCamera(cameraController.focus(previewCamera, new Vector3d(0.0D, 0.5D, 0.0D),
                1.0D, currentPreviewViewport(), EDITOR_WORLD_UP));
        syncLegacyPreviewScale();
    }

    private void switchProjection(EditorCameraMode targetMode) {
        setPreviewCamera(cameraController.switchProjection(previewCamera, targetMode));
        syncLegacyPreviewScale();
    }

    private static StandardCameraView standardViewForKey(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_KP_1 -> StandardCameraView.FRONT;
            case GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_KP_2 -> StandardCameraView.BACK;
            case GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_KP_3 -> StandardCameraView.LEFT;
            case GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_KP_4 -> StandardCameraView.RIGHT;
            case GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_KP_5 -> StandardCameraView.TOP;
            case GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_KP_6 -> StandardCameraView.BOTTOM;
            default -> null;
        };
    }

    private EditorViewport currentPreviewViewport() {
        return new EditorViewport(previewX() + 1, previewY() + 26, Math.max(1, previewW() - 2),
                Math.max(1, previewH() - 54));
    }

    private void syncLegacyPreviewScale() {
        double distance = Math.max(MIN_CAMERA_SCALE, previewCamera.position().distance(previewCamera.focus()));
        double scale = ORBIT_DEFAULT_CAMERA_DISTANCE * DEFAULT_PREVIEW_SCALE / distance;
        previewScale = (float) Math.min(Float.MAX_VALUE, Math.max(Float.MIN_NORMAL, scale));
    }

    private static EditorCameraState legacyOrbitCamera(double yawDegrees, double pitchDegrees, double distance,
            Vector3d focus) {
        Matrix4f view = new Matrix4f()
                .translate(0.0F, 0.0F, (float) -distance)
                .rotateX((float) Math.toRadians(pitchDegrees))
                .rotateY((float) Math.toRadians(yawDegrees))
                .translate((float) -focus.x, (float) -focus.y, (float) -focus.z);
        Matrix4f cameraWorld = view.invert(new Matrix4f());
        Vector3f position = cameraWorld.getTranslation(new Vector3f());
        Quaternionf orientation = cameraWorld.getNormalizedRotation(new Quaternionf());
        return new EditorCameraState(EditorCameraMode.ORBIT, new Vector3d(position), orientation, focus,
            (float) ORBIT_FOV_DEGREES, 4.0F, 0.05F, EDITOR_FAR_PLANE);
    }

    private static String fmt(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001F) {
            return Integer.toString(Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private record GizmoPoint(double x, double y, double depth, boolean visible) {
    }

        private record GizmoProjection(GizmoPoint center, GizmoPoint xAxis, GizmoPoint yAxis, GizmoPoint zAxis,
            GizmoPoint[] ringX, GizmoPoint[] ringY, GizmoPoint[] ringZ) {
    }

        private record ScreenSnapshot(float distance, float offsetX, float offsetY, float height, float aspect,
            float yaw, float pitch, float roll) {
    }

        private record ScreenEditState(int screenIndex, ScreenSnapshot snapshot) {
        }

            private record OrientationAxis(double screenX, double screenY, double depth, String label, int color,
                boolean positive) {
            }

            private record SceneHit(SceneHitType type, int screenIndex, double distance) {
                private static SceneHit none() {
                    return new SceneHit(SceneHitType.NONE, -1, Double.POSITIVE_INFINITY);
                }

                private static SceneHit player(double distance) {
                    return new SceneHit(SceneHitType.PLAYER, -1, distance);
                }

                private static SceneHit screen(int screenIndex, double distance) {
                    return new SceneHit(SceneHitType.SCREEN, screenIndex, distance);
                }
            }

    private record GizmoDragSession(int screenIndex, EditTool tool, GizmoHandle handle, CameraFrame cameraFrame,
            ScreenSnapshot start, Vector3d origin, Vector3d localY, Vector3d axis,
            GizmoConstraint constraint, Vector3d constraintVector, Vector3d startHit) {
        private GizmoDragSession {
            origin = new Vector3d(origin);
            localY = new Vector3d(localY);
            axis = axis != null ? new Vector3d(axis) : null;
            constraintVector = new Vector3d(constraintVector);
            startHit = new Vector3d(startHit);
        }
    }

    private PreviewScreenSpec screen() {
        if (screens.isEmpty()) {
            screens.add(PreviewScreenSpec.defaults());
        }
        selectedScreen = Math.max(0, Math.min(screens.size() - 1, selectedScreen));
        return screens.get(selectedScreen);
    }

    private PreviewScreenSpec selectedScreenOrNull() {
        return selectedScreen >= 0 && selectedScreen < screens.size() ? screens.get(selectedScreen) : null;
    }

    private enum EditTool {
        MOVE,
        ROTATE,
        SCALE
    }

    private enum DragMode {
        NONE,
        CAMERA,
        PAN,
        GIZMO
    }

    private enum GizmoHandle {
        NONE,
        CENTER,
        X,
        Y,
        Z,
        RING_X,
        RING_Y,
        RING_Z;

        private boolean isRotationRing() {
            return this == RING_X || this == RING_Y || this == RING_Z;
        }
    }

    private enum SceneHitType {
        NONE,
        PLAYER,
        SCREEN
    }

    private enum ElementType {
        SCREEN("▣", "屏幕", "Screen"),
        SUBTITLE("T", "字幕", "Subtitle"),
        AUDIO("♪", "音频", "Audio");

        private final String symbol;
        private final String displayName;
        private final String englishName;

        ElementType(String symbol, String displayName, String englishName) {
            this.symbol = symbol;
            this.displayName = displayName;
            this.englishName = englishName;
        }
    }

    private static final class PreviewScreenSpec {
        private final UUID elementId;
        private final ElementType type;
        private final String name;
        private float distance;
        private float offsetX;
        private float offsetY;
        private float height;
        private float aspect;
        private float yaw;
        private float pitch;
        private float roll;
        private String contentMode;
        private String text;
        private boolean followLyrics;
        private boolean showTranslation;
        private float textScale;
        private int color;
        private float volume;
        private int channelIndex;
        private float maxDistance;
        private boolean autoMixJoc;
        private int translationColor;
        private int backgroundColor;
        private ControlConsoleElement.Alignment alignment;
        private float maxWidth;
        private boolean wrap;
        private boolean enabled;
        private boolean locked;

        private PreviewScreenSpec(ElementType type, String name, float distance, float offsetX, float offsetY, float height,
                float aspect, float roll) {
            this(UUID.randomUUID(), type, name, distance, offsetX, offsetY, height, aspect, roll);
        }

        private PreviewScreenSpec(UUID elementId, ElementType type, String name, float distance, float offsetX,
                float offsetY, float height, float aspect, float roll) {
            this.elementId = java.util.Objects.requireNonNull(elementId, "elementId");
            this.type = type;
            this.name = name;
            this.distance = distance;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.height = height;
            this.aspect = aspect;
            this.yaw = 0.0F;
            this.pitch = 0.0F;
            this.roll = roll;
            this.contentMode = type == ElementType.SCREEN ? "SOURCE" : type == ElementType.SUBTITLE ? "LYRICS" : "SOURCE";
            this.text = "";
            this.followLyrics = type == ElementType.SUBTITLE;
            this.showTranslation = true;
            this.textScale = 1.0F;
            this.color = 0xFFFFFFFF;
            this.volume = 1.0F;
            this.channelIndex = 0;
            this.maxDistance = 32.0F;
            this.autoMixJoc = false;
            this.translationColor = ControlConsoleElement.DEFAULT_TRANSLATION_COLOR;
            this.backgroundColor = ControlConsoleElement.DEFAULT_BACKGROUND_COLOR;
            this.alignment = ControlConsoleElement.Alignment.CENTER;
            this.maxWidth = ControlConsoleElement.DEFAULT_MAX_WIDTH;
            this.wrap = false;
            this.enabled = true;
        }

        private static PreviewScreenSpec defaults() {
            return defaultsWithName(ElementType.SCREEN, "主屏幕");
        }

        @SuppressWarnings("unused")
        private static PreviewScreenSpec defaultsWithName(String name) {
            return defaultsWithName(ElementType.SCREEN, name);
        }

        private static PreviewScreenSpec defaultsWithName(ElementType type, String name) {
            HolographicGlassesItem.ScreenConfig config = HolographicGlassesItem.defaultScreenConfig();
            return new PreviewScreenSpec(type, name, config.distance(), config.offsetX(), config.offsetY(),
                    config.height(), config.aspect(), config.roll());
        }

        private PreviewScreenSpec copyWithName(String copyName) {
                    PreviewScreenSpec copy = new PreviewScreenSpec(UUID.randomUUID(), type, copyName, distance, offsetX, offsetY, height, aspect,
                    roll);
            copy.yaw = yaw;
            copy.pitch = pitch;
            copy.contentMode = contentMode;
            copy.text = text;
            copy.followLyrics = followLyrics;
            copy.showTranslation = showTranslation;
            copy.textScale = textScale;
            copy.color = color;
            copy.volume = volume;
            copy.channelIndex = channelIndex;
            copy.maxDistance = maxDistance;
            copy.autoMixJoc = autoMixJoc;
            copy.translationColor = translationColor;
            copy.backgroundColor = backgroundColor;
            copy.alignment = alignment;
            copy.maxWidth = maxWidth;
            copy.wrap = wrap;
            copy.enabled = enabled;
            copy.locked = false;
            return copy;
        }

        private static PreviewScreenSpec fromBinding(String fallbackName,
                HolographicGlassesItem.ScreenBinding binding) {
            String sourceName = binding.source() != null ? binding.source().shortName() : fallbackName;
            HolographicGlassesItem.ScreenConfig config = binding.config();
            return new PreviewScreenSpec(ElementType.SCREEN, fallbackName + " / " + sourceName, config.distance(),
                    config.offsetX(), config.offsetY(), config.height(), config.aspect(), config.roll());
        }

        private HolographicGlassesItem.ScreenConfig toConfig() {
            return new HolographicGlassesItem.ScreenConfig(distance, offsetX, offsetY, height, aspect, roll);
        }
    }
}