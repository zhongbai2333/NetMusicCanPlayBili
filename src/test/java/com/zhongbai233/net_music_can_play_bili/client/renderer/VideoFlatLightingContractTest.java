package com.zhongbai233.net_music_can_play_bili.client.renderer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks video surfaces to intrinsic brightness instead of orientation-dependent entity lighting. */
class VideoFlatLightingContractTest {
    @Test
    void rgbaAndYuvPipelinesDisableDirectionalLighting() throws Exception {
        String renderTypes = Files.readString(Path.of("src/main/java/com/zhongbai233/net_music_can_play_bili/"
                + "client/renderer/video/YuvVideoRenderTypes.java"));
        String geometry = Files.readString(Path.of("src/main/java/com/zhongbai233/net_music_can_play_bili/"
                + "client/renderer/video/VideoBillboardGeometrySupport.java"));

        assertTrue(occurrences(renderTypes, "NO_CARDINAL_LIGHTING") >= 3,
                "RGBA, YUV and single-sampler fallback pipelines must all be flat-lit");
        assertTrue(renderTypes.contains("RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)"));
        assertTrue(renderTypes.contains(".useLightmap()"),
                "RGBA stays non-emissive and uses the real full-bright lightmap");
        assertFalse(geometry.contains("RenderTypes.itemCutout(texture)"));
        assertFalse(geometry.contains("RenderTypes.itemTranslucent(texture)"));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }
}
