package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/** BER 视频表面的可见性准入；控制台无需冒充实体投影仪。 */
final class VideoBerConsumerVisibilityPolicy {
    private VideoBerConsumerVisibilityPolicy() {
    }

    static boolean usesBerSubmission(boolean berManagedProjector, boolean recentlySubmittedByBer) {
        return berManagedProjector || recentlySubmittedByBer;
    }
}