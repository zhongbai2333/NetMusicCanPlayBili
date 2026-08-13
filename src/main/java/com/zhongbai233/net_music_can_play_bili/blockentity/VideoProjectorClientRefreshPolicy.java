package com.zhongbai233.net_music_can_play_bili.blockentity;

import java.util.Objects;

/** Pure client-sync boundary used when projector block-entity data changes. */
final class VideoProjectorClientRefreshPolicy {
    private VideoProjectorClientRefreshPolicy() {
    }

    static boolean shouldRefresh(Object oldTarget, Object newTarget, int oldQuality, int newQuality) {
        return oldQuality != newQuality || !Objects.equals(oldTarget, newTarget);
    }
}
