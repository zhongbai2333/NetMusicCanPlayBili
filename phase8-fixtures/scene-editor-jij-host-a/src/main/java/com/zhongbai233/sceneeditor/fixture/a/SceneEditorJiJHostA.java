package com.zhongbai233.sceneeditor.fixture.a;

import com.zhongbai233.scene_editor.core.scene.SceneDocument;
import net.neoforged.fml.common.Mod;

/** First independent fixture mod embedding the public Scene Editor core artifact. */
@Mod(SceneEditorJiJHostA.MOD_ID)
public final class SceneEditorJiJHostA {
    public static final String MOD_ID = "scene_editor_jij_host_a";

    public SceneEditorJiJHostA() {
        SceneDocument.empty();
    }

    public static Class<?> coreType() {
        return SceneDocument.class;
    }
}
