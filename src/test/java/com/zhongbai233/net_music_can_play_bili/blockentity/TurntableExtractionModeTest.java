package com.zhongbai233.net_music_can_play_bili.blockentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class TurntableExtractionModeTest {
    @Test
    void mapsCycleOrderAndPersistedNames() {
        assertSame(TurntableExtractionMode.ALWAYS, TurntableExtractionMode.AFTER_PLAYBACK.next());
        assertSame(TurntableExtractionMode.AFTER_PLAYBACK, TurntableExtractionMode.ALWAYS.next());
        assertSame(TurntableExtractionMode.AFTER_PLAYBACK, TurntableExtractionMode.byName("after_playback"));
        assertSame(TurntableExtractionMode.ALWAYS, TurntableExtractionMode.byName("always"));
        assertSame(TurntableExtractionMode.AFTER_PLAYBACK, TurntableExtractionMode.byName("unknown"));
    }
}