package com.zhongbai233.net_music_can_play_bili.editor.core.document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 服务端整快照替换时保护已有锁定元素不被修改或删除。 */
public final class ControlConsoleElementLockPolicy {
    private ControlConsoleElementLockPolicy() {
    }

    public static boolean permits(List<ControlConsoleElement> previous,
            List<ControlConsoleElement> replacement) {
        Map<UUID, ControlConsoleElement> nextById = new HashMap<>();
        for (ControlConsoleElement element : replacement) {
            nextById.put(element.elementId(), element);
        }
        for (ControlConsoleElement old : previous) {
            if (!old.locked()) continue;
            ControlConsoleElement next = nextById.get(old.elementId());
            if (next == null || !sameExceptLock(old, next)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameExceptLock(ControlConsoleElement left, ControlConsoleElement right) {
        return left.equals(right) || left.equals(new ControlConsoleElement(
                right.elementId(), right.type(), right.name(), right.distance(), right.offsetX(), right.offsetY(),
                right.height(), right.aspect(), right.yaw(), right.pitch(), right.roll(), right.contentMode(),
                right.text(), right.followLyrics(), right.showTranslation(), right.textScale(), right.color(),
                right.volume(), right.channelIndex(), right.maxDistance(), right.autoMixJoc(), right.enabled(),
                left.locked()));
    }
}