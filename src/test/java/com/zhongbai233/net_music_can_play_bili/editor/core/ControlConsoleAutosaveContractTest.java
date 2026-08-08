package com.zhongbai233.net_music_can_play_bili.editor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zhongbai233.net_music_can_play_bili.editor.core.document.ControlConsoleDocument;
import org.junit.jupiter.api.Test;

/** 记录自动保存采用防抖全量快照，而非高频鼠标操作流的契约。 */
class ControlConsoleAutosaveContractTest {
    @Test
    void documentRevisionIsTheConflictAuthority() {
        ControlConsoleDocument document = ControlConsoleDocument.empty();
        assertEquals(0L, document.revision());
        assertEquals(1L, document.withRevision(1L).revision());
        assertThrows(IllegalArgumentException.class, () -> document.withRevision(-1L));
    }
}