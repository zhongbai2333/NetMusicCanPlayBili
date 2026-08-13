package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 服务端快照提交使用的派生几何安全校验。 */
public final class ControlConsoleGeometryValidator {
    private ControlConsoleGeometryValidator() {
    }

    public static ValidationResult validate(double hardRangeX, double hardRangeY, double hardRangeZ,
            List<ControlConsoleElement> elements) {
        if (!Double.isFinite(hardRangeX) || !Double.isFinite(hardRangeY) || !Double.isFinite(hardRangeZ)
                || hardRangeX <= 0.0D || hardRangeY <= 0.0D || hardRangeZ <= 0.0D) {
            return new ValidationResult(false, -1, "invalid hard range");
        }
        for (int index = 0; index < elements.size(); index++) {
            ControlConsoleElement element = elements.get(index);
            if (element.type() == ControlConsoleElement.Type.AUDIO) {
                if (!inside(element.offsetX(), 1.05D + element.offsetY(), element.distance(),
                        hardRangeX, hardRangeY, hardRangeZ)) {
                    return outside(index, element);
                }
                continue;
            }
            float halfHeight = element.height() * 0.5F;
            float halfWidth = halfHeight * element.aspect();
            if (!Float.isFinite(halfHeight) || halfHeight <= 0.0F
                    || !Float.isFinite(halfWidth) || halfWidth <= 0.0F) {
                return new ValidationResult(false, index,
                        "element derived geometry is not finite: index=" + index + " name=" + element.name());
            }
            Matrix4f transform = new Matrix4f().translation(0.0F, 1.05F, 0.0F)
                    .mul(element.editorTransform().matrix());
            Vector3f corner = new Vector3f();
            for (int ySign : new int[] { -1, 1 }) {
                for (int xSign : new int[] { -1, 1 }) {
                    corner.set(xSign * halfWidth, ySign * halfHeight, 0.0F);
                    transform.transformPosition(corner);
                    if (!inside(corner.x, corner.y, corner.z, hardRangeX, hardRangeY, hardRangeZ)) {
                        return outside(index, element);
                    }
                }
            }
        }
        return ValidationResult.VALID;
    }

    private static boolean inside(double x, double y, double z,
            double halfX, double halfY, double halfZ) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(x) < halfX && Math.abs(y) < halfY && Math.abs(z) < halfZ;
    }

    private static ValidationResult outside(int index, ControlConsoleElement element) {
        return new ValidationResult(false, index,
                "element geometry is outside hard range: index=" + index + " name=" + element.name());
    }

    public record ValidationResult(boolean valid, int elementIndex, String reason) {
        private static final ValidationResult VALID = new ValidationResult(true, -1, "");
    }
}
