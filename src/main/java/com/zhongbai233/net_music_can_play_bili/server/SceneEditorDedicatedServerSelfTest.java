package com.zhongbai233.net_music_can_play_bili.server;

import com.mojang.logging.LogUtils;
import com.zhongbai233.scene_editor.core.math.EditorTransform;
import com.zhongbai233.scene_editor.core.scene.SceneDocument;
import com.zhongbai233.scene_editor.core.scene.SceneElement;
import com.zhongbai233.scene_editor.minecraft.SceneEditorMinecraftLibrary;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
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
            if (!"scene-editor-minecraft".equals(SceneEditorMinecraftLibrary.ARTIFACT)
                    || SceneEditorMinecraftLibrary.API_MAJOR != 1) {
                throw new IllegalStateException("Scene Editor Minecraft library identity mismatch");
            }
            UUID id = UUID.fromString("00000000-0000-0000-0000-000000000008");
            SceneElement element = new ServerElement(id, "server:probe", EditorTransform.identity());
            SceneDocument<SceneElement> document = new SceneDocument<>(List.of(element));
            if (document.element(id).orElseThrow() != element) {
                throw new IllegalStateException("Scene Editor core API failed on dedicated server");
            }
            if (Boolean.getBoolean("ncpb.scene_editor.jij_dedupe_self_test")) {
                verifyJiJDedupe();
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

    private static void verifyJiJDedupe() {
        if (!ModList.get().isLoaded("scene_editor_jij_host_a")
                || !ModList.get().isLoaded("scene_editor_jij_host_b")) {
            throw new IllegalStateException("Both Scene Editor JiJ fixture mods must be loaded");
        }
        try {
            Class<?> hostA = Class.forName("com.zhongbai233.sceneeditor.fixture.a.SceneEditorJiJHostA");
            Class<?> hostB = Class.forName("com.zhongbai233.sceneeditor.fixture.b.SceneEditorJiJHostB");
            Class<?> typeA = (Class<?>) hostA.getMethod("coreType").invoke(null);
            Class<?> typeB = (Class<?>) hostB.getMethod("coreType").invoke(null);
            if (typeA != SceneDocument.class || typeB != SceneDocument.class || typeA != typeB) {
                throw new IllegalStateException("Fixture mods resolved different Scene Editor core class identities");
            }
            String resourceName = "com/zhongbai233/scene_editor/core/scene/SceneDocument.class";
            List<java.net.URL> resources = java.util.Collections.list(
                    SceneDocument.class.getClassLoader().getResources(resourceName));
            if (resources.stream().distinct().count() != 1L) {
                throw new IllegalStateException("Expected one negotiated Scene Editor core resource, got " + resources);
            }
            LOGGER.info("Scene Editor JiJ dedupe passed: hosts=2, sharedType={}, resource={}",
                    SceneDocument.class.getName(), resources.getFirst());
        } catch (ReflectiveOperationException | java.io.IOException error) {
            throw new IllegalStateException("Unable to verify Scene Editor JiJ deduplication", error);
        }
    }

    private record ServerElement(UUID id, String typeId, EditorTransform transform) implements SceneElement {
    }
}
