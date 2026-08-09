package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/** 26.1 feature 分桶与视频深度遮挡的纯策略。 */
final class VideoYuvRenderPolicy {
    static final String NO_DEPTH_WRITE_PROPERTY = "ncpb.video.yuv.no_depth_write";

    private VideoYuvRenderPolicy() {
    }

    static boolean useSolidFeatureStage() {
        return true;
    }

    static boolean disableDepthWrite() {
        return Boolean.parseBoolean(System.getProperty(NO_DEPTH_WRITE_PROPERTY, "false"));
    }
}