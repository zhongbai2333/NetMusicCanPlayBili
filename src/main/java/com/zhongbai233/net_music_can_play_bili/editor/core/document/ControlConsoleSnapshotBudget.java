package com.zhongbai233.net_music_can_play_bili.editor.core.document;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 控制台完整配置快照的编码字节预算，不包含 payload type 外层开销。 */
public final class ControlConsoleSnapshotBudget {
    public static final int MAX_BYTES = 64 * 1024;
    private static final int FIXED_PACKET_BYTES = 8 + 16 + 16 + 8 + 3 * 8;
    // UUID 16 bytes + locked 1 byte are part of every v4 element.
    private static final int FIXED_ELEMENT_BYTES = 8 * 4 + 2 + 3 * 4 + 2 * 4 + 2 + 16 + 1
            + 2 * 4 + 1 + 4 + 1;

    private ControlConsoleSnapshotBudget() {
    }

    public static int encodedBytes(String displayName, List<ControlConsoleElement> elements) {
        long bytes = FIXED_PACKET_BYTES + utfBytes(displayName) + varIntBytes(elements.size());
        for (ControlConsoleElement element : elements) {
            bytes += FIXED_ELEMENT_BYTES;
            bytes += utfBytes(element.type().name());
            bytes += utfBytes(element.name());
            bytes += utfBytes(element.contentMode());
            bytes += utfBytes(element.text());
            if (bytes > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) bytes;
    }

    public static void requireWithinLimit(String displayName, List<ControlConsoleElement> elements) {
        if (encodedBytes(displayName, elements) > MAX_BYTES) {
            throw new IllegalArgumentException("control console snapshot exceeds 64 KiB");
        }
    }

    private static int utfBytes(String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        return varIntBytes(length) + length;
    }

    private static int varIntBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }
}