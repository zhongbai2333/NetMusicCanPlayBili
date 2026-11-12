package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoPipelinePropertiesTest {
    @AfterEach
    void clearProperties() {
        System.clearProperty(VideoPipelineProperties.NETWORK_ERROR_PLACEHOLDER);
        System.clearProperty(VideoPipelineProperties.PIXEL_MODE);
        System.clearProperty(VideoPipelineProperties.FAST_NATIVE_UPLOAD);
        System.clearProperty(VideoPipelineProperties.NV12_PBO);
        System.clearProperty(VideoPipelineProperties.NV12_UV_RG8);
        System.clearProperty(VideoPipelineProperties.YUV_MATRIX);
        System.clearProperty(VideoPipelineProperties.YUV_SHADER_DEBUG);
        System.clearProperty(VideoPipelineProperties.YUV_NO_DEPTH_WRITE);
    }

    @Test
    void uploadAndYuvDefaultsRemainCompatible() {
        VideoPipelineProperties.Upload upload = VideoPipelineProperties.upload();
        assertEquals("normal", upload.pixelMode());
        assertTrue(upload.fastNativeUploadEnabled());
        assertTrue(upload.nv12PboEnabled());
        assertTrue(upload.nv12UvRg8Enabled());

        VideoPipelineProperties.Yuv yuv = VideoPipelineProperties.yuv();
        assertEquals("bt709_limited", yuv.matrix());
        assertEquals("", yuv.shaderDebug());
        assertFalse(yuv.depthWriteDisabled());
    }

    @Test
    void explicitFlagsAreSharedAcrossUploadAndYuvConsumers() {
        System.setProperty(VideoPipelineProperties.FAST_NATIVE_UPLOAD, "false");
        System.setProperty(VideoPipelineProperties.NV12_PBO, "false");
        System.setProperty(VideoPipelineProperties.NV12_UV_RG8, "false");
        System.setProperty(VideoPipelineProperties.YUV_NO_DEPTH_WRITE, "true");

        VideoPipelineProperties.Upload upload = VideoPipelineProperties.upload();
        assertFalse(upload.fastNativeUploadEnabled());
        assertFalse(upload.nv12PboEnabled());
        assertFalse(upload.nv12UvRg8Enabled());
        assertTrue(VideoPipelineProperties.yuv().depthWriteDisabled());
    }

}
