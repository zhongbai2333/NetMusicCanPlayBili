package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerRelayMutePolicyTest {
    @Test
    void flushKeepsRelayOnTheSharedMediaTimeline() {
        SpeakerAudioRelay relay = new SpeakerAudioRelay();
        relay.flushQueuedAudio(88_200L);
        assertEquals(88_200L, relay.timelineBaselineSamples());

        relay.flushQueuedAudio(44_100L);
        assertEquals(88_200L, relay.timelineBaselineSamples());

        relay.hardStopOutput();
        assertEquals(0L, relay.timelineBaselineSamples());
    }

    @Test
    void ignoresMutedAndUnsupportedRelays() {
        SpeakerAudioRelay muted = new SpeakerAudioRelay();
        muted.setChannelIndex(-1);
        muted.setUserVolume(1.0F);
        assertFalse(SpeakerRelayMutePolicy.shouldMuteMain(true, List.of(muted), false));

        SpeakerAudioRelay silent = new SpeakerAudioRelay();
        silent.setChannelIndex(0);
        silent.setUserVolume(0.0F);
        assertFalse(SpeakerRelayMutePolicy.shouldMuteMain(true, List.of(silent), false));
    }

    @Test
    void mutesMainWhenRelayCanActuallyOutput() {
        SpeakerAudioRelay relay = new SpeakerAudioRelay();
        relay.setChannelIndex(0);
        relay.setUserVolume(1.0F);
        assertTrue(SpeakerRelayMutePolicy.shouldMuteMain(true, List.of(relay), false));
        assertFalse(SpeakerRelayMutePolicy.shouldMuteMain(true, List.of(relay), true));
    }

    @Test
    void consoleRelayTakesOverTheTurntableOutputByDefault() {
        SpeakerAudioRelay console = new SpeakerAudioRelay();
        console.setChannelIndex(0);
        console.setUserVolume(1.0F);
        assertTrue(SpeakerRelayMutePolicy.shouldMuteMain(true, List.of(console), false));

        SpeakerAudioRelay physicalSpeaker = new SpeakerAudioRelay();
        physicalSpeaker.setChannelIndex(1);
        physicalSpeaker.setUserVolume(1.0F);
        assertTrue(SpeakerRelayMutePolicy.shouldMuteMain(true, List.of(console, physicalSpeaker), false));
    }
    @Test
    void mutesMainOutputOnlyWhenTakeoverIsEnabledAndRelaysExist() {
        assertFalse(SpeakerRelayMutePolicy.shouldMuteMain(true, 0, false));
        assertTrue(SpeakerRelayMutePolicy.shouldMuteMain(true, 1, false));
        assertTrue(SpeakerRelayMutePolicy.shouldMuteMain(true, 3, false));
        assertFalse(SpeakerRelayMutePolicy.shouldMuteMain(false, 1, false));
    }

    @Test
    void keepsMainOutputForPrivateHeadphoneRoute() {
        assertFalse(SpeakerRelayMutePolicy.shouldMuteMain(true, 1, true));
        assertFalse(SpeakerRelayMutePolicy.shouldMuteMain(true, 3, true));
    }
}
