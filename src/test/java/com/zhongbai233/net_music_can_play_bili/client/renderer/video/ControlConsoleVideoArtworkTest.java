package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleVideoStatePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlConsoleVideoArtworkTest {
    @Test
    void nonActiveStatesMapToDedicatedAssetsAndActiveRequiresARealFrame() {
        assertEquals("textures/gui/control_console_video/idle.png",
                ControlConsoleVideoArtwork.texturePath(ControlConsoleVideoStatePolicy.State.IDLE));
        assertEquals("textures/gui/control_console_video/buffering.png",
                ControlConsoleVideoArtwork.texturePath(ControlConsoleVideoStatePolicy.State.BUFFERING));
        assertEquals("textures/gui/control_console_video/error.png",
                ControlConsoleVideoArtwork.texturePath(ControlConsoleVideoStatePolicy.State.ERROR));
        assertThrows(IllegalArgumentException.class,
                () -> ControlConsoleVideoArtwork.texturePath(ControlConsoleVideoStatePolicy.State.ACTIVE));
    }
}
