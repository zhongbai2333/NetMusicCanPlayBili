package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.joml.Vector3f;
import org.joml.Matrix4f;

import com.zhongbai233.net_music_can_play_bili.client.renderer.gui.HolographicPreviewPipRenderState;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.scene_editor.core.camera.CameraFrame;
import com.zhongbai233.scene_editor.core.camera.CameraMatrices;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import com.zhongbai233.scene_editor.core.gizmo.GizmoCoordinateSpace;
import com.zhongbai233.net_music_can_play_bili.mixin.GuiGraphicsExtractorAccessor;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;

import java.util.ArrayList;
import java.util.List;

/** Scene preview rendering, editor HUD, orientation widget, and PIP extraction. */
abstract class HolographicEditorRenderScreen extends HolographicConsoleInspectorScreen {
    protected HolographicEditorRenderScreen(boolean bindEquippedGlasses, BlockPos controlConsolePos) {
        super(bindEquippedGlasses, controlConsolePos);
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

    protected void drawControlConsolePanels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
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
            g.text(font, Component.literal("位置 / 尺寸 / 旋转 / 高级变换"), x, 82, TEXT_DIM);
            String[][] labels = { { "距离", "位置X" }, { "位置Y", "高度" },
                    { "比例", "Yaw" }, { "Pitch", "Roll" } };
            for (int row = 0; row < labels.length; row++) {
                g.text(font, Component.literal(labels[row][0]), rightX + 12, 117 + row * 22, TEXT_SECONDARY);
                g.text(font, Component.literal(labels[row][1]), rightX + 122, 117 + row * 22, TEXT_SECONDARY);
            }
            if (showTransformInspector) {
                String[][] advancedLabels = { { "缩放X", "缩放Y" }, { "缩放Z", "枢轴X" },
                        { "枢轴Y", "枢轴Z" }, { "X←Y", "Y←X" } };
                for (int row = 0; row < advancedLabels.length; row++) {
                    g.text(font, Component.literal(advancedLabels[row][0]), rightX + 12, 211 + row * 22,
                            TEXT_SECONDARY);
                    g.text(font, Component.literal(advancedLabels[row][1]), rightX + 122, 211 + row * 22,
                            TEXT_SECONDARY);
                }
                g.text(font, Component.literal(screen.type == ElementType.AUDIO
                        ? "音源首版忽略 scale / pivot / skew" : "完整仿射变换"), x, 288, TEXT_DIM);
            } else {
                g.text(font, Component.literal("元素内容"), x, 194, TEXT_DIM);
            }
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

    protected void drawPreview(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY) {
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

    protected void drawEditorHud(GuiGraphicsExtractor g) {
        int buttonCount = controlConsoleMode ? 6 : 5;
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
            case MOVE -> "移动（" + coordinateSpaceLabel() + "）：拖动轴 · 空白处旋转视角 · 右键平移视角";
            case ROTATE -> "旋转（" + coordinateSpaceLabel() + "）：拖动圆环 · 空白处旋转视角 · 右键平移视角";
            case SCALE -> "缩放（" + coordinateSpaceLabel() + "）：拖动轴 · 空白处旋转视角 · 右键平移视角";
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

    protected String controlConsoleSourceLabel() {
        ControlConsoleDocument document = controlConsoleDocument();
        if (document == null || !document.hasSourceBinding()) {
            return "媒体源：未绑定（拿中控台右键唱片机/直播机后再放置）";
        }
        return "媒体源：" + document.sourceDimension() + "  " + document.sourceX() + ", "
                + document.sourceY() + ", " + document.sourceZ();
    }

    @Override
    protected ControlConsoleDocument controlConsoleDocument() {
        if (controlConsolePos == null || minecraft == null || minecraft.level == null) {
            return null;
        }
        var blockEntity = minecraft.level.getBlockEntity(controlConsolePos);
        return blockEntity instanceof ControlConsoleBlockEntity console ? console.document() : null;
    }

    protected void drawFirstPersonCrosshair(GuiGraphicsExtractor g, int x, int y, int w, int h) {
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

    protected void drawOrientationWidget(GuiGraphicsExtractor g, int centerX, int centerY) {
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
        axes.sort(java.util.Comparator.comparingDouble(axis -> axis.depth));
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

    protected void drawOrientationAxis(GuiGraphicsExtractor g, int centerX, int centerY, OrientationAxis axis,
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

    protected static void addOrientationAxis(List<OrientationAxis> axes, Matrix4f view, Vector3f worldAxis,
            String label, int color, boolean positive) {
        Vector3f cameraAxis = view.transformDirection(new Vector3f(worldAxis)).normalize();
        axes.add(new OrientationAxis(cameraAxis.x, -cameraAxis.y, cameraAxis.z, label, color, positive));
    }

    protected static void drawLine(GuiGraphicsExtractor g, int startX, int startY, int endX, int endY, int color) {
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

    protected static void drawDisc(GuiGraphicsExtractor g, int centerX, int centerY, int radius, int color) {
        for (int y = -radius; y <= radius; y++) {
            int halfWidth = (int) Math.floor(Math.sqrt(radius * radius - y * y));
            g.fillGradient(centerX - halfWidth, centerY + y, centerX + halfWidth + 1, centerY + y + 1,
                    color, color);
        }
    }

    protected static int dimColor(int color) {
        return (color & 0xFF000000) | (((color >>> 16) & 0xFF) / 2 << 16)
                | (((color >>> 8) & 0xFF) / 2 << 8) | ((color & 0xFF) / 2);
    }

    protected double orbitSensitivityScale() {
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

    protected void submitPipPreview(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY,
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
        float[] scaleXs = new float[screens.size()];
        float[] scaleYs = new float[screens.size()];
        float[] scaleZs = new float[screens.size()];
        float[] pivotXs = new float[screens.size()];
        float[] pivotYs = new float[screens.size()];
        float[] pivotZs = new float[screens.size()];
        float[] skewXByYs = new float[screens.size()];
        float[] skewYByXs = new float[screens.size()];
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
            scaleXs[i] = spec.scaleX;
            scaleYs[i] = spec.scaleY;
            scaleZs[i] = spec.scaleZ;
            pivotXs[i] = spec.pivotX;
            pivotYs[i] = spec.pivotY;
            pivotZs[i] = spec.pivotZ;
            skewXByYs[i] = spec.skewXByY;
            skewYByXs[i] = spec.skewYByX;
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
                yaws, pitches, rolls, elementTypes, scaleXs, scaleYs, scaleZs,
                pivotXs, pivotYs, pivotZs, skewXByYs, skewYByXs,
                encodedGizmoTool, selectedHandle,
                coordinateSpace == GizmoCoordinateSpace.LOCAL, controlConsoleMode,
                controlConsoleMode && controlConsolePos != null,
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

    protected abstract int previewX();
    protected abstract int previewY();
    protected abstract int previewW();
    protected abstract int previewH();
    protected abstract void drawNumericPanel(GuiGraphicsExtractor g, int mouseX, int mouseY);
    protected abstract CameraFrame createFirstPersonCameraFrame(int x, int y, int w, int h);
    protected abstract CameraFrame createOrbitCameraFrame(int x, int y, int w, int h);
    protected abstract SceneHit sceneHitAt(double mouseX, double mouseY, CameraFrame cameraFrame);
    protected abstract GizmoHandle gizmoHandleAt(double mouseX, double mouseY, int x, int y, int w, int h,
            CameraFrame cameraFrame);

}
