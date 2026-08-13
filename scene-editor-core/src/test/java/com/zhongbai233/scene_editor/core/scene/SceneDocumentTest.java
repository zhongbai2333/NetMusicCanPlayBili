package com.zhongbai233.scene_editor.core.scene;

import com.zhongbai233.scene_editor.core.math.EditorTransform;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SceneDocumentTest {
    @Test
    void rejectsDuplicateStableIdsAndKeepsHostSchemaOutOfTheModel() {
        UUID id = UUID.randomUUID();
        TestElement first = new TestElement(id, "example:panel", EditorTransform.identity());
        assertThrows(IllegalArgumentException.class, () -> new SceneDocument<>(List.of(first, first)));
        assertEquals(first, new SceneDocument<>(List.of(first)).element(id).orElseThrow());
    }

    private record TestElement(UUID id, String typeId, EditorTransform transform) implements SceneElement {
    }
}
