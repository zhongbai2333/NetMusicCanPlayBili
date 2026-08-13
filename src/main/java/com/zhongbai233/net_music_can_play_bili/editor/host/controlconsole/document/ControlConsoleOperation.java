package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document;

import java.util.Objects;

/** 可提交到权威控制台文档服务的操作。 */
public sealed interface ControlConsoleOperation permits ControlConsoleOperation.ReplaceDocument {
    /** 仅当服务端 revision 等于 expectedRevision 时，用 draft 替换业务文档。 */
    record ReplaceDocument(long expectedRevision, ControlConsoleDocument draft)
            implements ControlConsoleOperation {
        public ReplaceDocument {
            if (expectedRevision < 0L) {
                throw new IllegalArgumentException("expectedRevision must not be negative");
            }
            Objects.requireNonNull(draft, "draft");
            if (draft.revision() != expectedRevision) {
                throw new IllegalArgumentException("draft revision must match expectedRevision");
            }
        }
    }
}