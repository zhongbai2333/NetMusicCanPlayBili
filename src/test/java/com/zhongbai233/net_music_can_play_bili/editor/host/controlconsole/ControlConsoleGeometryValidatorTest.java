package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleGeometryValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleGeometryValidatorTest {
    @Test
    void rejectsElementsWhoseTransformedGeometryExtendsOutsideHardRange() {
        assertTrue(validate(screen(0.0F, 0.0F, 0.0F, 0.0F, 2.0F, 2.0F)).valid());
        assertFalse(validate(screen(30_000.0F, 0.0F, -40_000.0F, 0.0F, 2.0F, 2.0F)).valid());
    }

    @Test
    void rotationStillRequiresEveryCornerInsideHardRange() {
        ControlConsoleElement rotated = screen(2.3F, 0.0F, 0.0F, 45.0F, 2.0F, 2.0F);
        assertFalse(validate(rotated).valid());
    }

    @Test
    void pitchAndRollUseSameTransformOrderAsRenderer() {
        ControlConsoleElement pitched = new ControlConsoleElement(ControlConsoleElement.Type.SUBTITLE, "字幕",
                0.0F, 0.0F, -1.05F, 2.0F, 2.0F, 0.0F, 45.0F, 45.0F);
        assertTrue(validate(pitched).valid());
    }

    @Test
    void audioCenterMustRemainInsideHardRange() {
        ControlConsoleElement audio = new ControlConsoleElement(ControlConsoleElement.Type.AUDIO, "音源",
                3.01F, 0.0F, -1.05F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F);
        assertFalse(validate(audio).valid());
    }

    @Test
    void nonUniformScalePivotAndSkewParticipateInCornerValidation() {
        ControlConsoleElement transformed = advanced(screen(0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F),
                2.0F, 0.5F, 1.0F, 1.8F, 0.0F, 0.0F, 0.75F, -0.25F);
        assertFalse(ControlConsoleGeometryValidator.validate(2.0D, 2.0D, 2.0D,
                List.of(transformed)).valid());
    }

    private static ControlConsoleGeometryValidator.ValidationResult validate(ControlConsoleElement element) {
        return ControlConsoleGeometryValidator.validate(3.0D, 3.0D, 3.0D, List.of(element));
    }

    private static ControlConsoleElement screen(float x, float y, float z, float yaw, float height, float aspect) {
        return new ControlConsoleElement(ControlConsoleElement.Type.SCREEN, "屏幕", z, x, y - 1.05F,
                height, aspect, yaw, 0.0F, 0.0F);
    }

    private static ControlConsoleElement advanced(ControlConsoleElement source,
            float scaleX, float scaleY, float scaleZ, float pivotX, float pivotY, float pivotZ,
            float skewXByY, float skewYByX) {
        return new ControlConsoleElement(source.elementId(), source.type(), source.name(), source.distance(),
                source.offsetX(), source.offsetY(), source.height(), source.aspect(), source.yaw(), source.pitch(),
                source.roll(), source.contentMode(), source.text(), source.followLyrics(), source.showTranslation(),
                source.textScale(), source.color(), source.volume(), source.channelIndex(), source.maxDistance(),
                source.autoMixJoc(), source.translationColor(), source.backgroundColor(), source.alignment(),
                source.maxWidth(), source.wrap(), source.enabled(), source.locked(), scaleX, scaleY, scaleZ,
                pivotX, pivotY, pivotZ, skewXByY, skewYByX);
    }
}
