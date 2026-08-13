package com.zhongbai233.sceneeditor.fixture.b;

import com.zhongbai233.scene_editor.core.scene.SceneDocument;
import net.neoforged.fml.common.Mod;

/** Second independent fixture mod embedding the same public Scene Editor core artifact. */
@Mod(SceneEditorJiJHostB.MOD_ID)
public final class SceneEditorJiJHostB {
    public static final String MOD_ID = "scene_editor_jij_host_b";

    public SceneEditorJiJHostB() {
        SceneDocument.empty();
    }

    public static Class<?> coreType() {
        return SceneDocument.class;
    }
}
