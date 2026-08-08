package com.zhongbai233.net_music_can_play_bili.editor.core.transaction;

import java.util.Objects;
import java.util.function.UnaryOperator;
import com.zhongbai233.net_music_can_play_bili.editor.core.command.CommandStack;
import com.zhongbai233.net_music_can_play_bili.editor.core.command.EditorCommand;

/** 将一次连续鼠标拖动合并为一个可撤销操作。 */
public final class DragTransaction<S> {
    private final S before;
    private S current;
    private final String description;

    public DragTransaction(S before, String description) {
        this.before = Objects.requireNonNull(before, "before");
        this.current = before;
        this.description = Objects.requireNonNull(description, "description");
    }

    public S update(UnaryOperator<S> operation) {
        current = Objects.requireNonNull(operation.apply(current), "operation result");
        return current;
    }

    public boolean changed() {
        return !before.equals(current);
    }

    public S before() {
        return before;
    }

    public S current() {
        return current;
    }

    public String description() {
        return description;
    }

    /** 将整段拖动压缩成一个 undo 步骤；无变化时不写入命令栈。 */
    public S commit(CommandStack<S> stack) {
        Objects.requireNonNull(stack, "stack");
        if (!changed()) {
            return current;
        }
        EditorCommand<S> command = new EditorCommand<>() {
            @Override
            public S apply(S state) {
                return current;
            }

            @Override
            public S undo(S state) {
                return before;
            }

            @Override
            public String description() {
                return DragTransaction.this.description;
            }
        };
        return stack.execute(current, command);
    }
}