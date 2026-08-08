package com.zhongbai233.net_music_can_play_bili.editor.core;

import com.zhongbai233.net_music_can_play_bili.editor.core.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.core.document.ControlConsoleElementLockPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleElementLockPolicyTest {
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
}