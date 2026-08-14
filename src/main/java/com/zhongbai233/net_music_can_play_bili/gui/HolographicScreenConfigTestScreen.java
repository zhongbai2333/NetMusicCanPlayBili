package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import com.zhongbai233.scene_editor.core.camera.CameraFrame;
import com.zhongbai233.scene_editor.core.camera.CameraMatrices;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.camera.StandardCameraView;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import com.zhongbai233.net_music_can_play_bili.link.HolographicScreenSettings;
import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;

import java.util.List;

/**
 * 全息眼镜屏幕配置界面。
 */
public class HolographicScreenConfigTestScreen extends HolographicEditorInputScreen {

    public HolographicScreenConfigTestScreen() {
        this(false, null);
    }

    public HolographicScreenConfigTestScreen(boolean bindEquippedGlasses) {
        this(bindEquippedGlasses, null);
    }

    private HolographicScreenConfigTestScreen(boolean bindEquippedGlasses, BlockPos controlConsolePos) {
        super(bindEquippedGlasses, controlConsolePos);
        if (bindEquippedGlasses) {
            loadEquippedGlassesConfig();
        }
    }

    public static HolographicScreenConfigTestScreen forControlConsole(BlockPos pos) {
        HolographicScreenConfigTestScreen screen = new HolographicScreenConfigTestScreen(false,
                java.util.Objects.requireNonNull(pos, "pos"));
        // 右键直接进入时默认不选中任何元素，并以中控台本体为建模中心。
        screen.selectedScreen = -1;
        screen.initialFocusElement = -2;
        return screen;
    }

