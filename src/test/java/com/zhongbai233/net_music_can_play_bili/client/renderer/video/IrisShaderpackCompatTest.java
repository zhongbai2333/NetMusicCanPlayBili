package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisShaderpackCompatTest {
    @AfterEach
    void clearProperties() {
        System.clearProperty(IrisVideoRenderTypePolicy.FORCE_SOLID_RENDER_TYPE);
    }

    @Test
    void solidRenderTypeExperimentIsDisabledByDefault() {
        assertFalse(IrisVideoRenderTypePolicy.isSolidClassificationEnabled());
    }

    @Test
    void solidRenderTypeExperimentCanBeEnabledExplicitly() {
        System.setProperty(IrisVideoRenderTypePolicy.FORCE_SOLID_RENDER_TYPE, "true");

        assertTrue(IrisVideoRenderTypePolicy.isSolidClassificationEnabled());
    }

    @Test
    void invalidExperimentValueFallsBackToDisabled() {
        System.setProperty(IrisVideoRenderTypePolicy.FORCE_SOLID_RENDER_TYPE, "yes");

        assertFalse(IrisVideoRenderTypePolicy.isSolidClassificationEnabled());
    }

    @Test
    void activeCompatibilityUsesImmediateDrawByDefault() {
        assertTrue(IrisVideoRenderTypePolicy.shouldUseImmediateDraw(true));
        assertFalse(IrisVideoRenderTypePolicy.shouldForceSolidClassification(true));
    }

    @Test
    void experimentRoutesActiveCompatibilityThroughSolidCustomGeometry() {
        System.setProperty(IrisVideoRenderTypePolicy.FORCE_SOLID_RENDER_TYPE, "true");

        assertFalse(IrisVideoRenderTypePolicy.shouldUseImmediateDraw(true));
        assertTrue(IrisVideoRenderTypePolicy.shouldForceSolidClassification(true));
    }

    @Test
    void inactiveCompatibilityUsesNeitherExperimentalRoute() {
        System.setProperty(IrisVideoRenderTypePolicy.FORCE_SOLID_RENDER_TYPE, "true");

        assertFalse(IrisVideoRenderTypePolicy.shouldUseImmediateDraw(false));
        assertFalse(IrisVideoRenderTypePolicy.shouldForceSolidClassification(false));
    }
}