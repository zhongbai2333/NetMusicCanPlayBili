package com.zhongbai233.scene_editor.core;

import com.zhongbai233.scene_editor.core.command.CommandStack;
import com.zhongbai233.scene_editor.core.command.StateReplacementCommand;
import com.zhongbai233.scene_editor.core.command.EditorCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandStackTest {
    @Test
    void replacementCommandsCoverValueAddDeleteAndCopyAsOneStepEach() {
        CommandStack<List<String>> stack = new CommandStack<>(8);
        List<String> state = List.of("screen");
        List<String> renamed = List.of("main-screen");
        state = stack.execute(state, new StateReplacementCommand<>(state, renamed, "数值/名称修改"));
        List<String> added = List.of("main-screen", "subtitle");
        state = stack.execute(state, new StateReplacementCommand<>(state, added, "添加"));
        List<String> copied = List.of("main-screen", "subtitle", "subtitle-copy");
        state = stack.execute(state, new StateReplacementCommand<>(state, copied, "复制"));
        List<String> deleted = List.of("main-screen", "subtitle-copy");
        state = stack.execute(state, new StateReplacementCommand<>(state, deleted, "删除"));

        assertEquals(copied, stack.undo(state));
        assertEquals(deleted, stack.redo(copied));
    }
    @Test
    void executesUndoesAndRedoesCommands() {
        CommandStack<Integer> stack = new CommandStack<>(8);
        int value = stack.execute(1, add(3));
        assertEquals(4, value);
        assertTrue(stack.canUndo());

        value = stack.undo(value);
        assertEquals(1, value);
        assertTrue(stack.canRedo());

        value = stack.redo(value);
        assertEquals(4, value);
    }

    @Test
    void executingAfterUndoClearsRedoHistory() {
        CommandStack<Integer> stack = new CommandStack<>(8);
        int value = stack.execute(0, add(1));
        value = stack.undo(value);
        value = stack.execute(value, add(2));
        assertEquals(2, value);
        assertFalse(stack.canRedo());
    }

    private static EditorCommand<Integer> add(int delta) {
        return new EditorCommand<>() {
            @Override
            public Integer apply(Integer state) {
                return state + delta;
            }

            @Override
            public Integer undo(Integer state) {
                return state - delta;
            }

            @Override
            public String description() {
                return "add " + delta;
            }
        };
    }
}
