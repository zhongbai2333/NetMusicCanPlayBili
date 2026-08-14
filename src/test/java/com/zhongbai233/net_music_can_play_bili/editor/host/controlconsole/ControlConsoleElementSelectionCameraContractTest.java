package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Minecraft Screen 无法由纯 Java 测试加载；锁定元素选择不得恢复旧建模相机的接线契约。 */
class ControlConsoleElementSelectionCameraContractTest {
    @Test
    void selectingElementPreservesCurrentCamera() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/zhongbai233/net_music_can_play_bili/"
                + "gui/HolographicEditorInputScreen.java"));
        String method = methodBody(source, "protected void selectElement(int index)",
                "protected void clearFlyKeys()");

        assertTrue(method.contains("EditorCameraState cameraBeforeSelection = previewCamera;"));
        assertTrue(method.contains("navigationCamera = cameraBeforeSelection;"));
        assertTrue(method.contains("setPreviewCamera(cameraBeforeSelection);"));
        assertFalse(method.contains("setPreviewCamera(modelingCamera"),
                "selection must not restore a stale modeling camera");
    }

    private static String methodBody(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start, "selection method markers must remain present");
        return source.substring(start, end);
    }
}
