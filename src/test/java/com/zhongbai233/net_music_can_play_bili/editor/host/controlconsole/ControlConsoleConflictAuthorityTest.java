package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleConflictAuthority;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlConsoleConflictAuthorityTest {
    @Test
    void conflictRequiresExactAuthoritativeRevision() {
        ControlConsoleDocument authoritative = ControlConsoleDocument.empty().withRevision(8L);

        assertDoesNotThrow(() -> ControlConsoleConflictAuthority.validate(true, 8L, authoritative));
        assertThrows(IllegalArgumentException.class,
                () -> ControlConsoleConflictAuthority.validate(true, 8L, null));
        assertThrows(IllegalArgumentException.class,
                () -> ControlConsoleConflictAuthority.validate(true, 7L, authoritative));
    }

    @Test
    void ordinaryResultsStayCompact() {
        assertDoesNotThrow(() -> ControlConsoleConflictAuthority.validate(false, 3L, null));
        assertThrows(IllegalArgumentException.class, () -> ControlConsoleConflictAuthority.validate(false, 3L,
                ControlConsoleDocument.empty().withRevision(3L)));
    }
}
