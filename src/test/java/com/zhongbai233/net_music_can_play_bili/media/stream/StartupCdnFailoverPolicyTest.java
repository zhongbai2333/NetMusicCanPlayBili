package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupCdnFailoverPolicyTest {
    @Test
    void firstRangeIsBoundedByStartupTarget() {
        assertEquals(768L * 1024L,
                StartupCdnFailoverPolicy.firstRequestBytes(4L * 1024L * 1024L, 768L * 1024L, true));
        assertEquals(4L * 1024L * 1024L,
                StartupCdnFailoverPolicy.firstRequestBytes(4L * 1024L * 1024L, 768L * 1024L, false));
    }

    @Test
    void onlyEmptyInFlightStartupWithAlternateCanSwitch() {
        assertTrue(StartupCdnFailoverPolicy.shouldSwitch(false, 0L, 2, true, false));
        assertFalse(StartupCdnFailoverPolicy.shouldSwitch(false, 1L, 2, true, false));
        assertFalse(StartupCdnFailoverPolicy.shouldSwitch(false, 0L, 1, true, false));
        assertFalse(StartupCdnFailoverPolicy.shouldSwitch(false, 0L, 2, false, false));
        assertFalse(StartupCdnFailoverPolicy.shouldSwitch(false, 0L, 2, true, true));
        assertFalse(StartupCdnFailoverPolicy.shouldSwitch(true, 0L, 2, true, false));
    }
}
