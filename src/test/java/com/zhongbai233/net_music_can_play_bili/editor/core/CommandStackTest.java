package com.zhongbai233.net_music_can_play_bili.editor.core;

import com.zhongbai233.net_music_can_play_bili.editor.core.command.CommandStack;
import com.zhongbai233.net_music_can_play_bili.editor.core.command.EditorCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandStackTest {
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