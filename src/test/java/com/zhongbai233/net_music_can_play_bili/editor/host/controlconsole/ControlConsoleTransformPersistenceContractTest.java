package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 纯 Java 测试源集不加载 Minecraft；这里锁定 NBT/网络接线不能漏掉任一 v6 变换分量。 */
class ControlConsoleTransformPersistenceContractTest {
    @Test
    void schemaV6AdvancedTransformIsPresentInNbtAndNetworkBothDirections() throws Exception {
        String blockEntity = Files.readString(Path.of("src/main/java/com/zhongbai233/net_music_can_play_bili/"
                + "blockentity/ControlConsoleBlockEntity.java"));
        String packet = Files.readString(Path.of("src/main/java/com/zhongbai233/net_music_can_play_bili/"
                + "network/ControlConsoleConfigPacket.java"));
        for (String component : new String[] { "ScaleX", "ScaleY", "ScaleZ", "PivotX", "PivotY", "PivotZ",
                "SkewXByY", "SkewYByX" }) {
            String tag = "ELEMENT_" + camelToConstant(component) + "_TAG";
            assertTrue(occurrences(blockEntity, tag) >= 3,
                    () -> "NBT declaration/write/read are incomplete for " + component);
        }
        for (String accessor : new String[] { "scaleX()", "scaleY()", "scaleZ()", "pivotX()", "pivotY()",
                "pivotZ()", "skewXByY()", "skewYByX()" }) {
            assertTrue(packet.contains("element." + accessor), () -> "missing packet write for " + accessor);
        }
        assertTrue(packet.contains("buf.readFloat(), buf.readFloat(), buf.readFloat(),\n"
                + "            buf.readFloat(), buf.readFloat(), buf.readFloat(),\n"
                + "            buf.readFloat(), buf.readFloat()"), "packet read must consume all 8 v6 floats");
    }

    private static String camelToConstant(String value) {
        return value.replaceAll("(?<!^)(?=[A-Z])", "_").toUpperCase(java.util.Locale.ROOT);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }
}
