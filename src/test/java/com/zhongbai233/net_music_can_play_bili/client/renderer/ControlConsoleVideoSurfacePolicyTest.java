package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleVideoSurfacePolicyTest {
    @Test
    void onlyAnEnabledScreenCreatesVideoDemand() {
        ControlConsoleElement screen = ControlConsoleElement.defaultScreen();
        ControlConsoleElement disabledScreen = new ControlConsoleElement(
                ControlConsoleElement.Type.SCREEN, "禁用屏幕", 2.2F, 0.0F, 0.05F,
                0.75F, 16.0F / 9.0F, 0.0F, 0.0F, 0.0F,
                "SOURCE", "", false, true, 1.0F, 0xFFFFFFFF,
                1.0F, 0, 32.0F, false, false);
        ControlConsoleElement audio = new ControlConsoleElement(
                ControlConsoleElement.Type.AUDIO, "音频", 1.0F, 0.0F, 0.0F,
                0.5F, 1.0F, 0.0F, 0.0F, 0.0F);

        assertTrue(ControlConsoleVideoSurfacePolicy.hasEnabledScreen(List.of(screen)));
        assertFalse(ControlConsoleVideoSurfacePolicy.hasEnabledScreen(List.of(disabledScreen)));
        assertFalse(ControlConsoleVideoSurfacePolicy.hasEnabledScreen(List.of(audio)));
        assertFalse(ControlConsoleVideoSurfacePolicy.hasEnabledScreen(List.of()));
    }
}
