package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TurntableResolveAdmissionPolicyTest {
    private static final ResolveGeneration THIRD = ResolveGeneration.of(3L);

    @Test
    void acceptsOnlyTheCurrentIntentForTheSameSourceAndLevel() {
        assertEquals(TurntableResolveAdmissionPolicy.Decision.APPLY,
                TurntableResolveAdmissionPolicy.decide(false, true, THIRD, THIRD, "BV1|p=2", "BV1|p=2"));
        assertEquals(TurntableResolveAdmissionPolicy.Decision.DROP_REMOVED,
                TurntableResolveAdmissionPolicy.decide(true, true, THIRD, THIRD, "BV1|p=2", "BV1|p=2"));
        assertEquals(TurntableResolveAdmissionPolicy.Decision.DROP_LEVEL_CHANGED,
                TurntableResolveAdmissionPolicy.decide(false, false, THIRD, THIRD, "BV1|p=2", "BV1|p=2"));
        assertEquals(TurntableResolveAdmissionPolicy.Decision.DROP_SOURCE_CHANGED,
                TurntableResolveAdmissionPolicy.decide(false, true, THIRD, THIRD, "BV2|p=1", "BV1|p=2"));
    }

    @Test
    void rejectsAnOlderIntentEvenWhenTheSourceIsUnchanged() {
        assertEquals(TurntableResolveAdmissionPolicy.Decision.DROP_STALE_GENERATION,
                TurntableResolveAdmissionPolicy.decide(false, true, ResolveGeneration.of(4L), THIRD,
                        "BV1|p=2", "BV1|p=2"));
    }
}
