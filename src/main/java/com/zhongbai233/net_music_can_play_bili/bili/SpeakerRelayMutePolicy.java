package com.zhongbai233.net_music_can_play_bili.bili;

/** Decides whether connected speaker relays take over the turntable output. */
final class SpeakerRelayMutePolicy {
    private SpeakerRelayMutePolicy() {
    }

    static boolean shouldMuteMain(boolean enabled, int registeredRelayCount) {
        return enabled && registeredRelayCount > 0;
    }
}