package com.zhongbai233.net_music_can_play_bili.editor.core.command;

import java.util.ArrayDeque;
import java.util.Deque;

/** 有容量上限的通用撤销/重做栈。 */
public final class CommandStack<S> {
    private final int capacity;
    private final Deque<EditorCommand<S>> undo = new ArrayDeque<>();
    private final Deque<EditorCommand<S>> redo = new ArrayDeque<>();

    public CommandStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public S execute(S state, EditorCommand<S> command) {
        S result = java.util.Objects.requireNonNull(command, "command").apply(state);
        undo.addLast(command);
        while (undo.size() > capacity) {
            undo.removeFirst();
        }
        redo.clear();
        return result;
    }

    public S undo(S state) {
        if (undo.isEmpty()) {
            return state;
        }
        EditorCommand<S> command = undo.removeLast();
        S result = command.undo(state);
        redo.addLast(command);
        return result;
    }

    public S redo(S state) {
        if (redo.isEmpty()) {
            return state;
        }
        EditorCommand<S> command = redo.removeLast();
        S result = command.apply(state);
        undo.addLast(command);
        return result;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void clear() {
        undo.clear();
        redo.clear();
    }
}