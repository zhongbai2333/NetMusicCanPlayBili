package com.zhongbai233.net_music_can_play_bili.editor.host;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.scene_editor.minecraft.SceneEditorMinecraftLibrary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Freezes host persistence versions independently of the separately versioned Scene Editor library API. */
class SceneEditorHostSchemaContractTest {
    @Test
    void hostSchemasAreExplicitAndNotDerivedFromLibraryApiVersion() {
        assertEquals(7, ControlConsoleDocument.CURRENT_SCHEMA_VERSION);
        assertEquals(1, HolographicGlassesItem.PERSISTENCE_SCHEMA_VERSION);

        // This intentionally prevents a future library-major bump from being mechanically copied into host data.
        assertNotEquals(SceneEditorMinecraftLibrary.API_MAJOR,
                ControlConsoleDocument.CURRENT_SCHEMA_VERSION);
    }
}
