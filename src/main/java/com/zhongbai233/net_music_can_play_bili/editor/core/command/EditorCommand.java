package com.zhongbai233.net_music_can_play_bili.editor.core.command;

/** 对不可变编辑器草稿执行可逆修改的命令。 */
public interface EditorCommand<S> {
    S apply(S state);

    S undo(S state);

    String description();
}