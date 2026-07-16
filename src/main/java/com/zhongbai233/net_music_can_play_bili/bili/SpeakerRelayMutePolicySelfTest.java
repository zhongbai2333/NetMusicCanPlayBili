package com.zhongbai233.net_music_can_play_bili.bili;

public final class SpeakerRelayMutePolicySelfTest {
    private SpeakerRelayMutePolicySelfTest() {
    }

    public static void main(String[] args) {
        check(!SpeakerRelayMutePolicy.shouldMuteMain(true, 0),
                "turntable must remain audible without a registered speaker relay");
        check(SpeakerRelayMutePolicy.shouldMuteMain(true, 1),
                "one connected relay must mute the turntable before relay startup or channel selection");
        check(SpeakerRelayMutePolicy.shouldMuteMain(true, 3),
                "multiple connected relays must keep the turntable muted regardless of individual state");
        check(!SpeakerRelayMutePolicy.shouldMuteMain(false, 1),
                "compatibility switch must be able to disable relay takeover muting");
        check(!SpeakerRelayMutePolicy.shouldMuteMain(true, 0),
                "removing the last relay must restore the turntable output");
        System.out.println("SpeakerRelayMutePolicySelfTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}