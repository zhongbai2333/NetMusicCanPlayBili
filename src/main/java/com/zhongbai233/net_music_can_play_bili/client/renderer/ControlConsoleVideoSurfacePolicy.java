package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;

import java.util.List;

/** Separates console range/audio consumers from actual video-surface demand. */
final class ControlConsoleVideoSurfacePolicy {
    private ControlConsoleVideoSurfacePolicy() {
    }

    static boolean hasEnabledScreen(List<ControlConsoleElement> elements) {
        return elements != null && elements.stream()
                .anyMatch(element -> element.enabled() && element.type() == ControlConsoleElement.Type.SCREEN);
    }
}
