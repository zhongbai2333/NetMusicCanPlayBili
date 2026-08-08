package com.zhongbai233.net_music_can_play_bili.bili;

import java.util.Collection;

/** Decides whether connected speaker relays take over the turntable output. */
final class SpeakerRelayMutePolicy {
    private SpeakerRelayMutePolicy() {
    }

    static boolean shouldMuteMain(boolean enabled, int registeredRelayCount, boolean privateHeadphoneRoute) {
        return enabled && registeredRelayCount > 0 && !privateHeadphoneRoute;
    }

    static boolean shouldMuteMain(boolean enabled, Collection<SpeakerAudioRelay> relays,
            boolean privateHeadphoneRoute) {
        if (!enabled || privateHeadphoneRoute || relays == null) {
            return false;
        }
        return relays.stream().anyMatch(relay -> relay.takesOverMainOutput() && relay.hasOutputIntent());
    }
}