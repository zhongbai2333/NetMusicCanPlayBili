package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.scene_editor.core.camera.CameraFrame;
import com.zhongbai233.scene_editor.core.camera.EditorCameraController;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.gizmo.GizmoConstraint;
import com.zhongbai233.scene_editor.core.gizmo.GizmoCoordinateSpace;
import com.zhongbai233.scene_editor.core.command.CommandStack;
import com.zhongbai233.scene_editor.core.transaction.DragTransaction;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.HolographicScreenSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 全息眼镜屏幕配置界面。
 */
abstract class HolographicEditorScreenState extends Screen {
    protected static final int GOLD = BlackGoldScreen.GOLD;
    protected static final int GOLD_DIM = BlackGoldScreen.GOLD_DIM;
    protected static final int BG_HEADER = BlackGoldScreen.BG_HEADER;
    protected static final int TEXT_SECONDARY = BlackGoldScreen.TEXT_SECONDARY;
    protected static final int TEXT_DIM = BlackGoldScreen.TEXT_DIM;

    protected static final float DEFAULT_PREVIEW_SCALE = HolographicScreenSettings.DEFAULT_PREVIEW_SCALE;
    protected static final double MIN_CAMERA_SCALE = 1.0e-4D;
    protected static final double MAX_CAMERA_SCALE = 1.0e6D;
    protected static final float EDITOR_FAR_PLANE = 1.0e6F;
    protected static final float PLAYER_HEAD_RELATIVE_YAW = 0.0F;
    protected static final float DEFAULT_PREVIEW_YAW = 180.0F;
    protected static final float DEFAULT_PREVIEW_PITCH = 35.0F;
    protected static final double ORBIT_FOV_DEGREES = HolographicScreenSettings.ORBIT_FOV_DEGREES;
    protected static final double ORBIT_DEFAULT_CAMERA_DISTANCE = HolographicScreenSettings.ORBIT_DEFAULT_CAMERA_DISTANCE;
    protected static final double ORBIT_TARGET_Y = HolographicScreenSettings.ORBIT_TARGET_Y;
    /** 中控台首次打开时保留约 20% 额外画面边距，避免本体和附近元素贴满视口。 */
    protected static final double CONTROL_CONSOLE_INITIAL_FOCUS_RADIUS = 1.25D;
    protected static final int ICON_W = 22;
    protected static final int ICON_H = 18;
    protected static final int ICON_GAP = 3;
    protected static final int GIZMO_HIT_RADIUS = 7;
    protected static final double GIZMO_AXIS_WORLD_LEN = HolographicScreenSettings.GIZMO_AXIS_WORLD_LEN;
    protected static final int GIZMO_RING_SEGMENTS = 48;
    protected static final double ORBIT_YAW_DEGREES_PER_PIXEL = 0.35D;
    protected static final double ORBIT_PITCH_DEGREES_PER_PIXEL = 0.30D;
    protected static final double PAN_SENSITIVITY = 0.82D;
    protected static final int ORIENTATION_WIDGET_RADIUS = 18;
    protected static final int ORIENTATION_WIDGET_MARGIN_RIGHT = 30;
    protected static final int ORIENTATION_WIDGET_MARGIN_BOTTOM = 40;
    protected static final int CONTROL_LEFT_PANEL_W = 156;
    protected static final int CONTROL_RIGHT_PANEL_W = 226;
    protected static final int CONTROL_PANEL_GAP = 4;
    protected static final Vector3d EDITOR_WORLD_UP = new Vector3d(0.0D, 1.0D, 0.0D);
    protected static final Vector3d PREVIEW_PLAYER_BOUNDS_MIN = new Vector3d(-0.345D, -0.03D, -0.345D);
    protected static final Vector3d PREVIEW_PLAYER_BOUNDS_MAX = new Vector3d(0.345D, 1.88D, 0.345D);

