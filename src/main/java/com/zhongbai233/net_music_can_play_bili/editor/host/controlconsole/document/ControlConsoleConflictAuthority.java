package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document;

/** revision 冲突回包携带权威文档时的纯核心不变量。 */
public final class ControlConsoleConflictAuthority {
    private ControlConsoleConflictAuthority() {
    }

    public static void validate(boolean conflict, long revision, ControlConsoleDocument authoritativeDocument) {
        if (conflict) {
            if (authoritativeDocument == null) {
                throw new IllegalArgumentException("conflict result requires the authoritative document");
            }
            if (revision != authoritativeDocument.revision()) {
                throw new IllegalArgumentException("conflict revision must match the authoritative document");
            }
        } else if (authoritativeDocument != null) {
            throw new IllegalArgumentException("only conflict results may carry an authoritative document");
        }
    }
}
