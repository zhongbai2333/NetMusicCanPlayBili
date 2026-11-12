package com.zhongbai233.net_music_can_play_bili.media.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResolveGenerationTest {

    @Test
    void maximumValueWrapsToTheFirstActiveGeneration() {
        assertEquals(ResolveGeneration.of(1L), ResolveGeneration.of(Long.MAX_VALUE).next());
    }

    @Test
    void negativeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ResolveGeneration.of(-1L));
    }
}
