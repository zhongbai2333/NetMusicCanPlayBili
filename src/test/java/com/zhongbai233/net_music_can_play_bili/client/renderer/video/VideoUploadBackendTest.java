package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VideoUploadBackendTest {
    @Test
    void onlyActualOpenGlDeviceCanUsePbo() {
        assertTrue(VideoUploadBackend.fromName("OpenGL").supportsPbo());
        assertFalse(VideoUploadBackend.fromName("Vulkan").supportsPbo());
        assertFalse(VideoUploadBackend.fromName("unknown").supportsPbo());
        assertFalse(VideoUploadBackend.fromName(null).supportsPbo());
    }
}
