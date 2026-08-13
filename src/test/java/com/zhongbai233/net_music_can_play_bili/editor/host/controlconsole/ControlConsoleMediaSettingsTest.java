package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleMediaSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlConsoleMediaSettingsTest {
    @Test
    void legacyZeroValueRemainsTheDefault1080p60Quality() {
        assertEquals(116, ControlConsoleMediaSettings.videoQualityCeiling(0));
        assertEquals("1080P60", ControlConsoleMediaSettings.videoQualityLabel(0));
    }

    @Test
    void videoQualityCyclesAcrossAllSupportedPresets() {
        int index = 0;
        for (int i = 0; i < 8; i++) {
            index = ControlConsoleMediaSettings.nextVideoQualityIndex(index);
        }
        assertEquals(0, index);
        assertEquals(127, ControlConsoleMediaSettings.videoQualityCeiling(7));
        assertEquals(0, ControlConsoleMediaSettings.normalizeVideoQualityIndex(-100));
        assertEquals(7, ControlConsoleMediaSettings.normalizeVideoQualityIndex(100));
    }

    @Test
    void audioChannelCyclesFromMuteThroughTheFull714Layout() {
        int channel = -1;
        for (int i = 0; i < 13; i++) {
            channel = ControlConsoleMediaSettings.nextAudioChannel(channel);
        }
        assertEquals(-1, channel);
        assertEquals("静音", ControlConsoleMediaSettings.audioChannelLabel(-1));
        assertEquals("L", ControlConsoleMediaSettings.audioChannelLabel(0));
        assertEquals("Rtr", ControlConsoleMediaSettings.audioChannelLabel(11));
    }
}
