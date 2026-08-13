package com.zhongbai233.sceneeditor.example;

import com.zhongbai233.scene_editor.core.camera.CameraMatrices;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.command.StateReplacementCommand;
import com.zhongbai233.scene_editor.core.math.EditorTransform;
import com.zhongbai233.scene_editor.core.projection.EditorProjection;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import com.zhongbai233.scene_editor.core.projection.PickingRay;
import com.zhongbai233.scene_editor.core.scene.SceneDocument;
import com.zhongbai233.scene_editor.core.scene.SceneElement;
import com.zhongbai233.scene_editor.core.session.EditorSession;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

/** Media- and Minecraft-independent consumer used to verify the published Maven artifact. */
public final class MinimalSceneEditorHost {
    private MinimalSceneEditorHost() {
    }

    public static VerificationResult verify() {
        ExampleElement element = new ExampleElement(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "example:screen", EditorTransform.identity());
        SceneDocument<ExampleElement> document = new SceneDocument<>(List.of(element));
        EditorViewport viewport = new EditorViewport(0, 0, 1280, 720);
        EditorCameraState camera = EditorCameraState.lookingAt(EditorCameraMode.ORBIT,
                new Vector3d(0.0D, 0.0D, 5.0D), new Vector3d(), new Vector3d(0.0D, 1.0D, 0.0D),
                60.0F, 4.0F, 0.05F, 100.0F);

        try (EditorSession<ExampleElement> session = EditorSession.open(document, camera, viewport, 16)) {
            session.select(element.id());
            CameraMatrices matrices = CameraMatrices.create(session.camera(), session.viewport());
            PickingRay ray = EditorProjection.rayFromScreen(640.0D, 360.0D, matrices, session.viewport());

            SceneDocument<ExampleElement> empty = SceneDocument.empty();
            session.document(session.commands().execute(session.document(),
                    new StateReplacementCommand<>(document, empty, "clear example scene")));
            session.document(session.commands().undo(session.document()));

            return new VerificationResult(session.document().elements().size(), session.selectedElementId().isEmpty(),
                    ray.origin().isFinite() && ray.direction().isFinite());
        }
    }

    public static void main(String[] args) {
        VerificationResult result = verify();
        if (!result.valid()) {
            throw new IllegalStateException("scene-editor Maven host verification failed: " + result);
        }
        System.out.println("scene-editor Maven host verified: " + result);
    }

    public record VerificationResult(int elementCount, boolean selectionCleared, boolean finitePickingRay) {
        public boolean valid() {
            return elementCount == 1 && selectionCleared && finitePickingRay;
        }
    }

    private record ExampleElement(UUID id, String typeId, EditorTransform transform) implements SceneElement {
    }
}
