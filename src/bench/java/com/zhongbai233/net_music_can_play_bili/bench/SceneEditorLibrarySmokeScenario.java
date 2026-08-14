package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.camera.StandardCameraView;
import com.zhongbai233.scene_editor.core.math.EditorTransform;
import com.zhongbai233.scene_editor.core.scene.SceneDocument;
import com.zhongbai233.scene_editor.core.scene.SceneElement;
import com.zhongbai233.scene_editor.core.session.EditorSession;
import com.zhongbai233.scene_editor.minecraft.SceneEditorMinecraftLibrary;
import com.zhongbai233.scene_editor.minecraft.gui.MinecraftEditorViewport;
import com.zhongbai233.scene_editor.minecraft.input.MinecraftEditorInput;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.UUID;

final class SceneEditorLibrarySmokeScenario implements BenchClientScenario {
    private boolean verified;

    @Override
    public void setup(BenchClientContext context) {
        verified = false;
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        return context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        if (!"com.github.zhongbai2333.SceneEditor".equals(SceneEditorMinecraftLibrary.GROUP)
                || !"scene-editor-minecraft".equals(SceneEditorMinecraftLibrary.ARTIFACT)
                || sceneEditorApiMajor() != 1) {
            throw new AssertionError("Scene Editor Minecraft adapter identity mismatch");
        }
        var viewport = MinecraftEditorViewport.fullWindow(context.minecraft());
        if (viewport.width() != Math.max(1, context.minecraft().getWindow().getGuiScaledWidth())
                || viewport.height() != Math.max(1, context.minecraft().getWindow().getGuiScaledHeight())) {
            throw new AssertionError("Minecraft viewport adapter did not preserve the scaled client window");
        }
        if (MinecraftEditorInput.standardView(GLFW.GLFW_KEY_1).orElseThrow() != StandardCameraView.FRONT) {
            throw new AssertionError("Minecraft input adapter did not expose the core standard view");
        }

        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000057");
        SceneElement element = new ClientProbeElement(id, "client:probe", EditorTransform.identity());
        SceneDocument<SceneElement> document = new SceneDocument<>(List.of(element));
        EditorCameraState camera = EditorCameraState.lookingAt(EditorCameraMode.ORBIT,
                new Vector3d(0.0D, 0.0D, 5.0D), new Vector3d(), new Vector3d(0.0D, 1.0D, 0.0D),
                60.0F, 4.0F, 0.05F, 100.0F);
        try (EditorSession<SceneElement> session = EditorSession.open(document, camera, viewport, 16)) {
            session.select(id);
            if (!session.selectedElementId().orElseThrow().equals(id)
                    || session.document().element(id).orElseThrow() != element) {
                throw new AssertionError("Scene Editor core session failed on the integrated client");
            }
        }
        verified = true;
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public void verify(BenchClientContext context) {
        if (!verified) {
            throw new AssertionError("Scene Editor client smoke did not execute");
        }
    }

    private static int sceneEditorApiMajor() {
        return SceneEditorMinecraftLibrary.API_MAJOR;
    }

    private record ClientProbeElement(UUID id, String typeId, EditorTransform transform)
            implements SceneElement {
    }
}