    public static HolographicScreenConfigTestScreen forControlConsole(BlockPos pos, int selectedElement) {
        HolographicScreenConfigTestScreen screen = forControlConsole(pos);
        // 文档元素会在 init() 中加载，此处先保留请求索引，加载后再校验。
        screen.selectedScreen = Math.max(0, selectedElement);
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

    @Override
    protected void addNumericPanelWidgets() {
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
                    edit("右窗预设", this::presetRight);
                    syncNumericEditBoxes();
                }, GOLD));
        addRenderableWidget(new BlackGoldButton(presetX1 + pbtnW + pbtnGap, row1y, pbtnW, pbtnH,
                Component.literal("左窗"), btn -> {
                    edit("左窗预设", this::presetLeft);
                    syncNumericEditBoxes();
                }, GOLD));
        addRenderableWidget(new BlackGoldButton(presetX1 + (pbtnW + pbtnGap) * 2, row1y, pbtnW, pbtnH,
                Component.literal("影院"), btn -> {
                    edit("影院预设", this::presetCinema);
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
                    edit("重置元素", this::resetDefaults);
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
                        edit("设置" + label, () -> onApply.accept(parsed));
                    }
                } else {
                    float clamped = HolographicScreenSettings.clamp(parsed, min, max);
                    edit("设置" + label, () -> onApply.accept(clamped));
                }
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(box);
        int rstX = px + labelW + boxW + 6;
        addRenderableWidget(new BlackGoldButton(rstX, y, rstW, rowH,
                Component.literal("\u21BA"), btn -> {
                    edit("重置" + label, () -> onApply.accept(defaultVal));
                    box.setValue(fmt(defaultVal));
                }, GOLD));
        return box;
    }

    @Override
    protected void syncNumericEditBoxes() {
        PreviewScreenSpec screen = selectedScreenOrNull();
        if (screen == null) {
            return;
        }
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
            if (numericScaleXBox != null) numericScaleXBox.setValue(fmt(screen.scaleX));
            if (numericScaleYBox != null) numericScaleYBox.setValue(fmt(screen.scaleY));
            if (numericScaleZBox != null) numericScaleZBox.setValue(fmt(screen.scaleZ));
            if (numericPivotXBox != null) numericPivotXBox.setValue(fmt(screen.pivotX));
            if (numericPivotYBox != null) numericPivotYBox.setValue(fmt(screen.pivotY));
            if (numericPivotZBox != null) numericPivotZBox.setValue(fmt(screen.pivotZ));
            if (numericSkewXByYBox != null) numericSkewXByYBox.setValue(fmt(screen.skewXByY));
            if (numericSkewYByXBox != null) numericSkewYByXBox.setValue(fmt(screen.skewYByX));
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

    @Override
    protected void drawNumericPanel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
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

    @Override
    protected int previewX() {
        return controlConsoleMode ? CONTROL_LEFT_PANEL_W + CONTROL_PANEL_GAP : 0;
    }

    @Override
    protected int previewY() {
        return showNumericPanel ? numericPanelY() + numericPanelH() + 4 : 0;
    }

    @Override
    protected int previewW() {
        return controlConsoleMode
            ? Math.max(1, width - CONTROL_LEFT_PANEL_W - CONTROL_RIGHT_PANEL_W - CONTROL_PANEL_GAP * 2)
            : width;
    }

    @Override
    protected int previewH() {
        return Math.max(1, height - previewY());
    }

    @Override
    protected CameraFrame orbitCameraFrameFor(int x, int y, int w, int h) {
        EditorViewport viewport = new EditorViewport(x, y, Math.max(1, w), Math.max(1, h));
        if (lastOrbitCameraFrame != null && lastOrbitCameraFrame.viewport().equals(viewport)) {
            return lastOrbitCameraFrame;
        }
        return createOrbitCameraFrame(x, y, w, h);
    }

    @Override
    protected CameraFrame createOrbitCameraFrame(int x, int y, int w, int h) {
        EditorViewport viewport = new EditorViewport(x, y, Math.max(1, w), Math.max(1, h));
        return new CameraFrame(CameraMatrices.create(previewCamera, viewport), viewport, previewCamera.mode());
    }

    @Override
    protected CameraFrame createFirstPersonCameraFrame(int x, int y, int w, int h) {
        EditorViewport viewport = new EditorViewport(x, y, Math.max(1, w), Math.max(1, h));
        float fov = Minecraft.getInstance().options.fov().get();
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(fov),
            (float) viewport.aspectRatio(), 0.05F, 100.0F);
        Matrix4f view = new Matrix4f().translate(0.0F, 0.0F, -0.001F)
            .scale(1.0F, -1.0F, -1.0F)
            .translate(0.0F, -1.62F, 0.0F);
        return new CameraFrame(CameraMatrices.from(view, projection), viewport, EditorCameraMode.FIRST_PERSON);
        }

    @Override
    protected void setPreviewCamera(EditorCameraState camera) {
        previewCamera = java.util.Objects.requireNonNull(camera, "camera");
        if (navigationMode()) {
            navigationCamera = previewCamera;
        } else {
            modelingCamera = previewCamera;
        }
        lastOrbitCameraFrame = null;
    }

    @Override
    protected void focusSelectedScreen() {
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
    @Override
    protected void focusControlConsoleCenter() {
        setPreviewCamera(cameraController.focus(previewCamera, new Vector3d(0.0D, 0.5D, 0.0D),
                CONTROL_CONSOLE_INITIAL_FOCUS_RADIUS, currentPreviewViewport(), EDITOR_WORLD_UP));
        syncLegacyPreviewScale();
    }

    @Override
    protected void switchProjection(EditorCameraMode targetMode) {
        setPreviewCamera(cameraController.switchProjection(previewCamera, targetMode));
        syncLegacyPreviewScale();
    }

    @Override
    protected EditorViewport currentPreviewViewport() {
        return new EditorViewport(previewX() + 1, previewY() + 26, Math.max(1, previewW() - 2),
                Math.max(1, previewH() - 54));
    }

    @Override
    protected void syncLegacyPreviewScale() {
        double distance = Math.max(MIN_CAMERA_SCALE, previewCamera.position().distance(previewCamera.focus()));
        double scale = ORBIT_DEFAULT_CAMERA_DISTANCE * DEFAULT_PREVIEW_SCALE / distance;
        previewScale = (float) Math.min(Float.MAX_VALUE, Math.max(Float.MIN_NORMAL, scale));
    }

}
