package com.zhongbai233.net_music_can_play_bili.editor.core;

import com.zhongbai233.net_music_can_play_bili.editor.core.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.core.document.ControlConsoleGeometryValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleGeometryValidatorTest {
    @Test
    void elementsMayExtendOutsideHardRange() {
        assertTrue(validate(screen(0.0F, 0.0F, 0.0F, 0.0F, 2.0F, 2.0F)).valid());
        assertTrue(validate(screen(30_000.0F, 0.0F, -40_000.0F, 0.0F, 2.0F, 2.0F)).valid());
    }

    @Test
    void unrestrictedRotationDoesNotDependOnHardRange() {
        ControlConsoleElement rotated = screen(2.3F, 0.0F, 0.0F, 45.0F, 2.0F, 2.0F);
        assertTrue(validate(rotated).valid());
    }

    @Test
    void pitchAndRollUseSameTransformOrderAsRenderer() {
        ControlConsoleElement pitched = new ControlConsoleElement(ControlConsoleElement.Type.SUBTITLE, "字幕",
                2.7F, 0.0F, -1.05F, 2.0F, 2.0F, 0.0F, 45.0F, 45.0F);
        assertTrue(validate(pitched).valid());
    }

    @Test
    void audioMayBePlacedOutsideHardRange() {
        ControlConsoleElement audio = new ControlConsoleElement(ControlConsoleElement.Type.AUDIO, "音源",
                3.01F, 0.0F, -1.05F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F);
        assertTrue(validate(audio).valid());
    }

    private static ControlConsoleGeometryValidator.ValidationResult validate(ControlConsoleElement element) {
        return ControlConsoleGeometryValidator.validate(3.0D, 3.0D, 3.0D, List.of(element));
    }

    private static ControlConsoleElement screen(float x, float y, float z, float yaw, float height, float aspect) {
        return new ControlConsoleElement(ControlConsoleElement.Type.SCREEN, "屏幕", z, x, y - 1.05F,
                height, aspect, yaw, 0.0F, 0.0F);
    }
}