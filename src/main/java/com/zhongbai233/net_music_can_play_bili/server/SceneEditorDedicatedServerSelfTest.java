package com.zhongbai233.net_music_can_play_bili.server;

import com.mojang.logging.LogUtils;
import com.zhongbai233.scene_editor.core.math.EditorTransform;
import com.zhongbai233.scene_editor.core.scene.SceneDocument;
import com.zhongbai233.scene_editor.core.scene.SceneElement;
import com.zhongbai233.scene_editor.minecraft.SceneEditorMinecraftLibrary;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/** Dedicated-server smoke test for the common Scene Editor API and Minecraft adapter library boundary. */
public final class SceneEditorDedicatedServerSelfTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean ran;

    private SceneEditorDedicatedServerSelfTest() {
    }

    public static void onServerStarted(ServerStartedEvent event) {
        if (ran || !Boolean.getBoolean("ncpb.scene_editor.server_self_test")) {
            return;
        }
        ran = true;
        try {
            if (FMLEnvironment.getDist() != Dist.DEDICATED_SERVER) {
                throw new IllegalStateException("Scene Editor server smoke test must run on DEDICATED_SERVER");
            }
            if (!"com.github.zhongbai2333.SceneEditor".equals(SceneEditorMinecraftLibrary.GROUP)
                    || !"scene-editor-minecraft".equals(SceneEditorMinecraftLibrary.ARTIFACT)
                    || SceneEditorMinecraftLibrary.API_MAJOR != 1) {
                throw new IllegalStateException("Scene Editor Minecraft library identity mismatch");
            }
            UUID id = UUID.fromString("00000000-0000-0000-0000-000000000008");
            SceneElement element = new ServerElement(id, "server:probe", EditorTransform.identity());
            SceneDocument<SceneElement> document = new SceneDocument<>(List.of(element));
            if (!element.equals(document.element(id).orElseThrow())) {
                throw new IllegalStateException("Scene Editor core API failed on dedicated server");
            }
            LOGGER.info("SceneEditorDedicatedServerSelfTest passed: group={}, artifact={}, apiMajor={}, elements={}",
                    SceneEditorMinecraftLibrary.GROUP, SceneEditorMinecraftLibrary.ARTIFACT,
                    SceneEditorMinecraftLibrary.API_MAJOR, document.elements().size());
        } catch (RuntimeException error) {
            LOGGER.error("SceneEditorDedicatedServerSelfTest failed", error);
            Runtime.getRuntime().halt(1);
            throw error;
        } finally {
            event.getServer().halt(false);
        }
    }

    private record ServerElement(UUID id, String typeId, EditorTransform transform) implements SceneElement {
    }
}
