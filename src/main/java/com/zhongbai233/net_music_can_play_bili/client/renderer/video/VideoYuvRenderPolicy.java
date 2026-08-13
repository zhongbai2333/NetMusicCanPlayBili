package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/** 26.1 feature 分桶与视频深度遮挡的纯策略。 */
final class VideoYuvRenderPolicy {
    static final String NO_DEPTH_WRITE_PROPERTY = VideoPipelineProperties.YUV_NO_DEPTH_WRITE;

    private VideoYuvRenderPolicy() {
    }

    static boolean useSolidFeatureStage() {
        return true;
    }

    static boolean disableDepthWrite() {
        return VideoPipelineProperties.yuv().depthWriteDisabled();
    }
}
