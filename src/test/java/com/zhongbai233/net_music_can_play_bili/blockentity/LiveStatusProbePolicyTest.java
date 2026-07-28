package com.zhongbai233.net_music_can_play_bili.blockentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveStatusProbePolicyTest {
    @Test
    void probesOnlyWhenRequestedDueAndNotAlreadyRunning() {
        assertFalse(LiveStatusProbePolicy.shouldProbe(false, false, 200L, 200L));
        assertFalse(LiveStatusProbePolicy.shouldProbe(true, true, 200L, 200L));
        assertFalse(LiveStatusProbePolicy.shouldProbe(true, false, 199L, 200L));
        assertTrue(LiveStatusProbePolicy.shouldProbe(true, false, 200L, 200L));
    }

    @Test
    void schedulesNextProbeTenSecondsAfterCompletion() {
        assertEquals(1_200L, LiveStatusProbePolicy.nextProbeGameTime(1_000L));
    }

    @Test
    void rejectsResultsAfterStopOrRoomGenerationChange() {
        assertTrue(LiveStatusProbePolicy.acceptsResult(true, 3L, 3L, 8L, 8L, "123", "123"));
        assertFalse(LiveStatusProbePolicy.acceptsResult(false, 3L, 3L, 8L, 8L, "123", "123"));
        assertFalse(LiveStatusProbePolicy.acceptsResult(true, 4L, 3L, 8L, 8L, "123", "123"));
        assertFalse(LiveStatusProbePolicy.acceptsResult(true, 3L, 3L, 9L, 8L, "123", "123"));
        assertFalse(LiveStatusProbePolicy.acceptsResult(true, 3L, 3L, 8L, 8L, "456", "123"));
    }
}