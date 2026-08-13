package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPreviewTextureLifecycleTest {
    @Test
    void replacementDisposesOnlyThePreviousResourceInThatSlot() {
        List<String> disposed = new ArrayList<>();
        LegacyPreviewTextureLifecycle<String, String, String> textures = lifecycle(disposed);
        textures.replaceRgba("rgba-1");
        textures.replaceYuv("yuv");

        textures.replaceRgba("rgba-2");

        assertEquals(List.of("rgba:rgba-1"), disposed);
        assertSame("rgba-2", textures.rgba());
        assertSame("yuv", textures.yuv());
    }

    @Test
    void replacingAResourceWithItselfDoesNotDisposeIt() {
        List<String> disposed = new ArrayList<>();
        LegacyPreviewTextureLifecycle<Object, Object, Object> textures = new LegacyPreviewTextureLifecycle<>(
                ignored -> disposed.add("rgba"), ignored -> disposed.add("yuv"), ignored -> disposed.add("packed"));
        Object rgba = new Object();
        textures.replaceRgba(rgba);

        textures.replaceRgba(rgba);

        assertTrue(disposed.isEmpty());
        assertSame(rgba, textures.rgba());
    }

    @Test
    void nullReplacementClearsAndDisposesOneSlot() {
        List<String> disposed = new ArrayList<>();
        LegacyPreviewTextureLifecycle<String, String, String> textures = lifecycle(disposed);
        textures.replacePacked("bench");

        textures.replacePacked(null);

        assertEquals(List.of("packed:bench"), disposed);
        assertNull(textures.packed());
        assertFalse(textures.hasRgbaOrPacked());
    }

    @Test
    void clearDisposesEveryOwnedResourceExactlyOnceAndIsIdempotent() {
        List<String> disposed = new ArrayList<>();
        LegacyPreviewTextureLifecycle<String, String, String> textures = lifecycle(disposed);
        textures.replaceRgba("rgba");
        textures.replaceYuv("yuv");
        textures.replacePacked("packed");

        textures.clear();
        textures.clear();

        assertEquals(List.of("rgba:rgba", "yuv:yuv", "packed:packed"), disposed);
        assertFalse(textures.hasRgbaOrPacked());
        assertFalse(textures.hasYuv());
    }

    private static LegacyPreviewTextureLifecycle<String, String, String> lifecycle(List<String> disposed) {
        return new LegacyPreviewTextureLifecycle<>(value -> disposed.add("rgba:" + value),
                value -> disposed.add("yuv:" + value), value -> disposed.add("packed:" + value));
    }
}
