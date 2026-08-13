package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisShaderpackPropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        assertTrue(IrisShaderpackProperties.forceYuvShaderEnabled());
        assertFalse(IrisShaderpackProperties.customYuvShaderDisabled());
        assertTrue(IrisShaderpackProperties.threePlaneYuvAllowed());
        assertTrue(IrisShaderpackProperties.yuvShaderpackBypassEnabled());
        assertEquals("ENTITIES_TRANSLUCENT", IrisShaderpackProperties.yuvProgramName());
        assertEquals("", IrisShaderpackProperties.yuvShaderKeyName());
    }

    @Test
    void explicitValuesRemainDynamicAndNormalized() {
        set(IrisShaderpackProperties.FORCE_YUV_SHADER, "false");
        set(IrisShaderpackProperties.DISABLE_CUSTOM_YUV_SHADER, "true");
        set(IrisShaderpackProperties.ALLOW_THREE_PLANE_YUV, "false");
        set(IrisShaderpackProperties.ENABLE_YUV_SHADERPACK_BYPASS, "false");
        set(IrisShaderpackProperties.YUV_PROGRAM, "  textured  ");
        set(IrisShaderpackProperties.YUV_SHADER_KEY, "  particles_trans  ");

        assertFalse(IrisShaderpackProperties.forceYuvShaderEnabled());
        assertTrue(IrisShaderpackProperties.customYuvShaderDisabled());
        assertFalse(IrisShaderpackProperties.threePlaneYuvAllowed());
        assertFalse(IrisShaderpackProperties.yuvShaderpackBypassEnabled());
        assertEquals("TEXTURED", IrisShaderpackProperties.yuvProgramName());
        assertEquals("PARTICLES_TRANS", IrisShaderpackProperties.yuvShaderKeyName());
    }

    @Test
    void canonicalDisableKeyTakesPriorityOverLegacyKey() {
        set(IrisShaderpackProperties.LEGACY_DISABLE_CUSTOM_YUV_SHADER, "true");
        assertTrue(IrisShaderpackProperties.customYuvShaderDisabled());

        set(IrisShaderpackProperties.DISABLE_CUSTOM_YUV_SHADER, "false");
        assertFalse(IrisShaderpackProperties.customYuvShaderDisabled());
    }

    @Test
    void invalidBooleanValuesUseDefaultsOrValidLegacyFallback() {
        set(IrisShaderpackProperties.FORCE_YUV_SHADER, "yes");
        set(IrisShaderpackProperties.DISABLE_CUSTOM_YUV_SHADER, "invalid");
        set(IrisShaderpackProperties.LEGACY_DISABLE_CUSTOM_YUV_SHADER, "true");
        set(IrisShaderpackProperties.ALLOW_THREE_PLANE_YUV, "1");
        set(IrisShaderpackProperties.ENABLE_YUV_SHADERPACK_BYPASS, "no");

        assertTrue(IrisShaderpackProperties.forceYuvShaderEnabled());
        assertTrue(IrisShaderpackProperties.customYuvShaderDisabled());
        assertTrue(IrisShaderpackProperties.threePlaneYuvAllowed());
        assertTrue(IrisShaderpackProperties.yuvShaderpackBypassEnabled());
    }

    @Test
    void blankNamesUseCompatibilityDefaults() {
        set(IrisShaderpackProperties.YUV_PROGRAM, "   ");
        set(IrisShaderpackProperties.YUV_SHADER_KEY, "   ");

        assertEquals("ENTITIES_TRANSLUCENT", IrisShaderpackProperties.yuvProgramName());
        assertEquals("", IrisShaderpackProperties.yuvShaderKeyName());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
