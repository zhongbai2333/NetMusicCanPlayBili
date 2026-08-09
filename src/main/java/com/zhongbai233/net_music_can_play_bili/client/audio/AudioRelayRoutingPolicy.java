package com.zhongbai233.net_music_can_play_bili.client.audio;

/** 决定公共世界音响（实体音响和中控台音响）是否应对本地玩家静音。 */
final class AudioRelayRoutingPolicy {
    private AudioRelayRoutingPolicy() {
    }

    static boolean muteWorldRelays(boolean headphoneHandlesSource, boolean headphoneSuppressesSource,
            boolean privateOwnerRoute) {
        return headphoneHandlesSource || headphoneSuppressesSource || privateOwnerRoute;
    }
}