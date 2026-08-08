package com.zhongbai233.net_music_can_play_bili.editor.core.document;

import java.util.List;

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
            float halfHeight = element.height() * 0.5F;
            float halfWidth = halfHeight * element.aspect();
            if (!Float.isFinite(halfHeight) || halfHeight <= 0.0F
                    || !Float.isFinite(halfWidth) || halfWidth <= 0.0F) {
                return new ValidationResult(false, index,
                        "element derived geometry is not finite: index=" + index + " name=" + element.name());
            }
        }
        return ValidationResult.VALID;
    }

    public record ValidationResult(boolean valid, int elementIndex, String reason) {
        private static final ValidationResult VALID = new ValidationResult(true, -1, "");
    }
}