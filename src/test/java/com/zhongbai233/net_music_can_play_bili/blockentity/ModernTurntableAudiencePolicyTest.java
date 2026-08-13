package com.zhongbai233.net_music_can_play_bili.blockentity;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModernTurntableAudiencePolicyTest {
    private static final UUID STAYING = UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final UUID LEAVING = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID JOINING = UUID.fromString("00000000-0000-0000-0000-000000000043");

    @Test
    void reportsOnlyPlayersThatLeftTheSyncRange() {
        assertEquals(Set.of(LEAVING), ModernTurntableAudiencePolicy.departed(
                Set.of(STAYING, LEAVING), Set.of(STAYING, JOINING)));
    }

    @Test
    void handlesInitialAndEmptyAudiences() {
        assertEquals(Set.of(), ModernTurntableAudiencePolicy.departed(Set.of(), Set.of(JOINING)));
        assertEquals(Set.of(STAYING), ModernTurntableAudiencePolicy.departed(Set.of(STAYING), Set.of()));
    }
}
