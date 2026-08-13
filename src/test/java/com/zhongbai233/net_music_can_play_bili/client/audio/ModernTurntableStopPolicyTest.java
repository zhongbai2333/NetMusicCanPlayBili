package com.zhongbai233.net_music_can_play_bili.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModernTurntableStopPolicyTest {
    @Test
    void stopsOnlyTheExactActiveSession() {
        assertEquals(ModernTurntableStopPolicy.Decision.STOP_EXACT,
                ModernTurntableStopPolicy.decide("disc-session", "disc-session"));
    }

    @Test
    void staleOrMalformedStopsCannotKillTheReplacementSession() {
        assertEquals(ModernTurntableStopPolicy.Decision.IGNORE_STALE,
                ModernTurntableStopPolicy.decide("old-disc", "replacement-disc"));
        assertEquals(ModernTurntableStopPolicy.Decision.IGNORE_INVALID,
                ModernTurntableStopPolicy.decide("", "replacement-disc"));
        assertEquals(ModernTurntableStopPolicy.Decision.IGNORE_INVALID,
                ModernTurntableStopPolicy.decide("old-disc", null));
    }
}
