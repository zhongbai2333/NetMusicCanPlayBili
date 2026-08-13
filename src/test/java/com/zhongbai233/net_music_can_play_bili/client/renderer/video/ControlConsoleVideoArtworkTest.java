package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleVideoStatePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void loadingProgressIsRenderedOnlyWhileBuffering() {
        assertTrue(ControlConsoleVideoArtwork.loadingProgressOverlay(ControlConsoleVideoStatePolicy.State.BUFFERING));
        assertFalse(ControlConsoleVideoArtwork.loadingProgressOverlay(ControlConsoleVideoStatePolicy.State.IDLE));
        assertFalse(ControlConsoleVideoArtwork.loadingProgressOverlay(ControlConsoleVideoStatePolicy.State.ERROR));
        assertFalse(ControlConsoleVideoArtwork.loadingProgressOverlay(ControlConsoleVideoStatePolicy.State.ACTIVE));
    }
}