    protected final List<PreviewScreenSpec> screens = new ArrayList<>();
    protected int selectedScreen;
    protected int consoleElementScroll;
    protected final boolean bindEquippedGlasses;
    protected final BlockPos controlConsolePos;
    protected final boolean controlConsoleMode;
    protected final Level controlConsoleLevel;
    protected final Player controlConsolePlayer;
    protected EditTool activeTool = EditTool.MOVE;
    protected GizmoCoordinateSpace coordinateSpace = GizmoCoordinateSpace.LOCAL;
    protected DragMode dragMode = DragMode.NONE;
    protected GizmoHandle activeHandle = GizmoHandle.NONE;

    protected float previewScale = DEFAULT_PREVIEW_SCALE;
    protected final EditorCameraController cameraController = new EditorCameraController(
            new EditorCameraController.Settings(0.08D,
                MIN_CAMERA_SCALE, MAX_CAMERA_SCALE,
                MIN_CAMERA_SCALE, MAX_CAMERA_SCALE, 8.0D, 2.0D, 0.05D, 1.15D));
    protected EditorCameraState previewCamera = legacyOrbitCamera(DEFAULT_PREVIEW_YAW, DEFAULT_PREVIEW_PITCH,
            ORBIT_DEFAULT_CAMERA_DISTANCE, new Vector3d(0.0D, ORBIT_TARGET_Y, 0.0D));
    protected EditorCameraState modelingCamera = previewCamera;
    protected EditorCameraState navigationCamera;
    protected boolean draggingPreview;
    protected int previewDragButton = -1;
    protected boolean firstPersonPreview;
    protected double lastMouseX;
    protected double lastMouseY;
    protected double previewClickX;
    protected double previewClickY;
    protected boolean previewClickHitPlayer;
    protected boolean previewDragStartedWithoutElement;
    protected CameraFrame lastOrbitCameraFrame;
    protected boolean flyForward;
    protected boolean flyBackward;
    protected boolean flyLeft;
    protected boolean flyRight;
    protected boolean flyDown;
    protected boolean flyUp;
    protected boolean flyFast;
    protected GizmoDragSession gizmoDragSession;
    protected final CommandStack<EditorSceneState> editHistory = new CommandStack<>(128);
    protected DragTransaction<EditorSceneState> gizmoTransaction;

    protected boolean showNumericPanel;
    protected boolean showTransformInspector;
    protected EditBox numericDistanceBox;
    protected EditBox numericOffsetXBox;
    protected EditBox numericOffsetYBox;
    protected EditBox numericHeightBox;
    protected EditBox numericAspectBox;
    protected EditBox numericRollBox;
    protected EditBox numericYawBox;
    protected EditBox numericPitchBox;
    protected EditBox numericScaleXBox;
    protected EditBox numericScaleYBox;
    protected EditBox numericScaleZBox;
    protected EditBox numericPivotXBox;
    protected EditBox numericPivotYBox;
    protected EditBox numericPivotZBox;
    protected EditBox numericSkewXByYBox;
    protected EditBox numericSkewYByXBox;
    protected EditBox elementTextBox;
    protected EditBox elementTextScaleBox;
    protected ElementVolumeSlider elementVolumeSlider;
    protected EditBox elementMaxDistanceBox;
    protected EditBox elementColorBox;
    protected EditBox elementTranslationColorBox;
    protected EditBox elementBackgroundColorBox;
    protected EditBox elementMaxWidthBox;
    protected EditBox consoleTrustedPlayersBox;
    protected boolean syncingNumericEditBoxes;
    protected ControlConsoleDocument.AccessMode consoleAccessModeDraft;
    protected ControlConsoleDocument consoleAccessRollback;
    protected ControlConsoleDocument consoleDraft;
    protected boolean consoleElementsLoaded;
    protected boolean roamingHistoryPending;
    protected long consoleAutosaveTick;
    protected int consoleSavedFingerprint;
    protected int consolePendingFingerprint;
    protected int consoleObservedFingerprint;
    protected boolean consoleAutosaveFingerprintInitialized;
    protected UUID consolePendingOperation;
    protected boolean consoleSaveConflict;
    protected ControlConsoleDocument consoleConflictAuthoritative;
    protected String consoleSaveStatus = "";
    protected boolean worldRoamingTransitionPending;
    protected boolean transferLeaseToRoaming;
    protected int initialFocusElement = -1;
    protected Vector3d terrainPreviewCenterLocal = new Vector3d(0.0D, 0.5D, 0.0D);

