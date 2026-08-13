package com.zhongbai233.scene_editor.core.camera;

import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * 与宿主输入系统无关的编辑器相机导航器。所有操作都返回新状态，不持有鼠标捕获或按键状态。
 */
public final class EditorCameraController {
    private static final double EPSILON = 1.0e-9D;

    private final Settings settings;

    public EditorCameraController() {
        this(Settings.defaults());
    }

    public EditorCameraController(Settings settings) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
    }

    /** 绕 focus 环绕。正 yaw 向相机右侧环绕，正 pitch 向上环绕。 */
    public EditorCameraState orbit(EditorCameraState state, double yawRadians, double pitchRadians,
            Vector3dc worldUp) {
        requireFinite(yawRadians, "yawRadians");
        requireFinite(pitchRadians, "pitchRadians");
        Vector3f up = normalized(worldUp, "worldUp");
        Vector3d focus = state.focus();
        Vector3d offset = state.position().sub(focus);
        double distance = offset.length();
        if (distance <= EPSILON) {
            throw new IllegalArgumentException("camera position and focus must differ");
        }

        Vector3f offsetF = new Vector3f((float) offset.x, (float) offset.y, (float) offset.z);
        new Quaternionf().rotateAxis((float) -yawRadians, up.x, up.y, up.z).transform(offsetF);
        Vector3f forward = new Vector3f(offsetF).negate().normalize();
        Vector3f right = forward.cross(up, new Vector3f());
        if (right.lengthSquared() > 1.0e-8F && pitchRadians != 0.0D) {
            right.normalize();
            Vector3f candidate = new Quaternionf().rotateAxis((float) pitchRadians, right.x, right.y, right.z)
                    .transform(new Vector3f(offsetF));
            Vector3f candidateForward = new Vector3f(candidate).negate().normalize();
            double alignment = Math.abs(candidateForward.dot(up));
            double maximumAlignment = Math.cos(Math.toRadians(settings.minimumPoleAngleDegrees()));
            if (alignment <= maximumAlignment) {
                offsetF.set(candidate);
            }
        }
        offsetF.normalize((float) distance);
        Vector3d position = new Vector3d(focus).add(offsetF.x, offsetF.y, offsetF.z);
        return lookingAtLike(state, position, focus, new Vector3d(up));
    }

    /**
     * 按 GUI 像素平移相机和 focus。语义为抓住场景：鼠标向右拖时场景向右、相机向左。
     */
    public EditorCameraState panPixels(EditorCameraState state, double deltaPixelsX, double deltaPixelsY,
            EditorViewport viewport) {
        requireFinite(deltaPixelsX, "deltaPixelsX");
        requireFinite(deltaPixelsY, "deltaPixelsY");
        java.util.Objects.requireNonNull(viewport, "viewport");
        double unitsPerPixel = worldUnitsPerPixel(state, viewport);
        Vector3f right = state.orientation().transform(new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f up = state.orientation().transform(new Vector3f(0.0F, 1.0F, 0.0F));
        Vector3d translation = new Vector3d(right).mul(-deltaPixelsX * unitsPerPixel)
                .add(new Vector3d(up).mul(deltaPixelsY * unitsPerPixel));
        return state.withPose(state.position().add(translation), state.orientation(), state.focus().add(translation));
    }

    /** 正 wheelSteps 表示拉近；正交相机缩小 orthoScale，透视相机缩短 focus 距离。 */
    public EditorCameraState dolly(EditorCameraState state, double wheelSteps) {
        requireFinite(wheelSteps, "wheelSteps");
        double factor = Math.exp(-settings.dollyExponent() * wheelSteps);
        if (state.mode() == EditorCameraMode.ORTHOGRAPHIC) {
            double scale = clamp(state.orthoScale() * factor, settings.minimumOrthoScale(),
                    settings.maximumOrthoScale());
            return state.withOrthoScale((float) scale);
        }
        Vector3d focus = state.focus();
        Vector3d offset = state.position().sub(focus);
        double distance = offset.length();
        double targetDistance = clamp(distance * factor, settings.minimumDistance(), settings.maximumDistance());
        Vector3d position = new Vector3d(focus).add(offset.mul(targetDistance / distance));
        return state.withPose(position, state.orientation(), focus);
    }

    /** 以局部 X=右、Y=世界上、Z=前的语义移动自由相机。具体键位由宿主管理。 */
    public EditorCameraState fly(EditorCameraState state, Vector3dc localMovement, double deltaSeconds,
            double speedMultiplier, Vector3dc worldUp) {
        java.util.Objects.requireNonNull(localMovement, "localMovement");
        requireFinite(deltaSeconds, "deltaSeconds");
        requireFinite(speedMultiplier, "speedMultiplier");
        if (deltaSeconds < 0.0D || speedMultiplier < 0.0D) {
            throw new IllegalArgumentException("fly timing and speed must be non-negative");
        }
        Vector3f right = state.orientation().transform(new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f forward = state.orientation().transform(new Vector3f(0.0F, 0.0F, -1.0F));
        Vector3f up = normalized(worldUp, "worldUp");
        double amount = settings.flySpeed() * deltaSeconds * speedMultiplier;
        Vector3d translation = new Vector3d(right).mul(localMovement.x() * amount)
                .add(new Vector3d(up).mul(localMovement.y() * amount))
                .add(new Vector3d(forward).mul(localMovement.z() * amount));
        return state.withPose(state.position().add(translation), state.orientation(), state.focus().add(translation));
    }

    /** 将六方向输入转换为局部移动；具体 WASD/QE 键位和按键生命周期由宿主管理。 */
    public EditorCameraState fly(EditorCameraState state, boolean forward, boolean backward, boolean left,
            boolean right, boolean down, boolean up, double deltaSeconds, boolean fast, Vector3dc worldUp) {
        Vector3f movement = new Vector3f(
                (right ? 1.0F : 0.0F) - (left ? 1.0F : 0.0F),
                (up ? 1.0F : 0.0F) - (down ? 1.0F : 0.0F),
                (forward ? 1.0F : 0.0F) - (backward ? 1.0F : 0.0F));
        if (movement.lengthSquared() > 1.0e-8F) {
            movement.normalize();
        }
        double multiplier = fast ? settings.flyBoostMultiplier() : 1.0D;
        return fly(state, new Vector3d(movement), deltaSeconds, multiplier, worldUp);
    }

    /**
     * 第三人称式移动：前后/左右始终投影到世界水平面，升降单独沿 worldUp；相机与 focus 同步平移。
     */
    public EditorCameraState walk(EditorCameraState state, boolean forward, boolean backward, boolean left,
            boolean right, boolean down, boolean up, double deltaSeconds, boolean fast, Vector3dc worldUp) {
        requireFinite(deltaSeconds, "deltaSeconds");
        if (deltaSeconds < 0.0D) {
            throw new IllegalArgumentException("walk timing must be non-negative");
        }
        Vector3f vertical = normalized(worldUp, "worldUp");
        Vector3f cameraForward = state.orientation().transform(new Vector3f(0.0F, 0.0F, -1.0F));
        Vector3f horizontalForward = cameraForward.sub(new Vector3f(vertical)
                .mul(cameraForward.dot(vertical)));
        if (horizontalForward.lengthSquared() <= 1.0e-8F) {
            Vector3d focusDirection = state.focus().sub(state.position());
            horizontalForward.set((float) focusDirection.x, 0.0F, (float) focusDirection.z);
        }
        if (horizontalForward.lengthSquared() <= 1.0e-8F) {
            horizontalForward.set(0.0F, 0.0F, -1.0F);
        }
        horizontalForward.normalize();
        Vector3f horizontalRight = horizontalForward.cross(vertical, new Vector3f()).normalize();
        float forwardInput = (forward ? 1.0F : 0.0F) - (backward ? 1.0F : 0.0F);
        float rightInput = (right ? 1.0F : 0.0F) - (left ? 1.0F : 0.0F);
        float verticalInput = (up ? 1.0F : 0.0F) - (down ? 1.0F : 0.0F);
        Vector3d movement = new Vector3d(horizontalForward).mul(forwardInput)
                .add(new Vector3d(horizontalRight).mul(rightInput))
                .add(new Vector3d(vertical).mul(verticalInput));
        if (movement.lengthSquared() > EPSILON) {
            movement.normalize();
        }
        double multiplier = fast ? settings.flyBoostMultiplier() : 1.0D;
        double amount = settings.flySpeed() * deltaSeconds * multiplier;
        Vector3d translation = movement.mul(amount);
        return state.withPose(state.position().add(translation), state.orientation(),
                state.focus().add(translation));
    }

    /** 聚焦包围球；保持当前观察方向，并为透视或正交投影留出配置的边缘。 */
    public EditorCameraState focus(EditorCameraState state, Vector3dc center, double boundingRadius,
            EditorViewport viewport, Vector3dc worldUp) {
        java.util.Objects.requireNonNull(viewport, "viewport");
        requireFinite(boundingRadius, "boundingRadius");
        if (boundingRadius < 0.0D) {
            throw new IllegalArgumentException("boundingRadius must be non-negative");
        }
        double radius = Math.max(settings.minimumFocusRadius(), boundingRadius) * settings.focusMargin();
        Vector3f backward = state.orientation().transform(new Vector3f(0.0F, 0.0F, 1.0F)).normalize();
        if (state.mode() == EditorCameraMode.ORTHOGRAPHIC) {
            float scale = (float) clamp(radius, settings.minimumOrthoScale(), settings.maximumOrthoScale());
            Vector3d position = new Vector3d(center).add(new Vector3d(backward)
                    .mul(Math.max(settings.minimumDistance(), state.position().distance(state.focus()))));
            return lookingAtLike(state.withOrthoScale(scale), position, center, worldUp);
        }
        double verticalHalfFov = Math.toRadians(state.fovDegrees()) * 0.5D;
        double horizontalHalfFov = Math.atan(Math.tan(verticalHalfFov) * viewport.aspectRatio());
        double limitingHalfFov = Math.min(verticalHalfFov, horizontalHalfFov);
        double distance = clamp(radius / Math.sin(limitingHalfFov), settings.minimumDistance(),
                settings.maximumDistance());
        Vector3d position = new Vector3d(center).add(new Vector3d(backward).mul(distance));
        return lookingAtLike(state, position, center, worldUp);
    }

    /** 切到围绕当前 focus 的标准轴向视图。 */
    public EditorCameraState standardView(EditorCameraState state, StandardCameraView view, Vector3dc worldUp) {
        java.util.Objects.requireNonNull(view, "view");
        Vector3d backward = switch (view) {
            case FRONT -> new Vector3d(0.0D, 0.0D, 1.0D);
            case BACK -> new Vector3d(0.0D, 0.0D, -1.0D);
            case LEFT -> new Vector3d(-1.0D, 0.0D, 0.0D);
            case RIGHT -> new Vector3d(1.0D, 0.0D, 0.0D);
            case TOP -> new Vector3d(0.0D, 1.0D, 0.0D);
            case BOTTOM -> new Vector3d(0.0D, -1.0D, 0.0D);
        };
        Vector3d focus = state.focus();
        double distance = Math.max(settings.minimumDistance(), state.position().distance(focus));
        Vector3d position = new Vector3d(focus).add(backward.mul(distance));
        Vector3dc effectiveUp = (view == StandardCameraView.TOP || view == StandardCameraView.BOTTOM)
                ? new Vector3d(0.0D, 0.0D, view == StandardCameraView.TOP ? -1.0D : 1.0D)
                : worldUp;
        return lookingAtLike(state, position, focus, effectiveUp);
    }

    /**
     * 在透视与正交投影之间切换，并保持 focus 深度处的垂直取景范围不变。
     */
    public EditorCameraState switchProjection(EditorCameraState state, EditorCameraMode targetMode) {
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(targetMode, "targetMode");
        if (targetMode != EditorCameraMode.ORBIT && targetMode != EditorCameraMode.ORTHOGRAPHIC) {
            throw new IllegalArgumentException("targetMode must be ORBIT or ORTHOGRAPHIC");
        }
        if (state.mode() == targetMode) {
            return state;
        }
        double halfFov = Math.toRadians(state.fovDegrees()) * 0.5D;
        if (targetMode == EditorCameraMode.ORTHOGRAPHIC) {
            double distance = state.position().distance(state.focus());
            double scale = clamp(distance * Math.tan(halfFov), settings.minimumOrthoScale(),
                    settings.maximumOrthoScale());
            return state.withOrthoScale((float) scale).withMode(EditorCameraMode.ORTHOGRAPHIC);
        }
        Vector3f backward = state.orientation().transform(new Vector3f(0.0F, 0.0F, 1.0F));
        double distance = clamp(state.orthoScale() / Math.tan(halfFov), settings.minimumDistance(),
                settings.maximumDistance());
        Vector3d focus = state.focus();
        Vector3d position = new Vector3d(focus).add(new Vector3d(backward).mul(distance));
        return state.withPose(position, state.orientation(), focus).withMode(EditorCameraMode.ORBIT);
    }

    private static double worldUnitsPerPixel(EditorCameraState state, EditorViewport viewport) {
        if (state.mode() == EditorCameraMode.ORTHOGRAPHIC) {
            return 2.0D * state.orthoScale() / viewport.height();
        }
        double distance = state.position().distance(state.focus());
        return 2.0D * distance * Math.tan(Math.toRadians(state.fovDegrees()) * 0.5D) / viewport.height();
    }

    private static EditorCameraState lookingAtLike(EditorCameraState source, Vector3dc position, Vector3dc focus,
            Vector3dc worldUp) {
        return EditorCameraState.lookingAt(source.mode(), position, focus, worldUp, source.fovDegrees(),
                source.orthoScale(), source.nearPlane(), source.farPlane());
    }

    private static Vector3f normalized(Vector3dc value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y()) || !Double.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        Vector3f result = new Vector3f((float) value.x(), (float) value.y(), (float) value.z());
        if (result.lengthSquared() <= 1.0e-8F) {
            throw new IllegalArgumentException(name + " must be non-zero");
        }
        return result.normalize();
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Settings(double dollyExponent, double minimumDistance, double maximumDistance,
            double minimumOrthoScale, double maximumOrthoScale, double flySpeed, double minimumPoleAngleDegrees,
            double minimumFocusRadius, double focusMargin, double flyBoostMultiplier) {
        public Settings(double dollyExponent, double minimumDistance, double maximumDistance,
            double minimumOrthoScale, double maximumOrthoScale, double flySpeed,
            double minimumPoleAngleDegrees, double minimumFocusRadius, double focusMargin) {
            this(dollyExponent, minimumDistance, maximumDistance, minimumOrthoScale, maximumOrthoScale, flySpeed,
                minimumPoleAngleDegrees, minimumFocusRadius, focusMargin, 4.0D);
        }

        public Settings {
            if (!positiveFinite(dollyExponent) || !positiveFinite(minimumDistance)
                    || !positiveFinite(maximumDistance) || maximumDistance < minimumDistance
                    || !positiveFinite(minimumOrthoScale) || !positiveFinite(maximumOrthoScale)
                    || maximumOrthoScale < minimumOrthoScale || !positiveFinite(flySpeed)
                    || !Double.isFinite(minimumPoleAngleDegrees) || minimumPoleAngleDegrees <= 0.0D
                    || minimumPoleAngleDegrees >= 45.0D || !positiveFinite(minimumFocusRadius)
                    || !Double.isFinite(focusMargin) || focusMargin < 1.0D || !positiveFinite(flyBoostMultiplier)) {
                throw new IllegalArgumentException("invalid camera controller settings");
            }
        }

        public static Settings defaults() {
            return new Settings(0.18D, 0.05D, 4096.0D, 0.01D, 4096.0D, 8.0D, 2.0D, 0.05D, 1.15D,
                    4.0D);
        }

        private static boolean positiveFinite(double value) {
            return Double.isFinite(value) && value > 0.0D;
        }
    }
}