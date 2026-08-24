package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.scene_editor.core.camera.CameraFrame;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.camera.EditorMouseDragPolicy;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.camera.StandardCameraView;
import com.zhongbai233.scene_editor.core.projection.EditorProjection;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import com.zhongbai233.scene_editor.core.projection.PickingRay;
import com.zhongbai233.scene_editor.core.projection.ProjectedPoint;
import com.zhongbai233.scene_editor.core.selection.BlankClickSelectionPolicy;
import com.zhongbai233.scene_editor.core.gizmo.GizmoConstraint;
import com.zhongbai233.scene_editor.core.gizmo.GizmoDragMath;
import com.zhongbai233.scene_editor.core.gizmo.GizmoCoordinateSpace;
import com.zhongbai233.scene_editor.core.gizmo.GizmoTransformMath;
import com.zhongbai233.scene_editor.core.math.EditorTransform;
import com.zhongbai233.scene_editor.core.command.StateReplacementCommand;
import com.zhongbai233.scene_editor.core.transaction.DragTransaction;
import com.zhongbai233.scene_editor.minecraft.input.MinecraftEditorInput;
import com.zhongbai233.net_music_can_play_bili.link.HolographicScreenSettings;

/** Mouse/keyboard navigation, selection, gizmo manipulation, and undoable scene edits. */
abstract class HolographicEditorInputScreen extends HolographicEditorRenderScreen {
    protected HolographicEditorInputScreen(boolean bindEquippedGlasses, BlockPos controlConsolePos) {
        super(bindEquippedGlasses, controlConsolePos);
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
            dragMode = switch (EditorMouseDragPolicy.action(event.button(), firstPersonPreview,
                    activeHandle != GizmoHandle.NONE)) {
                case ORBIT -> DragMode.CAMERA;
                case PAN -> DragMode.PAN;
                case GIZMO -> DragMode.GIZMO;
            };
            if (dragMode == DragMode.GIZMO) {
                gizmoDragSession = createGizmoDragSession(event.x(), event.y(), cameraFrame, activeHandle);
                if (gizmoDragSession == null) {
                    dragMode = DragMode.CAMERA;
                    activeHandle = GizmoHandle.NONE;
                } else {
                    gizmoTransaction = new DragTransaction<>(snapshotScene(), "拖动场景元素");
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
                EditorSceneState current = snapshotScene();
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
            EditorSceneState current = currentEditState();
            if (current != null) {
                applySceneState(editHistory.undo(current));
            }
            return true;
        }
        if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_Y) {
            EditorSceneState current = currentEditState();
            if (current != null) {
                applySceneState(editHistory.redo(current));
            }
            return true;
        }
        if (!navigationMode()) {
            StandardCameraView standardView = MinecraftEditorInput.standardView(event.key()).orElse(null);
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

    protected boolean setFlyKey(int key, boolean pressed) {
        MinecraftEditorInput.FlyControl control = MinecraftEditorInput.flyControl(key, navigationMode()).orElse(null);
        if (control == null) {
            return false;
        }
        switch (control) {
            case FORWARD -> flyForward = pressed;
            case BACKWARD -> flyBackward = pressed;
            case LEFT -> flyLeft = pressed;
            case RIGHT -> flyRight = pressed;
            case DOWN -> flyDown = pressed;
            case UP -> flyUp = pressed;
            case FAST -> flyFast = pressed;
        }
        return true;
    }

    @Override
    protected boolean navigationMode() {
        return controlConsoleMode && selectedScreen < 0 && !firstPersonPreview;
    }

    protected void enterNavigationMode() {
        clearFlyKeys();
        modelingCamera = previewCamera;
        selectedScreen = -1;
        EditorCameraState target = navigationCamera != null ? navigationCamera : modelingCamera;
        if (target.mode() == EditorCameraMode.ORTHOGRAPHIC) {
            target = cameraController.switchProjection(target, EditorCameraMode.ORBIT);
        }
        setPreviewCamera(target);
    }

    @Override
    protected void selectElement(int index) {
        if (index < 0 || index >= screens.size()) {
            return;
        }
        clearFlyKeys();
        EditorCameraState cameraBeforeSelection = previewCamera;
        if (navigationMode()) {
            navigationCamera = cameraBeforeSelection;
        }
        selectedScreen = index;
        // 选择只改变编辑目标，不应隐式切换到旧的建模相机。显式 F 聚焦、数字视图快捷键和
        // 投影切换仍会按原逻辑改变相机；从漫游模式进入元素编辑时则从当前观察位置继续。
        setPreviewCamera(cameraBeforeSelection);
    }

    @Override
    protected void clearFlyKeys() {
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

    protected void applyGizmoDrag(double mouseX, double mouseY) {
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
            switch (session.handle) {
                case X -> screen.offsetX = finiteOrPrevious(
                    session.start.offsetX + (float) axisDelta, screen.offsetX);
                case Y -> screen.offsetY = finiteOrPrevious(
                    session.start.offsetY + (float) axisDelta, screen.offsetY);
                case Z -> screen.distance = finiteOrPrevious(
                    session.start.distance + (float) axisDelta, screen.distance);
                case CENTER -> {
                screen.offsetX = finiteOrPrevious(
                    session.start.offsetX + (float) worldDelta.x, screen.offsetX);
                screen.offsetY = finiteOrPrevious(
                    session.start.offsetY + (float) worldDelta.y, screen.offsetY);
                }
                default -> { }
                }
            }
            case ROTATE -> {
                if (session.handle.isRotationRing()) {
                    float delta = GizmoDragMath.rotationDeltaDegrees(session.origin, session.axis,
                            session.startHit, currentHit);
                    int axisIndex = session.handle == GizmoHandle.RING_X ? 0
                            : session.handle == GizmoHandle.RING_Y ? 1 : 2;
                    applyTransform(screen, GizmoTransformMath.rotate(transform(session.start), axisIndex,
                            delta, coordinateSpace));
                }
            }
            case SCALE -> {
                float factor;
                if (session.handle == GizmoHandle.X) {
                    factor = 1.0F + (float) axisDelta;
                    applyScaledTransform(screen, session.start, 0, factor);
                } else if (session.handle == GizmoHandle.Y) {
                    factor = 1.0F + (float) axisDelta;
                    applyScaledTransform(screen, session.start, 1, factor);
                } else if (session.handle == GizmoHandle.Z) {
                    factor = 1.0F + (float) axisDelta;
                    applyScaledTransform(screen, session.start, 2, factor);
                } else if (session.handle == GizmoHandle.CENTER) {
                    double delta = session.handle == GizmoHandle.CENTER
                            ? worldDelta.dot(session.localY) : axisDelta;
                    factor = 1.0F + (float) delta;
                    screen.scaleX = boundedScale(session.start.scaleX * factor, screen.scaleX);
                    screen.scaleY = boundedScale(session.start.scaleY * factor, screen.scaleY);
                    screen.scaleZ = boundedScale(session.start.scaleZ * factor, screen.scaleZ);
                }
            }
        }
    }

    protected static float finiteOrPrevious(float candidate, float previous) {
        return Float.isFinite(candidate) ? candidate : previous;
    }

    protected static float boundedScale(float candidate, float previous) {
        return Float.isFinite(candidate)
                ? Math.clamp(candidate, ControlConsoleElement.MIN_SCALE, ControlConsoleElement.MAX_SCALE)
                : previous;
    }

    protected void applyScaledTransform(PreviewScreenSpec screen, ScreenSnapshot start, int axisIndex,
            float factor) {
        GizmoTransformMath.scale(transform(start), axisIndex, factor, coordinateSpace,
                ControlConsoleElement.MIN_SCALE, ControlConsoleElement.MAX_SCALE,
                ControlConsoleElement.MAX_SKEW).ifPresent(value -> applyTransform(screen, value));
    }

    protected static EditorTransform transform(ScreenSnapshot value) {
        return EditorTransform.fromEulerDegrees(new Vector3f(value.offsetX, value.offsetY, value.distance),
                value.yaw, value.pitch, value.roll, new Vector3f(value.scaleX, value.scaleY, value.scaleZ),
                new Vector3f(value.pivotX, value.pivotY, value.pivotZ), value.skewXByY, value.skewYByX);
    }

    protected static void applyTransform(PreviewScreenSpec screen, EditorTransform value) {
        Vector3f scale = value.scale();
        Vector3f pivot = value.pivot();
        Vector3f euler = value.rotation().getEulerAnglesYXZ(new Vector3f());
        screen.pitch = (float) Math.toDegrees(euler.x);
        screen.yaw = (float) Math.toDegrees(euler.y);
        screen.roll = (float) Math.toDegrees(euler.z);
        screen.scaleX = scale.x;
        screen.scaleY = scale.y;
        screen.scaleZ = scale.z;
        screen.pivotX = pivot.x;
        screen.pivotY = pivot.y;
        screen.pivotZ = pivot.z;
        screen.skewXByY = value.skewXByY();
        screen.skewYByX = value.skewYByX();
    }

    protected static float saturatedPositiveFloat(double value) {
        return value >= Float.MAX_VALUE ? Float.MAX_VALUE : (float) value;
    }

    protected GizmoDragSession createGizmoDragSession(double mouseX, double mouseY, CameraFrame cameraFrame,
            GizmoHandle handle) {
        PreviewScreenSpec screen = screen();
        if (screen.locked) {
            return null;
        }
        ScreenSnapshot start = snapshot(screen);
        Vector3d origin = new Vector3d(screen.offsetX + screen.pivotX,
                1.55D + screen.offsetY + screen.pivotY, screen.distance + screen.pivotZ);
        Quaternionf rotation = new Quaternionf().rotateYXZ((float) Math.toRadians(screen.yaw),
            (float) Math.toRadians(screen.pitch), (float) Math.toRadians(screen.roll));
        Vector3d localX = new Vector3d(rotation.transform(new Vector3f(1.0F, 0.0F, 0.0F)));
        Vector3d localY = new Vector3d(rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F)));
        Vector3d localZ = new Vector3d(rotation.transform(new Vector3f(0.0F, 0.0F, 1.0F)));
        boolean worldSpace = coordinateSpace == GizmoCoordinateSpace.WORLD;
        Vector3d axis = switch (handle) {
            case X -> worldSpace ? new Vector3d(1.0D, 0.0D, 0.0D) : localX;
            case Y -> worldSpace ? new Vector3d(0.0D, 1.0D, 0.0D) : localY;
            case Z -> worldSpace ? new Vector3d(0.0D, 0.0D, 1.0D) : localZ;
            case RING_X -> worldSpace ? new Vector3d(1.0D, 0.0D, 0.0D) : localX;
            case RING_Y -> worldSpace ? new Vector3d(0.0D, 1.0D, 0.0D) : localY;
            case RING_Z -> worldSpace ? new Vector3d(0.0D, 0.0D, 1.0D) : localZ;
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

    protected EditorSceneState currentEditState() {
        return snapshotScene();
    }

    @Override
    protected EditorSceneState snapshotScene() {
        return new EditorSceneState(screens.stream().map(HolographicScreenConfigTestScreen::snapshot).toList(),
                selectedScreen, consoleDraft == null ? null : new ConsoleProperties(consoleDraft.displayName(),
                        consoleDraft.hardRangeX(), consoleDraft.hardRangeY(), consoleDraft.hardRangeZ()));
    }

    protected void applySceneState(EditorSceneState state) {
        if (state == null) {
            return;
        }
        screens.clear();
        state.screens.forEach(value -> screens.add(PreviewScreenSpec.fromSnapshot(value)));
        selectedScreen = screens.isEmpty() ? -1 : Math.clamp(state.selectedScreen, -1, screens.size() - 1);
        if (state.consoleProperties != null && consoleDraft != null) {
            ConsoleProperties value = state.consoleProperties;
            consoleDraft = new ControlConsoleDocument(consoleDraft.schemaVersion(), consoleDraft.consoleId(),
                    consoleDraft.revision(), consoleDraft.ownerId(), consoleDraft.accessMode(),
                    consoleDraft.trustedPlayerIds(), value.displayName, consoleDraft.sourceDimension(),
                    consoleDraft.sourceKind(), consoleDraft.sourceX(), consoleDraft.sourceY(), consoleDraft.sourceZ(),
                    value.hardRangeX, value.hardRangeY, value.hardRangeZ, consoleElementsSnapshot());
        }
        syncNumericEditBoxes();
        init();
    }

    @Override
    protected void edit(String description, Runnable mutation) {
        EditorSceneState before = snapshotScene();
        mutation.run();
        EditorSceneState after = snapshotScene();
        if (!before.equals(after)) {
            editHistory.execute(before, new StateReplacementCommand<>(before, after, description));
        }
    }

    @Override
    protected void editSelected(String description, java.util.function.Consumer<PreviewScreenSpec> mutation) {
        edit(description, () -> {
            PreviewScreenSpec selected = selectedScreenOrNull();
            if (selected != null && !selected.locked) {
                mutation.accept(selected);
            }
        });
    }

    protected static ScreenSnapshot snapshot(PreviewScreenSpec screen) {
        return new ScreenSnapshot(screen.elementId, screen.type, screen.name, screen.distance, screen.offsetX,
                screen.offsetY, screen.height, screen.aspect, screen.yaw, screen.pitch, screen.roll,
                screen.scaleX, screen.scaleY, screen.scaleZ, screen.pivotX, screen.pivotY, screen.pivotZ,
                screen.skewXByY, screen.skewYByX, screen.contentMode, screen.text, screen.followLyrics,
                screen.showTranslation, screen.textScale, screen.color, screen.volume, screen.channelIndex,
                screen.maxDistance, screen.autoMixJoc, screen.translationColor, screen.backgroundColor,
                screen.alignment, screen.maxWidth, screen.wrap, screen.enabled, screen.locked, screen.brightness);
    }

    protected static ScreenSnapshot snapshot(ControlConsoleElement element) {
        ElementType type = switch (element.type()) {
            case SCREEN -> ElementType.SCREEN;
            case SUBTITLE -> ElementType.SUBTITLE;
            case AUDIO -> ElementType.AUDIO;
        };
        return new ScreenSnapshot(element.elementId(), type, element.name(), element.distance(), element.offsetX(),
                element.offsetY(), element.height(), element.aspect(), element.yaw(), element.pitch(), element.roll(),
                element.scaleX(), element.scaleY(), element.scaleZ(), element.pivotX(), element.pivotY(),
                element.pivotZ(), element.skewXByY(), element.skewYByX(), element.contentMode(), element.text(),
                element.followLyrics(), element.showTranslation(), element.textScale(), element.color(),
                element.volume(), element.channelIndex(), element.maxDistance(), element.autoMixJoc(),
                element.translationColor(), element.backgroundColor(), element.alignment(), element.maxWidth(),
                element.wrap(), element.enabled(), element.locked(), element.brightness());
    }

    protected void presetRight() {
        PreviewScreenSpec screen = screen();
        if (screen.locked) return;
        screen.distance = HolographicScreenSettings.DEFAULT_DISTANCE;
        screen.offsetX = 0.65F;
        screen.offsetY = HolographicScreenSettings.DEFAULT_OFFSET_Y;
        screen.height = HolographicScreenSettings.DEFAULT_HEIGHT;
        screen.aspect = HolographicScreenSettings.DEFAULT_ASPECT;
        screen.roll = HolographicScreenSettings.DEFAULT_ROLL;
        resetAdvancedTransform(screen);
    }

    protected void presetLeft() {
        PreviewScreenSpec screen = screen();
        if (screen.locked) return;
        screen.distance = HolographicScreenSettings.DEFAULT_DISTANCE;
        screen.offsetX = -0.65F;
        screen.offsetY = HolographicScreenSettings.DEFAULT_OFFSET_Y;
        screen.height = HolographicScreenSettings.DEFAULT_HEIGHT;
        screen.aspect = HolographicScreenSettings.DEFAULT_ASPECT;
        screen.roll = HolographicScreenSettings.DEFAULT_ROLL;
        resetAdvancedTransform(screen);
    }

    protected void presetCinema() {
        PreviewScreenSpec screen = screen();
        if (screen.locked) return;
        screen.distance = 2.1F;
        screen.offsetX = 0.0F;
        screen.offsetY = 0.0F;
        screen.height = 1.8F;
        screen.aspect = HolographicScreenSettings.DEFAULT_ASPECT;
        screen.roll = HolographicScreenSettings.DEFAULT_ROLL;
        resetAdvancedTransform(screen);
    }

    protected void resetDefaults() {
        PreviewScreenSpec screen = screen();
        if (screen.locked) return;
        screen.distance = HolographicScreenSettings.DEFAULT_DISTANCE;
        screen.offsetX = HolographicScreenSettings.DEFAULT_OFFSET_X;
        screen.offsetY = HolographicScreenSettings.DEFAULT_OFFSET_Y;
        screen.height = HolographicScreenSettings.DEFAULT_HEIGHT;
        screen.aspect = HolographicScreenSettings.DEFAULT_ASPECT;
        screen.roll = HolographicScreenSettings.DEFAULT_ROLL;
        resetAdvancedTransform(screen);
        firstPersonPreview = false;
        previewScale = DEFAULT_PREVIEW_SCALE;
        setPreviewCamera(legacyOrbitCamera(DEFAULT_PREVIEW_YAW, DEFAULT_PREVIEW_PITCH,
            ORBIT_DEFAULT_CAMERA_DISTANCE, new Vector3d(0.0D, ORBIT_TARGET_Y, 0.0D)));
    }

    protected static void resetAdvancedTransform(PreviewScreenSpec screen) {
        screen.scaleX = 1.0F;
        screen.scaleY = 1.0F;
        screen.scaleZ = 1.0F;
        screen.pivotX = 0.0F;
        screen.pivotY = 0.0F;
        screen.pivotZ = 0.0F;
        screen.skewXByY = 0.0F;
        screen.skewYByX = 0.0F;
    }

    protected boolean inPreview(double mouseX, double mouseY) {
        int x = previewX();
        int y = previewY();
        return mouseX >= x && mouseX <= x + previewW() && mouseY >= y && mouseY <= y + previewH();
    }

    protected GizmoProjection gizmoProjection(int x, int y, int w, int h, CameraFrame cameraFrame) {
        PreviewScreenSpec screen = selectedScreenOrNull();
        if (screen == null) {
            GizmoPoint hidden = new GizmoPoint(0.0D, 0.0D, 0.0D, false);
                return new GizmoProjection(hidden, hidden, hidden, hidden, new GizmoPoint[0],
                    new GizmoPoint[0], new GizmoPoint[0]);
        }
        double centerX = screen.offsetX + screen.pivotX;
        double centerY = 1.55D + screen.offsetY + screen.pivotY;
        double centerZ = screen.distance + screen.pivotZ;
        Quaternionf rotation = screenRotation(screen);
        boolean worldSpace = coordinateSpace == GizmoCoordinateSpace.WORLD;
        Vector3f xDirection = worldSpace ? new Vector3f(1.0F, 0.0F, 0.0F)
            : rotation.transform(new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f yDirection = worldSpace ? new Vector3f(0.0F, 1.0F, 0.0F)
            : rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F));
        Vector3f zDirection = worldSpace ? new Vector3f(0.0F, 0.0F, 1.0F)
            : rotation.transform(new Vector3f(0.0F, 0.0F, 1.0F));

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

    protected GizmoPoint[] projectGizmoRing(double centerX, double centerY, double centerZ, Quaternionf rotation,
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

    protected GizmoPoint projectGizmoPoint(double worldX, double worldY, double worldZ, CameraFrame cameraFrame) {
        ProjectedPoint point = EditorProjection.project(new Vector3d(worldX, worldY, worldZ),
                cameraFrame.matrices(), cameraFrame.viewport());
        return new GizmoPoint(point.screenX(), point.screenY(), point.depth(), point.visible());
    }

    @Override
    protected SceneHit sceneHitAt(double mouseX, double mouseY, CameraFrame cameraFrame) {
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
            double halfHeight = screen.height * 0.5D;
            Matrix4f transform = new Matrix4f().translation(0.0F, 1.55F, 0.0F)
                    .mul(previewTransform(screen).matrix());
            var intersection = ray.intersectTransformedRectangle(transform,
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

    protected static Quaternionf screenRotation(PreviewScreenSpec screen) {
        return new Quaternionf().rotateYXZ((float) Math.toRadians(screen.yaw),
                (float) Math.toRadians(screen.pitch), (float) Math.toRadians(screen.roll));
    }

    protected static EditorTransform previewTransform(PreviewScreenSpec screen) {
        return EditorTransform.fromEulerDegrees(new Vector3f(screen.offsetX, screen.offsetY, screen.distance),
                screen.yaw, screen.pitch, screen.roll,
                new Vector3f(screen.scaleX, screen.scaleY, screen.scaleZ),
                new Vector3f(screen.pivotX, screen.pivotY, screen.pivotZ),
                screen.skewXByY, screen.skewYByX);
    }

    @Override
    protected GizmoHandle gizmoHandleAt(double mouseX, double mouseY, int x, int y, int w, int h,
            CameraFrame cameraFrame) {
        if (!selectedElementEditable()) {
            return GizmoHandle.NONE;
        }
        return gizmoHandleAt(mouseX, mouseY, gizmoProjection(x, y, w, h, cameraFrame));
    }

    @Override
    protected boolean selectedElementEditable() {
        PreviewScreenSpec selected = selectedScreenOrNull();
        return selected != null && !selected.locked;
    }

    protected GizmoHandle gizmoHandleAt(double mouseX, double mouseY, GizmoProjection gizmo) {
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

    protected static boolean hitsRing(double mouseX, double mouseY, GizmoPoint[] ring) {
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

    protected static double distanceToSegment(double px, double py, double x1, double y1, double x2, double y2) {
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

    protected boolean isCloseButton(double mouseX, double mouseY) {
        int x = width - 8 - ICON_W;
        return mouseX >= x && mouseX <= x + ICON_W && mouseY >= 4 && mouseY <= 4 + ICON_H;
    }

    protected abstract CameraFrame orbitCameraFrameFor(int x, int y, int w, int h);
    protected abstract EditorViewport currentPreviewViewport();
    protected abstract void switchProjection(EditorCameraMode targetMode);
    protected abstract void syncLegacyPreviewScale();

}
