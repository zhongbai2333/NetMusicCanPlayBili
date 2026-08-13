package com.zhongbai233.scene_editor.core.session;

import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.command.StateReplacementCommand;
import com.zhongbai233.scene_editor.core.math.EditorTransform;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import com.zhongbai233.scene_editor.core.scene.SceneDocument;
import com.zhongbai233.scene_editor.core.scene.SceneElement;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorSessionTest {
    @Test
    void ownsSelectionViewportAndHistoryUntilExplicitClose() {
        Element element = new Element(UUID.randomUUID(), "example:screen", EditorTransform.identity());
        SceneDocument<Element> initial = new SceneDocument<>(List.of(element));
        EditorCameraState camera = EditorCameraState.lookingAt(EditorCameraMode.ORBIT,
                new Vector3d(0.0D, 0.0D, 5.0D), new Vector3d(), new Vector3d(0.0D, 1.0D, 0.0D),
                60.0F, 4.0F, 0.05F, 100.0F);
        EditorSession<Element> session = EditorSession.open(initial, camera, new EditorViewport(0, 0, 640, 360), 8);

        session.select(element.id());
        SceneDocument<Element> empty = SceneDocument.empty();
        session.document(session.commands().execute(initial,
                new StateReplacementCommand<>(initial, empty, "clear scene")));
        assertTrue(session.selectedElementId().isEmpty());
        session.document(session.commands().undo(session.document()));
        assertEquals(1, session.document().elements().size());

        session.close();
        assertTrue(session.isClosed());
        assertThrows(IllegalStateException.class, session::document);
    }

    private record Element(UUID id, String typeId, EditorTransform transform) implements SceneElement {
    }
}
