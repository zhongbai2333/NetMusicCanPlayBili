package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerRelayMutePolicyTest {
    @Test
    void mutesMainOutputOnlyWhenTakeoverIsEnabledAndRelaysExist() {
        assertFalse(SpeakerRelayMutePolicy.shouldMuteMain(true, 0));
        assertTrue(SpeakerRelayMutePolicy.shouldMuteMain(true, 1));
        assertTrue(SpeakerRelayMutePolicy.shouldMuteMain(true, 3));
        assertFalse(SpeakerRelayMutePolicy.shouldMuteMain(false, 1));
        assertFalse(SpeakerRelayMutePolicy.shouldMuteMain(true, 0));
    }
}