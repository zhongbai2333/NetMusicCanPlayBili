package com.zhongbai233.sceneeditor.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimalSceneEditorHostTest {
    @Test
    void publishedMavenArtifactSupportsCameraElementPickingAndUndoSession() {
        assertTrue(MinimalSceneEditorHost.verify().valid());
    }
}
