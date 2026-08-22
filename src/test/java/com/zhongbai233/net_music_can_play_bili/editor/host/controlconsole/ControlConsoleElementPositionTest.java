package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElementPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlConsoleElementPositionTest {
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
}