    protected HolographicEditorScreenState(boolean bindEquippedGlasses, BlockPos controlConsolePos) {
        super(Component.literal(controlConsolePos != null ? "中控台场景建模"
                : bindEquippedGlasses ? "全息眼镜配置" : "全息屏幕配置测试"));
        this.bindEquippedGlasses = bindEquippedGlasses;
        this.controlConsolePos = controlConsolePos != null ? controlConsolePos.immutable() : null;
        this.controlConsoleMode = controlConsolePos != null;
        Minecraft minecraft = Minecraft.getInstance();
        this.controlConsoleLevel = controlConsoleMode ? minecraft.level : null;
        this.controlConsolePlayer = controlConsoleMode ? minecraft.player : null;
        if (!controlConsoleMode) {
            screens.add(PreviewScreenSpec.defaults());
        }
    }

    protected abstract void editSelected(String description,
            java.util.function.Consumer<PreviewScreenSpec> mutation);

    protected static int documentFingerprint(ControlConsoleDocument document) {
        return java.util.Objects.hash(document.displayName(), document.hardRangeX(), document.hardRangeY(),
                document.hardRangeZ(), document.elements());
    }

    protected static float saturatedPositiveFloat(double value) {
        return value >= Float.MAX_VALUE ? Float.MAX_VALUE : (float) Math.max(0.0D, value);
    }

