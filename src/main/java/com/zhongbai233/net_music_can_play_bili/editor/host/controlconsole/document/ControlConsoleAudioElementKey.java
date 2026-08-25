package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document;

import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.UUID;

/** Stable synthetic key shared by the server zone snapshot and client virtual relay. */
public final class ControlConsoleAudioElementKey {
    private ControlConsoleAudioElementKey() {
    }

    public static BlockPos of(BlockPos consolePos, ControlConsoleElement element) {
        Objects.requireNonNull(element, "element");
        return of(consolePos, element.elementId());
    }

    public static BlockPos of(BlockPos consolePos, UUID elementId) {
        Objects.requireNonNull(consolePos, "consolePos");
        Objects.requireNonNull(elementId, "elementId");
        long identity = elementId.getMostSignificantBits() ^ elementId.getLeastSignificantBits();
        return new BlockPos(consolePos.getX() ^ (int) (identity >>> 32), consolePos.getY(),
                consolePos.getZ() ^ (int) identity);
    }
}
