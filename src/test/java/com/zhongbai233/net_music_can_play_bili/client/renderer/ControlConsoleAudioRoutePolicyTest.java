package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleAudioRoutePolicyTest {
    @Test
    void unboundConsoleDoesNotOwnAPlaybackRoute() {
        assertFalse(ControlConsoleAudioRoutePolicy.takesOverMainOutput(ControlConsoleDocument.empty()));
    }

    @Test
    void turntableBindingOwnsRouteEvenWithOnlyTheDefaultScreen() {
        ControlConsoleDocument document = new ControlConsoleDocument(
                ControlConsoleDocument.CURRENT_SCHEMA_VERSION, 0L, "中控台", "minecraft:overworld",
                1, 2, 3, ControlConsoleDocument.DEFAULT_HARD_RANGE_X,
                ControlConsoleDocument.DEFAULT_HARD_RANGE_Y, ControlConsoleDocument.DEFAULT_HARD_RANGE_Z);

        assertTrue(ControlConsoleAudioRoutePolicy.takesOverMainOutput(document));
    }
}
