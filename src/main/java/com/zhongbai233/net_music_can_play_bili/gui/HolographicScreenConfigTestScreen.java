package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.joml.Vector3f;

import com.zhongbai233.net_music_can_play_bili.client.renderer.gui.HolographicPreviewPipRenderState;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.link.HolographicScreenSettings;
import com.zhongbai233.net_music_can_play_bili.mixin.GuiGraphicsExtractorAccessor;
import com.zhongbai233.net_music_can_play_bili.network.HolographicGlassesConfigPacket;

import java.util.ArrayList;
import java.util.List;

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
    private static final float MIN_PREVIEW_SCALE = 8.0F;
    private static final float MAX_PREVIEW_SCALE = 180.0F;
    private static final float PREVIEW_SCROLL_SCALE_STEP = 1.5F;
    private static final float PLAYER_HEAD_RELATIVE_YAW = 0.0F;
    private static final float DEFAULT_PREVIEW_YAW = 180.0F;
    private static final float DEFAULT_PREVIEW_PITCH = 35.0F;
    private static final double ORBIT_FOV_DEGREES = HolographicScreenSettings.ORBIT_FOV_DEGREES;
    private static final double ORBIT_DEFAULT_CAMERA_DISTANCE = HolographicScreenSettings.ORBIT_DEFAULT_CAMERA_DISTANCE;
    private static final double ORBIT_TARGET_Y = HolographicScreenSettings.ORBIT_TARGET_Y;
    private static final int ICON_W = 22;
    private static final int ICON_H = 18;
    private static final int ICON_GAP = 3;
    private static final double ROLL_DRAG_SPEED = -0.55D;
    private static final int GIZMO_HIT_RADIUS = 7;
    private static final double GIZMO_AXIS_WORLD_LEN = HolographicScreenSettings.GIZMO_AXIS_WORLD_LEN;
    private static final int GIZMO_RING_SEGMENTS = 48;

    private final List<PreviewScreenSpec> screens = new ArrayList<>(List.of(PreviewScreenSpec.defaults()));
    private int selectedScreen;
    private final boolean bindEquippedGlasses;
    private EditTool activeTool = EditTool.MOVE;
    private DragMode dragMode = DragMode.NONE;
    private GizmoHandle activeHandle = GizmoHandle.NONE;

    private float previewYaw = DEFAULT_PREVIEW_YAW;
    private float previewPitch = DEFAULT_PREVIEW_PITCH;
    private float previewScale = DEFAULT_PREVIEW_SCALE;
    private boolean draggingPreview;
    private boolean firstPersonPreview;
    private double lastMouseX;
    private double lastMouseY;
    private double previewClickX;
    private double previewClickY;

    private boolean showNumericPanel;
    private EditBox numericDistanceBox;
    private EditBox numericOffsetXBox;
    private EditBox numericOffsetYBox;
    private EditBox numericHeightBox;
    private EditBox numericAspectBox;
    private EditBox numericRollBox;

    public HolographicScreenConfigTestScreen() {
        this(false);
    }

    public HolographicScreenConfigTestScreen(boolean bindEquippedGlasses) {
        super(Component.literal(bindEquippedGlasses ? "全息眼镜配置" : "全息屏幕配置测试"));
        this.bindEquippedGlasses = bindEquippedGlasses;
        if (bindEquippedGlasses) {
            loadEquippedGlassesConfig();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        saveEquippedGlassesConfig();
        clearNumericPanelRefs();
        super.onClose();
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

        int iconY = 4;
        int startX = width - 8 - (ICON_W * 5 + ICON_GAP * 4);
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
                Component.literal("\u2026"), btn -> toggleNumericPanel(), GOLD));
        x += ICON_W + ICON_GAP;
        addRenderableWidget(new BlackGoldButton(x, iconY, ICON_W, ICON_H,
                Component.literal("\u2715"), btn -> onClose(), 0xFFD04040));

        if (showNumericPanel) {
            addNumericPanelWidgets();
        }
    }

    private void clearNumericPanelRefs() {
        numericDistanceBox = null;
        numericOffsetXBox = null;
        numericOffsetYBox = null;
        numericHeightBox = null;
        numericAspectBox = null;
        numericRollBox = null;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, 0xD0000000, 0xE0050505);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        drawPreview(g, previewX(), previewY(), previewW(), previewH(), mouseX, mouseY);
        drawEditorHud(g);
        if (showNumericPanel) {
            drawNumericPanel(g, mouseX, mouseY);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawPreview(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY) {
        g.fillGradient(x, y, x + w, y + h, 0xFF080A0D, 0xFF111318);
        g.fillGradient(x, y, x + w, y + 1, GOLD_DIM, GOLD_DIM);
        g.fillGradient(x, y + h - 1, x + w, y + h, 0xFF2A2312, 0xFF2A2312);
        g.text(font, Component.literal(firstPersonPreview ? "第一人称预览：点击画面返回" : "全息屏幕编辑：右上角选择移动/旋转/缩放工具"), x + 10, y + 8,
                TEXT_SECONDARY);

        boolean playerHovered = !firstPersonPreview && inPreviewPlayerModel(mouseX, mouseY);
        int pipX = x + 1;
        int pipY = y + 26;
        int pipW = w - 2;
        int pipH = h - 54;
        submitPipPreview(g, pipX, pipY, pipW, pipH, mouseX, mouseY, playerHovered);
        if (playerHovered) {
            g.centeredText(font, Component.literal("点击进入第一人称"), x + w / 2, y + 31, 0xFFBFF7FF);
        }
        if (firstPersonPreview) {
            drawFirstPersonCrosshair(g, pipX, pipY, pipW, pipH);
        }
    }

    private void drawEditorHud(GuiGraphicsExtractor g) {
        int barW = (ICON_W + ICON_GAP) * 5 - ICON_GAP + 6;
        int barX = width - barW - 4;
        g.fillGradient(barX, 2, barX + barW + 2, ICON_H + 6, 0xD0080A0D, 0xD0111318);
        g.outline(barX, 2, barW, ICON_H + 4, 0x8045E7FF);
        int activeIndex = activeTool.ordinal();
        int ax = barX + 2 + activeIndex * (ICON_W + ICON_GAP);
        g.outline(ax - 1, 3, ICON_W + 2, ICON_H + 2, 0xFF45E7FF);

        PreviewScreenSpec screen = screen();
        String toolTip = switch (activeTool) {
            case MOVE -> "拖拽红/绿/蓝轴：移动屏幕；拖空白：旋转 3D 视角";
            case ROTATE -> "拖拽圆环：旋转屏幕；拖空白：旋转 3D 视角";
            case SCALE -> "拖拽红轴改宽度，绿轴改高度，蓝色 S 轴等比缩放；拖空白：旋转 3D 视角";
        };
        g.text(font, Component.literal(toolTip), 10, height - 34, TEXT_SECONDARY);
        g.text(font, Component.literal("当前屏幕：" + screen.name + "  距离=" + fmt(screen.distance) + "  X="
                + fmt(screen.offsetX) + "  Y=" + fmt(screen.offsetY) + "  高=" + fmt(screen.height)
                + "  roll=" + fmt(screen.roll) + "°"), 10, height - 18, TEXT_DIM);
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

    private void submitPipPreview(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY,
            boolean playerHovered) {
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
        float[] rolls = new float[screens.size()];
        for (int i = 0; i < screens.size(); i++) {
            PreviewScreenSpec spec = screens.get(i);
            distances[i] = spec.distance;
            offsetXs[i] = spec.offsetX;
            offsetYs[i] = spec.offsetY;
            heights[i] = spec.height;
            aspects[i] = spec.aspect;
            rolls[i] = spec.roll;
        }
        GuiRenderState guiState = ((GuiGraphicsExtractorAccessor) g).net_music_can_play_bili$guiRenderState();
        float fov = minecraft.options.fov().get();
        float pipScale = Math.min(w, h) * (previewScale / 200.0F);
        int hoveredHandle = firstPersonPreview ? GizmoHandle.NONE.ordinal()
                : gizmoHandleAt(mouseX, mouseY, x, y, w, h).ordinal();
        int selectedHandle = dragMode == DragMode.GIZMO ? activeHandle.ordinal() : hoveredHandle;
        guiState.addPicturesInPictureState(new HolographicPreviewPipRenderState(state,
                new Vector3f(0.0F, 0.0F, 0.0F), 1.0F, previewYaw, previewPitch, firstPersonPreview, fov,
                playerHovered, selectedScreen, distances, offsetXs, offsetYs, heights, aspects, rolls,
                activeTool.ordinal(), selectedHandle,
                true, x, y, x + w, y + h, pipScale,
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
        if (event.button() == 0 && inPreview(event.x(), event.y())) {
            int pipX = previewX() + 1;
            int pipY = previewY() + 26;
            int pipW = previewW() - 2;
            int pipH = previewH() - 54;
            activeHandle = firstPersonPreview ? GizmoHandle.NONE
                    : gizmoHandleAt(event.x(), event.y(), pipX, pipY, pipW, pipH);
            if (!firstPersonPreview && activeHandle == GizmoHandle.NONE) {
                int hitScreen = screenAt(event.x(), event.y(), pipX, pipY, pipW, pipH);
                if (hitScreen >= 0) {
                    selectedScreen = hitScreen;
                    syncNumericEditBoxes();
                }
            }
            draggingPreview = true;
            dragMode = activeHandle == GizmoHandle.NONE ? DragMode.CAMERA : DragMode.GIZMO;
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
        if (draggingPreview && event.button() == 0) {
            double dx = event.x() - lastMouseX;
            double dy = event.y() - lastMouseY;
            if (dragMode == DragMode.CAMERA || firstPersonPreview) {
                previewYaw = wrapDegrees(previewYaw - (float) dx * 0.55F);
                previewPitch = HolographicScreenSettings.clamp(previewPitch + (float) dy * 0.45F, -65.0F, 65.0F);
            } else if (dragMode == DragMode.GIZMO) {
                applyGizmoDrag(dx, dy);
            }
            lastMouseX = event.x();
            lastMouseY = event.y();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingPreview && event.button() == 0) {
            double dx = event.x() - previewClickX;
            double dy = event.y() - previewClickY;
            if (dragMode != DragMode.GIZMO && dx * dx + dy * dy < 16.0D
                    && (firstPersonPreview || inPreviewPlayerModel(previewClickX, previewClickY))) {
                firstPersonPreview = !firstPersonPreview;
            }
            draggingPreview = false;
            dragMode = DragMode.NONE;
            activeHandle = GizmoHandle.NONE;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inPreview(mouseX, mouseY)) {
            previewScale = HolographicScreenSettings.clamp(previewScale + (float) scrollY * PREVIEW_SCROLL_SCALE_STEP,
                    MIN_PREVIEW_SCALE, MAX_PREVIEW_SCALE);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void applyGizmoDrag(double dx, double dy) {
        PreviewScreenSpec screen = screen();
        GizmoProjection gizmo = gizmoProjection(previewX() + 1, previewY() + 26, previewW() - 2, previewH() - 54);
        double worldDelta = axisWorldDelta(dx, dy, activeHandle, gizmo);
        switch (activeTool) {
            case MOVE -> {
                if (activeHandle == GizmoHandle.X || activeHandle == GizmoHandle.CENTER) {
                    double delta = activeHandle == GizmoHandle.CENTER ? dx * screenPixelsToWorld(gizmo) : worldDelta;
                    screen.offsetX = HolographicScreenSettings.clampOffsetX(screen.offsetX + (float) delta);
                }
                if (activeHandle == GizmoHandle.Y || activeHandle == GizmoHandle.CENTER) {
                    double delta = activeHandle == GizmoHandle.CENTER ? -dy * screenPixelsToWorld(gizmo) : worldDelta;
                    screen.offsetY = HolographicScreenSettings.clampOffsetY(screen.offsetY + (float) delta);
                }
                if (activeHandle == GizmoHandle.Z) {
                    screen.distance = HolographicScreenSettings.clampDistance(screen.distance + (float) worldDelta);
                }
            }
            case ROTATE -> {
                if (activeHandle == GizmoHandle.RING || activeHandle == GizmoHandle.CENTER) {
                    screen.roll = HolographicScreenSettings.clampRoll(
                            screen.roll + (float) (ringAngleDelta(gizmo, dx, dy) * ROLL_DRAG_SPEED));
                }
            }
            case SCALE -> {
                if (activeHandle == GizmoHandle.X) {
                    screen.aspect = HolographicScreenSettings.clampAspect(screen.aspect + (float) worldDelta);
                } else if (activeHandle == GizmoHandle.Y || activeHandle == GizmoHandle.CENTER) {
                    double delta = activeHandle == GizmoHandle.CENTER ? -dy * screenPixelsToWorld(gizmo) : worldDelta;
                    float oldWidth = screen.height * screen.aspect;
                    screen.height = HolographicScreenSettings.clampHeight(screen.height + (float) delta);
                    screen.aspect = HolographicScreenSettings.clampAspect(oldWidth / screen.height);
                } else if (activeHandle == GizmoHandle.Z) {
                    screen.height = HolographicScreenSettings.clampHeight(screen.height + (float) (worldDelta * 0.7D));
                }
            }
        }
    }

    private double ringAngleDelta(GizmoProjection gizmo, double dx, double dy) {
        double prevAngle = Math.atan2(lastMouseY - gizmo.center.y, lastMouseX - gizmo.center.x);
        double nextAngle = Math.atan2(lastMouseY + dy - gizmo.center.y, lastMouseX + dx - gizmo.center.x);
        double delta = Math.toDegrees(nextAngle - prevAngle);
        if (delta > 180.0D) {
            delta -= 360.0D;
        } else if (delta < -180.0D) {
            delta += 360.0D;
        }
        return delta;
    }

    private double projectedAxisDelta(double dx, double dy, GizmoHandle handle, GizmoProjection gizmo) {
        GizmoPoint target = switch (handle) {
            case X -> gizmo.xAxis;
            case Y -> gizmo.yAxis;
            case Z -> gizmo.zAxis;
            default -> null;
        };
        if (target == null) {
            return dx;
        }
        double axisX = target.x - gizmo.center.x;
        double axisY = target.y - gizmo.center.y;
        double length = Math.hypot(axisX, axisY);
        if (length <= 0.0001D) {
            return dx;
        }
        return (dx * axisX + dy * axisY) / length;
    }

    private double axisWorldDelta(double dx, double dy, GizmoHandle handle, GizmoProjection gizmo) {
        GizmoPoint target = switch (handle) {
            case X -> gizmo.xAxis;
            case Y -> gizmo.yAxis;
            case Z -> gizmo.zAxis;
            default -> null;
        };
        if (target == null) {
            return dx * screenPixelsToWorld(gizmo);
        }
        double projectedLength = Math.max(1.0D, Math.hypot(target.x - gizmo.center.x, target.y - gizmo.center.y));
        return projectedAxisDelta(dx, dy, handle, gizmo) * (GIZMO_AXIS_WORLD_LEN / projectedLength);
    }

    private double screenPixelsToWorld(GizmoProjection gizmo) {
        double xLen = Math.hypot(gizmo.xAxis.x - gizmo.center.x, gizmo.xAxis.y - gizmo.center.y);
        double yLen = Math.hypot(gizmo.yAxis.x - gizmo.center.x, gizmo.yAxis.y - gizmo.center.y);
        double avgLen = Math.max(1.0D, (xLen + yLen) * 0.5D);
        return GIZMO_AXIS_WORLD_LEN / avgLen;
    }

    private void presetRight() {
        PreviewScreenSpec screen = screen();
        screen.distance = HolographicScreenSettings.DEFAULT_DISTANCE;
        screen.offsetX = 0.65F;
        screen.offsetY = HolographicScreenSettings.DEFAULT_OFFSET_Y;
        screen.height = HolographicScreenSettings.DEFAULT_HEIGHT;
        screen.aspect = HolographicScreenSettings.DEFAULT_ASPECT;
        screen.roll = HolographicScreenSettings.DEFAULT_ROLL;
    }

    private void presetLeft() {
        PreviewScreenSpec screen = screen();
        screen.distance = HolographicScreenSettings.DEFAULT_DISTANCE;
        screen.offsetX = -0.65F;
        screen.offsetY = HolographicScreenSettings.DEFAULT_OFFSET_Y;
        screen.height = HolographicScreenSettings.DEFAULT_HEIGHT;
        screen.aspect = HolographicScreenSettings.DEFAULT_ASPECT;
        screen.roll = HolographicScreenSettings.DEFAULT_ROLL;
    }

    private void presetCinema() {
        PreviewScreenSpec screen = screen();
        screen.distance = 2.1F;
        screen.offsetX = 0.0F;
        screen.offsetY = 0.0F;
        screen.height = 1.8F;
        screen.aspect = HolographicScreenSettings.DEFAULT_ASPECT;
        screen.roll = HolographicScreenSettings.DEFAULT_ROLL;
    }

    private void resetDefaults() {
        PreviewScreenSpec screen = screen();
        screen.distance = HolographicScreenSettings.DEFAULT_DISTANCE;
        screen.offsetX = HolographicScreenSettings.DEFAULT_OFFSET_X;
        screen.offsetY = HolographicScreenSettings.DEFAULT_OFFSET_Y;
        screen.height = HolographicScreenSettings.DEFAULT_HEIGHT;
        screen.aspect = HolographicScreenSettings.DEFAULT_ASPECT;
        screen.roll = HolographicScreenSettings.DEFAULT_ROLL;
        previewYaw = DEFAULT_PREVIEW_YAW;
        previewPitch = DEFAULT_PREVIEW_PITCH;
        firstPersonPreview = false;
        previewScale = DEFAULT_PREVIEW_SCALE;
    }

    private boolean inPreview(double mouseX, double mouseY) {
        int x = previewX();
        int y = previewY();
        return mouseX >= x && mouseX <= x + previewW() && mouseY >= y && mouseY <= y + previewH();
    }

    private boolean inPreviewPlayerModel(double mouseX, double mouseY) {
        int x = previewX() + 8;
        int y = previewY() + 28;
        int w = previewW() - 16;
        int h = previewH() - 74;
        int size = Math.min(w, h);
        double cx = x + w * 0.5D;
        double feetY = y + h * 0.72D;
        double headTop = feetY - size * 0.58D;
        double headBottom = headTop + size * 0.14D;
        double bodyTop = headBottom + size * 0.02D;
        double bodyBottom = feetY - size * 0.18D;
        double legBottom = feetY + size * 0.02D;

        // 这里不要用一个覆盖整个人形的大盒子，否则鼠标靠近但还没碰到模型时，
        // PIP 玩家就会提前高亮。分成头/躯干/腿三段，贴近实际模型轮廓一点。
        return inBox(mouseX, mouseY, cx, headTop, headBottom, size * 0.055D)
                || inBox(mouseX, mouseY, cx, bodyTop, bodyBottom, size * 0.105D)
                || inBox(mouseX, mouseY, cx - size * 0.045D, bodyBottom, legBottom, size * 0.045D)
                || inBox(mouseX, mouseY, cx + size * 0.045D, bodyBottom, legBottom, size * 0.045D);
    }

    private static boolean inBox(double mouseX, double mouseY, double cx, double top, double bottom, double halfW) {
        return mouseX >= cx - halfW && mouseX <= cx + halfW && mouseY >= top && mouseY <= bottom;
    }

    private GizmoProjection gizmoProjection(int x, int y, int w, int h) {
        PreviewScreenSpec screen = screen();
        double centerX = screen.offsetX;
        double centerY = 1.55D + screen.offsetY;
        double centerZ = screen.distance;
        double roll = Math.toRadians(screen.roll);
        double cosRoll = Math.cos(roll);
        double sinRoll = Math.sin(roll);

        GizmoPoint center = projectGizmoPoint(centerX, centerY, centerZ, x, y, w, h);
        GizmoPoint xAxis = projectGizmoPoint(centerX + cosRoll * GIZMO_AXIS_WORLD_LEN,
                centerY + sinRoll * GIZMO_AXIS_WORLD_LEN, centerZ, x, y, w, h);
        GizmoPoint yAxis = projectGizmoPoint(centerX - sinRoll * GIZMO_AXIS_WORLD_LEN,
                centerY + cosRoll * GIZMO_AXIS_WORLD_LEN, centerZ, x, y, w, h);
        GizmoPoint zAxis = projectGizmoPoint(centerX, centerY, centerZ + GIZMO_AXIS_WORLD_LEN, x, y, w, h);
        GizmoPoint[] ring = new GizmoPoint[GIZMO_RING_SEGMENTS];
        double radius = GIZMO_AXIS_WORLD_LEN * 0.66D;
        for (int i = 0; i < ring.length; i++) {
            double angle = Math.PI * 2.0D * i / ring.length;
            double localX = Math.cos(angle) * radius;
            double localY = Math.sin(angle) * radius;
            double rx = localX * cosRoll - localY * sinRoll;
            double ry = localX * sinRoll + localY * cosRoll;
            ring[i] = projectGizmoPoint(centerX + rx, centerY + ry, centerZ, x, y, w, h);
        }
        return new GizmoProjection(center, xAxis, yAxis, zAxis, ring);
    }

    private GizmoPoint projectGizmoPoint(double worldX, double worldY, double worldZ, int x, int y, int w, int h) {
        int size = Math.max(1, Math.min(w, h));
        double yaw = Math.toRadians(previewYaw);
        double pitch = Math.toRadians(previewPitch);
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        // 与 HolographicPreviewPipRenderer 的环绕变换保持一致：透视相机、X 俯仰、Y 偏航，
        // 最后再做目标居中平移。
        double rx = worldX * cosYaw + worldZ * sinYaw;
        double rz = -worldX * sinYaw + worldZ * cosYaw;
        double localY = worldY - ORBIT_TARGET_Y;
        double ry = localY * Math.cos(pitch) - rz * Math.sin(pitch);
        double cameraDistance = ORBIT_DEFAULT_CAMERA_DISTANCE * DEFAULT_PREVIEW_SCALE / Math.max(1.0D, previewScale);
        double cameraZ = localY * Math.sin(pitch) + rz * Math.cos(pitch);
        double depth = cameraDistance - cameraZ;
        double focal = size * 0.5D / Math.tan(Math.toRadians(ORBIT_FOV_DEGREES) * 0.5D);
        double perspective = focal / Math.max(0.05D, depth);
        return new GizmoPoint(x + w * 0.5D + rx * perspective, y + h * 0.5D - ry * perspective, depth);
    }

    private int screenAt(double mouseX, double mouseY, int x, int y, int w, int h) {
        int hit = -1;
        double bestDepth = Double.POSITIVE_INFINITY;
        for (int i = 0; i < screens.size(); i++) {
            ScreenProjection projection = screenProjection(screens.get(i), x, y, w, h);
            if (projection != null && pointInQuad(mouseX, mouseY, projection.points())
                    && projection.depth() < bestDepth) {
                hit = i;
                bestDepth = projection.depth();
            }
        }
        return hit;
    }

    private ScreenProjection screenProjection(PreviewScreenSpec screen, int x, int y, int w, int h) {
        float halfH = screen.height * 0.5F;
        float halfW = halfH * screen.aspect;
        double centerY = 1.55D + screen.offsetY;
        double roll = Math.toRadians(screen.roll);
        double cos = Math.cos(roll);
        double sin = Math.sin(roll);
        double[][] corners = { { -halfW, -halfH }, { halfW, -halfH }, { halfW, halfH }, { -halfW, halfH } };
        GizmoPoint[] points = new GizmoPoint[corners.length];
        double depth = 0.0D;
        for (int i = 0; i < corners.length; i++) {
            double lx = corners[i][0];
            double ly = corners[i][1];
            double rx = lx * cos - ly * sin;
            double ry = lx * sin + ly * cos;
            points[i] = projectGizmoPoint(screen.offsetX + rx, centerY + ry, screen.distance, x, y, w, h);
            depth += points[i].depth;
        }
        return new ScreenProjection(points, depth / points.length);
    }

    private static boolean pointInQuad(double x, double y, GizmoPoint[] p) {
        return pointInTriangle(x, y, p[0], p[1], p[2]) || pointInTriangle(x, y, p[0], p[2], p[3]);
    }

    private static boolean pointInTriangle(double x, double y, GizmoPoint a, GizmoPoint b, GizmoPoint c) {
        double d1 = sign(x, y, a, b);
        double d2 = sign(x, y, b, c);
        double d3 = sign(x, y, c, a);
        boolean hasNeg = d1 < 0.0D || d2 < 0.0D || d3 < 0.0D;
        boolean hasPos = d1 > 0.0D || d2 > 0.0D || d3 > 0.0D;
        return !(hasNeg && hasPos);
    }

    private static double sign(double x, double y, GizmoPoint a, GizmoPoint b) {
        return (x - b.x) * (a.y - b.y) - (a.x - b.x) * (y - b.y);
    }

    private GizmoHandle gizmoHandleAt(double mouseX, double mouseY, int x, int y, int w, int h) {
        return gizmoHandleAt(mouseX, mouseY, gizmoProjection(x, y, w, h));
    }

    private GizmoHandle gizmoHandleAt(double mouseX, double mouseY, GizmoProjection gizmo) {
        GizmoPoint center = gizmo.center;
        double cx = center.x;
        double cy = center.y;
        if (activeTool == EditTool.ROTATE) {
            for (int i = 1; i < gizmo.ring.length; i++) {
                if (distanceToSegment(mouseX, mouseY, gizmo.ring[i - 1].x, gizmo.ring[i - 1].y,
                        gizmo.ring[i].x, gizmo.ring[i].y) <= GIZMO_HIT_RADIUS) {
                    return GizmoHandle.RING;
                }
            }
            GizmoPoint first = gizmo.ring[0];
            GizmoPoint last = gizmo.ring[gizmo.ring.length - 1];
            if (distanceToSegment(mouseX, mouseY, last.x, last.y, first.x, first.y) <= GIZMO_HIT_RADIUS) {
                return GizmoHandle.RING;
            }
        }
        if (Math.hypot(mouseX - cx, mouseY - cy) <= GIZMO_HIT_RADIUS) {
            return GizmoHandle.CENTER;
        }
        if (distanceToSegment(mouseX, mouseY, cx, cy, gizmo.xAxis.x, gizmo.xAxis.y) <= GIZMO_HIT_RADIUS) {
            return GizmoHandle.X;
        }
        if (distanceToSegment(mouseX, mouseY, cx, cy, gizmo.yAxis.x, gizmo.yAxis.y) <= GIZMO_HIT_RADIUS) {
            return GizmoHandle.Y;
        }
        if (distanceToSegment(mouseX, mouseY, cx, cy, gizmo.zAxis.x, gizmo.zAxis.y) <= GIZMO_HIT_RADIUS) {
            return GizmoHandle.Z;
        }
        return GizmoHandle.NONE;
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
                    previewYaw = 0.0F;
                }, GOLD));
        addRenderableWidget(new BlackGoldButton(presetX1 + pbtnW + pbtnGap, row2y, pbtnW, pbtnH,
                Component.literal("侧面"), btn -> {
                    previewYaw = 90.0F;
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
            try {
                float parsed = Float.parseFloat(text.trim());
                parsed = HolographicScreenSettings.clamp(parsed, min, max);
                onApply.accept(parsed);
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
        if (numericRollBox != null)
            numericRollBox.setValue(fmt(screen.roll));
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
        return 0;
    }

    private int previewY() {
        return showNumericPanel ? numericPanelY() + numericPanelH() + 4 : 0;
    }

    private int previewW() {
        return width;
    }

    private int previewH() {
        return Math.max(1, height - previewY());
    }

    private static float wrapDegrees(float value) {
        value %= 360.0F;
        if (value < 0.0F) {
            value += 360.0F;
        }
        return value;
    }

    private static String fmt(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001F) {
            return Integer.toString(Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private record GizmoPoint(double x, double y, double depth) {
    }

    private record ScreenProjection(GizmoPoint[] points, double depth) {
    }

    private record GizmoProjection(GizmoPoint center, GizmoPoint xAxis, GizmoPoint yAxis, GizmoPoint zAxis,
            GizmoPoint[] ring) {
    }

    private PreviewScreenSpec screen() {
        if (screens.isEmpty()) {
            screens.add(PreviewScreenSpec.defaults());
        }
        selectedScreen = Math.max(0, Math.min(screens.size() - 1, selectedScreen));
        return screens.get(selectedScreen);
    }

    private enum EditTool {
        MOVE,
        ROTATE,
        SCALE
    }

    private enum DragMode {
        NONE,
        CAMERA,
        GIZMO
    }

    private enum GizmoHandle {
        NONE,
        CENTER,
        X,
        Y,
        Z,
        RING
    }

    private static final class PreviewScreenSpec {
        private final String name;
        private float distance;
        private float offsetX;
        private float offsetY;
        private float height;
        private float aspect;
        private float roll;

        private PreviewScreenSpec(String name, float distance, float offsetX, float offsetY, float height,
                float aspect, float roll) {
            this.name = name;
            this.distance = distance;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.height = height;
            this.aspect = aspect;
            this.roll = roll;
        }

        private static PreviewScreenSpec defaults() {
            HolographicGlassesItem.ScreenConfig config = HolographicGlassesItem.defaultScreenConfig();
            return new PreviewScreenSpec("主屏幕", config.distance(), config.offsetX(), config.offsetY(),
                    config.height(), config.aspect(), config.roll());
        }

        private static PreviewScreenSpec fromBinding(String fallbackName,
                HolographicGlassesItem.ScreenBinding binding) {
            String sourceName = binding.source() != null ? binding.source().shortName() : fallbackName;
            HolographicGlassesItem.ScreenConfig config = binding.config();
            return new PreviewScreenSpec(fallbackName + " / " + sourceName, config.distance(),
                    config.offsetX(), config.offsetY(), config.height(), config.aspect(), config.roll());
        }

        private HolographicGlassesItem.ScreenConfig toConfig() {
            return new HolographicGlassesItem.ScreenConfig(distance, offsetX, offsetY, height, aspect, roll);
        }
    }
}