package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document;

import org.joml.Vector3d;
import org.joml.Vector3f;

/** Resolves an element origin using the same console anchor and full transform as world rendering. */
public final class ControlConsoleElementPosition {
    public static final double CONSOLE_ELEMENT_BASE_Y = 1.55D;

    private ControlConsoleElementPosition() {
    }

    public static Vector3d worldPosition(int consoleX, int consoleY, int consoleZ, ControlConsoleElement element) {
        Vector3f local = element.editorTransform().matrix().transformPosition(new Vector3f());
        return new Vector3d(consoleX + 0.5D + local.x,
                consoleY + CONSOLE_ELEMENT_BASE_Y + local.y,
                consoleZ + 0.5D + local.z);
    }
}
