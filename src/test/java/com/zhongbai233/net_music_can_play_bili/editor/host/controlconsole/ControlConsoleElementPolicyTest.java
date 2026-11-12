package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElementLockPolicy;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElementPosition;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleGeometryValidator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Element brightness, edit permissions and transformed spatial bounds. */
class ControlConsoleElementPolicyTest {

    @Test
    void validatesPersistedBrightnessDomain() {
        assertEquals(0.35F, withBrightness(0.35F).brightness());
        assertThrows(IllegalArgumentException.class, () -> withBrightness(-0.01F));
        assertThrows(IllegalArgumentException.class, () -> withBrightness(1.01F));
        assertThrows(IllegalArgumentException.class, () -> withBrightness(Float.NaN));
    }

    private static ControlConsoleElement withBrightness(float brightness) {
        ControlConsoleElement value = ControlConsoleElement.defaultScreen();
        return new ControlConsoleElement(value.elementId(), value.type(), value.name(),
                value.distance(), value.offsetX(), value.offsetY(), value.height(), value.aspect(),
                value.yaw(), value.pitch(), value.roll(), value.contentMode(), value.text(),
                value.followLyrics(), value.showTranslation(), value.textScale(), value.color(),
                value.volume(), value.channelIndex(), value.maxDistance(), value.autoMixJoc(),
                value.translationColor(), value.backgroundColor(), value.alignment(), value.maxWidth(),
                value.wrap(), value.enabled(), value.locked(), value.scaleX(), value.scaleY(), value.scaleZ(),
                value.pivotX(), value.pivotY(), value.pivotZ(), value.skewXByY(), value.skewYByX(), brightness);
    }

    @Test
    void lockedElementMayOnlyBePreservedOrExplicitlyUnlocked() {
        UUID id = UUID.randomUUID();
        ControlConsoleElement locked = element(id, "字幕", "原文", true);
        assertTrue(ControlConsoleElementLockPolicy.permits(List.of(locked), List.of(locked)));
        assertTrue(ControlConsoleElementLockPolicy.permits(List.of(locked),
                List.of(element(id, "字幕", "原文", false))));
        assertFalse(ControlConsoleElementLockPolicy.permits(List.of(locked), List.of()));
        assertFalse(ControlConsoleElementLockPolicy.permits(List.of(locked),
                List.of(element(id, "字幕", "篡改", true))));
        assertTrue(ControlConsoleElementLockPolicy.permits(
                List.of(element(id, "字幕", "原文", false)),
                List.of(element(id, "字幕", "修改允许", false))));
    }

    private static ControlConsoleElement element(UUID id, String name, String text, boolean locked) {
        return new ControlConsoleElement(id, ControlConsoleElement.Type.SUBTITLE, name,
                1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F,
                "FIXED", text, false, false, 1.0F, 0xFFFFFFFF,
                1.0F, 0, 32.0F, false, true, locked);
    }

    @Test
    void resolvesElementTranslationFromConsoleRenderAnchor() {
        ControlConsoleElement element = new ControlConsoleElement(ControlConsoleElement.Type.AUDIO,
                "音频", 7.0F, -2.0F, 3.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F);

        var position = ControlConsoleElementPosition.worldPosition(10, 20, 30, element);

        assertEquals(8.5D, position.x, 1.0e-6D);
        assertEquals(24.55D, position.y, 1.0e-6D);
        assertEquals(37.5D, position.z, 1.0e-6D);
    }

    @Test
    void appliesTheFullElementTransformToTheAudioOrigin() {
        ControlConsoleElement base = new ControlConsoleElement(ControlConsoleElement.Type.AUDIO,
                "音频", 4.0F, 1.0F, 2.0F, 1.0F, 1.0F, 90.0F, 0.0F, 0.0F);
        ControlConsoleElement pivoted = new ControlConsoleElement(base.elementId(), base.type(), base.name(),
                base.distance(), base.offsetX(), base.offsetY(), base.height(), base.aspect(),
                base.yaw(), base.pitch(), base.roll(), base.contentMode(), base.text(), base.followLyrics(),
                base.showTranslation(), base.textScale(), base.color(), base.volume(), base.channelIndex(),
                base.maxDistance(), base.autoMixJoc(), base.translationColor(), base.backgroundColor(),
                base.alignment(), base.maxWidth(), base.wrap(), base.enabled(), base.locked(),
                base.scaleX(), base.scaleY(), base.scaleZ(), 1.0F, 0.0F, 0.0F,
                base.skewXByY(), base.skewYByX());

        var actual = ControlConsoleElementPosition.worldPosition(0, 0, 0, pivoted);
        var expectedLocal = pivoted.editorTransform().matrix().transformPosition(new org.joml.Vector3f());
        assertEquals(0.5D + expectedLocal.x, actual.x, 1.0e-6D);
        assertEquals(1.55D + expectedLocal.y, actual.y, 1.0e-6D);
        assertEquals(0.5D + expectedLocal.z, actual.z, 1.0e-6D);
    }

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
