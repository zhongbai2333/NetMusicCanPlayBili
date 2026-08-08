package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

final class IrisVideoRenderTypePolicy {
    static final String FORCE_SOLID_RENDER_TYPE = "ncpb.video.iris.force_solid_render_type";

    private IrisVideoRenderTypePolicy() {
    }

    static boolean isSolidClassificationEnabled() {
        return NcpbSystemProperties.booleanValue(FORCE_SOLID_RENDER_TYPE, false);
    }

    static boolean shouldForceSolidClassification(boolean irisYuvCompatibilityActive) {
        return irisYuvCompatibilityActive && isSolidClassificationEnabled();
    }

    static boolean shouldUseImmediateDraw(boolean irisYuvCompatibilityActive) {
        return irisYuvCompatibilityActive && !isSolidClassificationEnabled();
    }
}