package com.zhongbai233.scene_editor.core.command;

/** 将一个不可变编辑状态替换为另一个状态的通用可逆命令。 */
public record StateReplacementCommand<S>(S before, S after, String description) implements EditorCommand<S> {
    public StateReplacementCommand {
        java.util.Objects.requireNonNull(before, "before");
        java.util.Objects.requireNonNull(after, "after");
        description = java.util.Objects.requireNonNull(description, "description");
    }

    @Override
    public S apply(S ignored) {
        return after;
    }

    @Override
    public S undo(S ignored) {
        return before;
    }
}
