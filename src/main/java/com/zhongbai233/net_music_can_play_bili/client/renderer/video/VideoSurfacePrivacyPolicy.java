package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/** 公共投影面与眼镜私有投影面的隐私路由。 */
public final class VideoSurfacePrivacyPolicy {
    private VideoSurfacePrivacyPolicy() {
    }

    public static boolean hideVideo(boolean privacyActive, SurfaceKind surfaceKind) {
        return privacyActive && surfaceKind != SurfaceKind.PRIVATE_HOLOGRAPHIC;
    }

    public enum SurfaceKind {
        PUBLIC_PROJECTOR,
        CONTROL_CONSOLE,
        PRIVATE_HOLOGRAPHIC
    }
}