    protected static ScreenSnapshot snapshot(PreviewScreenSpec screen) {
        return new ScreenSnapshot(screen.elementId, screen.type, screen.name, screen.distance, screen.offsetX,
                screen.offsetY, screen.height, screen.aspect, screen.yaw, screen.pitch, screen.roll,
                screen.scaleX, screen.scaleY, screen.scaleZ, screen.pivotX, screen.pivotY, screen.pivotZ,
                screen.skewXByY, screen.skewYByX, screen.contentMode, screen.text, screen.followLyrics,
                screen.showTranslation, screen.textScale, screen.color, screen.volume, screen.channelIndex,
                screen.maxDistance, screen.autoMixJoc, screen.translationColor, screen.backgroundColor,
                screen.alignment, screen.maxWidth, screen.wrap, screen.enabled, screen.locked);
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
                element.wrap(), element.enabled(), element.locked());
    }

    protected static EditorCameraState legacyOrbitCamera(double yawDegrees, double pitchDegrees, double distance,
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

    protected static String fmt(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001F) {
            return Integer.toString(Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    protected static final class GizmoPoint {
        final double x, y, depth;
        final boolean visible;

        GizmoPoint(double x, double y, double depth, boolean visible) {
            this.x = x;
            this.y = y;
            this.depth = depth;
            this.visible = visible;
        }
    }

    protected static final class GizmoProjection {
        final GizmoPoint center, xAxis, yAxis, zAxis;
        final GizmoPoint[] ringX, ringY, ringZ;

        GizmoProjection(GizmoPoint center, GizmoPoint xAxis, GizmoPoint yAxis, GizmoPoint zAxis,
                GizmoPoint[] ringX, GizmoPoint[] ringY, GizmoPoint[] ringZ) {
            this.center = center;
            this.xAxis = xAxis;
            this.yAxis = yAxis;
            this.zAxis = zAxis;
            this.ringX = ringX;
            this.ringY = ringY;
            this.ringZ = ringZ;
        }
    }

    protected static final class ScreenSnapshot {
        final UUID elementId;
        final ElementType type;
        final String name;
        final float distance, offsetX, offsetY, height, aspect, yaw, pitch, roll;
        final float scaleX, scaleY, scaleZ, pivotX, pivotY, pivotZ, skewXByY, skewYByX;
        final String contentMode, text;
        final boolean followLyrics, showTranslation;
        final float textScale;
        final int color;
        final float volume;
        final int channelIndex;
        final float maxDistance;
        final boolean autoMixJoc;
        final int translationColor, backgroundColor;
        final ControlConsoleElement.Alignment alignment;
        final float maxWidth;
        final boolean wrap, enabled, locked;

        ScreenSnapshot(UUID elementId, ElementType type, String name, float distance, float offsetX, float offsetY,
                float height, float aspect, float yaw, float pitch, float roll, float scaleX, float scaleY,
                float scaleZ, float pivotX, float pivotY, float pivotZ, float skewXByY, float skewYByX,
                String contentMode, String text, boolean followLyrics, boolean showTranslation, float textScale,
                int color, float volume, int channelIndex, float maxDistance, boolean autoMixJoc,
                int translationColor, int backgroundColor, ControlConsoleElement.Alignment alignment,
                float maxWidth, boolean wrap, boolean enabled, boolean locked) {
            this.elementId = elementId;
            this.type = type;
            this.name = name;
            this.distance = distance;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.height = height;
            this.aspect = aspect;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.scaleZ = scaleZ;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.pivotZ = pivotZ;
            this.skewXByY = skewXByY;
            this.skewYByX = skewYByX;
            this.contentMode = contentMode;
            this.text = text;
            this.followLyrics = followLyrics;
            this.showTranslation = showTranslation;
            this.textScale = textScale;
            this.color = color;
            this.volume = volume;
            this.channelIndex = channelIndex;
            this.maxDistance = maxDistance;
            this.autoMixJoc = autoMixJoc;
            this.translationColor = translationColor;
            this.backgroundColor = backgroundColor;
            this.alignment = alignment;
            this.maxWidth = maxWidth;
            this.wrap = wrap;
            this.enabled = enabled;
            this.locked = locked;
        }
    }

    protected static final class ConsoleProperties {
        final String displayName;
        final double hardRangeX, hardRangeY, hardRangeZ;

        ConsoleProperties(String displayName, double hardRangeX, double hardRangeY, double hardRangeZ) {
            this.displayName = displayName;
            this.hardRangeX = hardRangeX;
            this.hardRangeY = hardRangeY;
            this.hardRangeZ = hardRangeZ;
        }
    }

    protected static final class EditorSceneState {
        final List<ScreenSnapshot> screens;
        final int selectedScreen;
        final ConsoleProperties consoleProperties;

        EditorSceneState(List<ScreenSnapshot> screens, int selectedScreen, ConsoleProperties consoleProperties) {
            this.screens = List.copyOf(screens);
            this.selectedScreen = selectedScreen;
            this.consoleProperties = consoleProperties;
        }
    }

    protected static final class OrientationAxis {
        final double screenX, screenY, depth;
        final String label;
        final int color;
        final boolean positive;

        OrientationAxis(double screenX, double screenY, double depth, String label, int color, boolean positive) {
            this.screenX = screenX;
            this.screenY = screenY;
            this.depth = depth;
            this.label = label;
            this.color = color;
            this.positive = positive;
        }
    }

    protected static final class SceneHit {
        final SceneHitType type;
        final int screenIndex;
        final double distance;

        SceneHit(SceneHitType type, int screenIndex, double distance) {
            this.type = type;
            this.screenIndex = screenIndex;
            this.distance = distance;
        }

        static SceneHit none() { return new SceneHit(SceneHitType.NONE, -1, Double.POSITIVE_INFINITY); }
        static SceneHit player(double distance) { return new SceneHit(SceneHitType.PLAYER, -1, distance); }
        static SceneHit screen(int screenIndex, double distance) {
            return new SceneHit(SceneHitType.SCREEN, screenIndex, distance);
        }
    }

    protected static final class GizmoDragSession {
        final int screenIndex;
        final EditTool tool;
        final GizmoHandle handle;
        final CameraFrame cameraFrame;
        final ScreenSnapshot start;
        final Vector3d origin, localY, axis, constraintVector, startHit;
        final GizmoConstraint constraint;

        GizmoDragSession(int screenIndex, EditTool tool, GizmoHandle handle, CameraFrame cameraFrame,
                ScreenSnapshot start, Vector3d origin, Vector3d localY, Vector3d axis,
                GizmoConstraint constraint, Vector3d constraintVector, Vector3d startHit) {
            this.screenIndex = screenIndex;
            this.tool = tool;
            this.handle = handle;
            this.cameraFrame = cameraFrame;
            this.start = start;
            this.origin = new Vector3d(origin);
            this.localY = new Vector3d(localY);
            this.axis = axis != null ? new Vector3d(axis) : null;
            this.constraint = constraint;
            this.constraintVector = new Vector3d(constraintVector);
            this.startHit = new Vector3d(startHit);
        }
    }

    protected PreviewScreenSpec screen() {
        PreviewScreenSpec selected = selectedScreenOrNull();
        if (selected == null) {
            throw new IllegalStateException("no control console element is selected");
        }
        return selected;
    }

    protected PreviewScreenSpec selectedScreenOrNull() {
        return selectedScreen >= 0 && selectedScreen < screens.size() ? screens.get(selectedScreen) : null;
    }

    protected enum EditTool {
        MOVE,
        ROTATE,
        SCALE
    }

    protected String coordinateSpaceLabel() {
        return coordinateSpace == GizmoCoordinateSpace.LOCAL ? "本地" : "世界";
    }

    protected enum DragMode {
        NONE,
        CAMERA,
        PAN,
        GIZMO
    }

    protected enum GizmoHandle {
        NONE,
        CENTER,
        X,
        Y,
        Z,
        RING_X,
        RING_Y,
        RING_Z;

        protected boolean isRotationRing() {
            return this == RING_X || this == RING_Y || this == RING_Z;
        }
    }

    protected enum SceneHitType {
        NONE,
        PLAYER,
        SCREEN
    }

    protected final class ElementVolumeSlider extends AbstractSliderButton {
        protected ElementVolumeSlider(int x, int y, int width, int height, float volume) {
            super(x, y, width, height, Component.empty(), Math.clamp(volume, 0.0F, 1.0F));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("音量：" + Math.round(value * 100.0D) + "%"));
        }

        @Override
        protected void applyValue() {
            updateMessage();
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            float volume = (float) Math.clamp(value, 0.0D, 1.0D);
            editSelected("设置音源音量", item -> item.volume = volume);
        }
    }

    protected enum ElementType {
        SCREEN("▣", "屏幕", "Screen"),
        SUBTITLE("T", "字幕", "Subtitle"),
        AUDIO("♪", "音频", "Audio");

        protected final String symbol;
        protected final String displayName;
        protected final String englishName;

        ElementType(String symbol, String displayName, String englishName) {
            this.symbol = symbol;
            this.displayName = displayName;
            this.englishName = englishName;
        }
    }

    protected static final class PreviewScreenSpec {
        protected final UUID elementId;
        protected final ElementType type;
        protected final String name;
        protected float distance;
        protected float offsetX;
        protected float offsetY;
        protected float height;
        protected float aspect;
        protected float yaw;
        protected float pitch;
        protected float roll;
        protected float scaleX;
        protected float scaleY;
        protected float scaleZ;
        protected float pivotX;
        protected float pivotY;
        protected float pivotZ;
        protected float skewXByY;
        protected float skewYByX;
        protected String contentMode;
        protected String text;
        protected boolean followLyrics;
        protected boolean showTranslation;
        protected float textScale;
        protected int color;
        protected float volume;
        protected int channelIndex;
        protected float maxDistance;
        protected boolean autoMixJoc;
        protected int translationColor;
        protected int backgroundColor;
        protected ControlConsoleElement.Alignment alignment;
        protected float maxWidth;
        protected boolean wrap;
        protected boolean enabled;
        protected boolean locked;

        protected PreviewScreenSpec(ElementType type, String name, float distance, float offsetX, float offsetY, float height,
                float aspect, float roll) {
            this(UUID.randomUUID(), type, name, distance, offsetX, offsetY, height, aspect, roll);
        }

        protected PreviewScreenSpec(UUID elementId, ElementType type, String name, float distance, float offsetX,
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
            this.scaleX = 1.0F;
            this.scaleY = 1.0F;
            this.scaleZ = 1.0F;
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

        protected static PreviewScreenSpec defaults() {
            return defaultsWithName(ElementType.SCREEN, "主屏幕");
        }

        protected static PreviewScreenSpec defaultsWithName(ElementType type, String name) {
            HolographicGlassesItem.ScreenConfig config = HolographicGlassesItem.defaultScreenConfig();
            return new PreviewScreenSpec(type, name, config.distance(), config.offsetX(), config.offsetY(),
                    config.height(), config.aspect(), config.roll());
        }

        protected static PreviewScreenSpec fromSnapshot(ScreenSnapshot value) {
            PreviewScreenSpec screen = new PreviewScreenSpec(value.elementId, value.type, value.name,
                    value.distance, value.offsetX, value.offsetY, value.height, value.aspect, value.roll);
            screen.yaw = value.yaw;
            screen.pitch = value.pitch;
            screen.scaleX = value.scaleX;
            screen.scaleY = value.scaleY;
            screen.scaleZ = value.scaleZ;
            screen.pivotX = value.pivotX;
            screen.pivotY = value.pivotY;
            screen.pivotZ = value.pivotZ;
            screen.skewXByY = value.skewXByY;
            screen.skewYByX = value.skewYByX;
            screen.contentMode = value.contentMode;
            screen.text = value.text;
            screen.followLyrics = value.followLyrics;
            screen.showTranslation = value.showTranslation;
            screen.textScale = value.textScale;
            screen.color = value.color;
            screen.volume = value.volume;
            screen.channelIndex = value.channelIndex;
            screen.maxDistance = value.maxDistance;
            screen.autoMixJoc = value.autoMixJoc;
            screen.translationColor = value.translationColor;
            screen.backgroundColor = value.backgroundColor;
            screen.alignment = value.alignment;
            screen.maxWidth = value.maxWidth;
            screen.wrap = value.wrap;
            screen.enabled = value.enabled;
            screen.locked = value.locked;
            return screen;
        }

        protected PreviewScreenSpec copyWithName(String copyName) {
                    PreviewScreenSpec copy = new PreviewScreenSpec(UUID.randomUUID(), type, copyName, distance, offsetX, offsetY, height, aspect,
                    roll);
            copy.yaw = yaw;
            copy.pitch = pitch;
            copy.scaleX = scaleX;
            copy.scaleY = scaleY;
            copy.scaleZ = scaleZ;
            copy.pivotX = pivotX;
            copy.pivotY = pivotY;
            copy.pivotZ = pivotZ;
            copy.skewXByY = skewXByY;
            copy.skewYByX = skewYByX;
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

        protected static PreviewScreenSpec fromBinding(String fallbackName,
                HolographicGlassesItem.ScreenBinding binding) {
            String sourceName = binding.source() != null ? binding.source().shortName() : fallbackName;
            HolographicGlassesItem.ScreenConfig config = binding.config();
            return new PreviewScreenSpec(ElementType.SCREEN, fallbackName + " / " + sourceName, config.distance(),
                    config.offsetX(), config.offsetY(), config.height(), config.aspect(), config.roll());
        }

        protected HolographicGlassesItem.ScreenConfig toConfig() {
            return new HolographicGlassesItem.ScreenConfig(distance, offsetX, offsetY, height, aspect, roll);
        }
    }
}
