package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleSnapshotBudget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleSnapshotBudgetTest {
    @Test
    void acceptsSmallSnapshotAndRejectsLargeUtf8Payload() {
        ControlConsoleElement small = element("字幕", "欢迎");
        assertDoesNotThrow(() -> ControlConsoleSnapshotBudget.requireWithinLimit("中控台", List.of(small)));

        List<ControlConsoleElement> large = new ArrayList<>();
        String text = "字".repeat(4096);
        for (int i = 0; i < 8; i++) {
            large.add(element("字幕" + i, text));
        }
        assertTrue(ControlConsoleSnapshotBudget.encodedBytes("中控台", large)
                > ControlConsoleSnapshotBudget.MAX_BYTES);
        assertThrows(IllegalArgumentException.class,
                () -> ControlConsoleSnapshotBudget.requireWithinLimit("中控台", large));
    }

    private static ControlConsoleElement element(String name, String text) {
        return new ControlConsoleElement(ControlConsoleElement.Type.SUBTITLE, name,
                2.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F,
                "FIXED", text, false, false, 1.0F, 0xFFFFFFFF,
                1.0F, 0, 32.0F, false, true);
    }
}