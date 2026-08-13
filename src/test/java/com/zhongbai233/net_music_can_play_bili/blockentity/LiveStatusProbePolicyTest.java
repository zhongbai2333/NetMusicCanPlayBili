package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
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
        ResolveGeneration current = ResolveGeneration.of(3L);
        assertTrue(LiveStatusProbePolicy.acceptsResult(true, current, current, 8L, 8L, "123", "123"));
        assertFalse(LiveStatusProbePolicy.acceptsResult(false, current, current, 8L, 8L, "123", "123"));
        assertFalse(LiveStatusProbePolicy.acceptsResult(true, ResolveGeneration.of(4L), current,
                8L, 8L, "123", "123"));
        assertFalse(LiveStatusProbePolicy.acceptsResult(true, current, current, 9L, 8L, "123", "123"));
        assertFalse(LiveStatusProbePolicy.acceptsResult(true, current, current, 8L, 8L, "456", "123"));
    }
}
