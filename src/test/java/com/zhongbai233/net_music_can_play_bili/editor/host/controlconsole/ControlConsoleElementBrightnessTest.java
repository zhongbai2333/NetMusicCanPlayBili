package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlConsoleElementBrightnessTest {
    @Test
    void oldElementsDefaultToFullBrightness() {
        assertEquals(ControlConsoleElement.DEFAULT_BRIGHTNESS,
                ControlConsoleElement.defaultScreen().brightness());
    }

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
}
