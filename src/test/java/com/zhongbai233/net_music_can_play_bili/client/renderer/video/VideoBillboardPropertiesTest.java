package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoBillboardPropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        VideoPipelineProperties.Billboard billboard = VideoPipelineProperties.billboard();
        assertTrue(billboard.worldAnchored());
        assertEquals(6.0D, billboard.worldAnchorDistance());
        assertEquals(96.0D * 96.0D, billboard.audioSyncRangeSqr());
        assertEquals("nv12", billboard.renderBackend());
        assertTrue(billboard.yuvUploadPlanes());
        assertEquals(16.0D * 16.0D, billboard.projectorTeleportResetDistanceSqr());

        assertEquals(new VideoPipelineProperties.YuvImmediate(
                "after_level", "camera_relative", "identity", false),
                VideoPipelineProperties.yuvImmediate());
        assertEquals(new VideoPipelineProperties.Visibility(
                0.12D, true, TimeUnit.MILLISECONDS.toNanos(150L), 0.86D, 64.0D),
                VideoPipelineProperties.visibility());
        assertEquals(10_000L, VideoPipelineProperties.chaseWindowMillis());
    }

    @Test
    void explicitValuesRemainConfigurableAndNormalized() {
        set(VideoPipelineProperties.WORLD_ANCHORED, "false");
        set(VideoPipelineProperties.WORLD_ANCHOR_DISTANCE, "8.0");
        set(VideoPipelineProperties.AUDIO_SYNC_RANGE, "128.0");
        set(VideoPipelineProperties.RENDER_BACKEND, "  YUV420_SHADER  ");
        set(VideoPipelineProperties.YUV_UPLOAD_PLANES, "false");
        set(VideoPipelineProperties.PROJECTOR_TELEPORT_RESET_DISTANCE, "24.0");
        set(VideoPipelineProperties.YUV_IMMEDIATE_STAGE, "  AFTER_TRANSLUCENT_BLOCKS  ");
        set(VideoPipelineProperties.YUV_IMMEDIATE_COORDS, "  RELATIVE  ");
        set(VideoPipelineProperties.YUV_IMMEDIATE_POSE, "  EVENT  ");
        set(VideoPipelineProperties.YUV_DEBUG_LOG, "true");
        set(VideoPipelineProperties.VIEW_DOT_THRESHOLD, "0.25");
        set(VideoPipelineProperties.VIEW_OCCLUSION_CHECK, "false");
        set(VideoPipelineProperties.VIEW_OCCLUSION_CACHE_MILLIS, "250");
        set(VideoPipelineProperties.VIEW_SAMPLE_EDGE_SCALE, "0.75");
        set(VideoPipelineProperties.MAX_RENDER_DISTANCE, "96.0");
        set(VideoPipelineProperties.CHASE_WINDOW_MILLIS, "12000");

        VideoPipelineProperties.Billboard billboard = VideoPipelineProperties.billboard();
        assertFalse(billboard.worldAnchored());
        assertEquals(8.0D, billboard.worldAnchorDistance());
        assertEquals(128.0D * 128.0D, billboard.audioSyncRangeSqr());
        assertEquals("yuv420_shader", billboard.renderBackend());
        assertFalse(billboard.yuvUploadPlanes());
        assertEquals(24.0D * 24.0D, billboard.projectorTeleportResetDistanceSqr());
        assertEquals(new VideoPipelineProperties.YuvImmediate(
                "after_translucent_blocks", "relative", "event", true),
                VideoPipelineProperties.yuvImmediate());
        assertEquals(new VideoPipelineProperties.Visibility(
                0.25D, false, TimeUnit.MILLISECONDS.toNanos(250L), 0.75D, 96.0D),
                VideoPipelineProperties.visibility());
        assertEquals(12_000L, VideoPipelineProperties.chaseWindowMillis());
    }

    @Test
    void canonicalOcclusionCacheKeyTakesPriorityOverLegacyFallback() {
        set(VideoPipelineProperties.LEGACY_VIEW_OCCLUSION_CACHE_MILLIS, "300");
        assertEquals(TimeUnit.MILLISECONDS.toNanos(300L),
                VideoPipelineProperties.visibility().occlusionCacheNanos());

        set(VideoPipelineProperties.VIEW_OCCLUSION_CACHE_MILLIS, "200");
        assertEquals(TimeUnit.MILLISECONDS.toNanos(200L),
                VideoPipelineProperties.visibility().occlusionCacheNanos());
    }

    @Test
    void invalidBlankAndNonFiniteValuesUseDefaults() {
        set(VideoPipelineProperties.WORLD_ANCHORED, "yes");
        set(VideoPipelineProperties.WORLD_ANCHOR_DISTANCE, "NaN");
        set(VideoPipelineProperties.AUDIO_SYNC_RANGE, "Infinity");
        set(VideoPipelineProperties.RENDER_BACKEND, "   ");
        set(VideoPipelineProperties.YUV_UPLOAD_PLANES, "yes");
        set(VideoPipelineProperties.PROJECTOR_TELEPORT_RESET_DISTANCE, "invalid");
        set(VideoPipelineProperties.YUV_IMMEDIATE_STAGE, "   ");
        set(VideoPipelineProperties.YUV_IMMEDIATE_COORDS, "   ");
        set(VideoPipelineProperties.YUV_IMMEDIATE_POSE, "   ");
        set(VideoPipelineProperties.YUV_DEBUG_LOG, "yes");
        set(VideoPipelineProperties.VIEW_DOT_THRESHOLD, "NaN");
        set(VideoPipelineProperties.VIEW_OCCLUSION_CHECK, "yes");
        set(VideoPipelineProperties.VIEW_OCCLUSION_CACHE_MILLIS, "invalid");
        set(VideoPipelineProperties.VIEW_SAMPLE_EDGE_SCALE, "Infinity");
        set(VideoPipelineProperties.MAX_RENDER_DISTANCE, "invalid");
        set(VideoPipelineProperties.CHASE_WINDOW_MILLIS, "invalid");

        assertEquals(new VideoPipelineProperties.Billboard(true, 6.0D, 96.0D, "nv12", true, 16.0D),
                VideoPipelineProperties.billboard());
        assertEquals(new VideoPipelineProperties.YuvImmediate(
                "after_level", "camera_relative", "identity", false),
                VideoPipelineProperties.yuvImmediate());
        assertEquals(new VideoPipelineProperties.Visibility(
                0.12D, true, TimeUnit.MILLISECONDS.toNanos(150L), 0.86D, 64.0D),
                VideoPipelineProperties.visibility());
        assertEquals(10_000L, VideoPipelineProperties.chaseWindowMillis());
    }

    @Test
    void unsafeValuesAreClampedAndLargeDistancesSaturate() {
        set(VideoPipelineProperties.WORLD_ANCHOR_DISTANCE, "-1.0");
        set(VideoPipelineProperties.AUDIO_SYNC_RANGE, Double.toString(Double.MAX_VALUE));
        set(VideoPipelineProperties.PROJECTOR_TELEPORT_RESET_DISTANCE, "-1.0");
        set(VideoPipelineProperties.VIEW_DOT_THRESHOLD, "2.0");
        set(VideoPipelineProperties.VIEW_OCCLUSION_CACHE_MILLIS, Long.toString(Long.MAX_VALUE));
        set(VideoPipelineProperties.VIEW_SAMPLE_EDGE_SCALE, "-1.0");
        set(VideoPipelineProperties.MAX_RENDER_DISTANCE, Double.toString(Double.MAX_VALUE));

        VideoPipelineProperties.Billboard billboard = VideoPipelineProperties.billboard();
        assertEquals(0.0D, billboard.worldAnchorDistance());
        assertEquals(Double.MAX_VALUE, billboard.audioSyncRangeSqr());
        assertEquals(0.0D, billboard.projectorTeleportResetDistanceSqr());
        VideoPipelineProperties.Visibility visibility = VideoPipelineProperties.visibility();
        assertEquals(1.0D, visibility.viewDotThreshold());
        assertEquals(Long.MAX_VALUE, visibility.occlusionCacheNanos());
        assertEquals(0.0D, visibility.sampleEdgeScale());
        assertEquals(Double.MAX_VALUE, visibility.maxRenderDistanceSqr());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
