package com.zhongbai233.net_music_can_play_bili.editor.core;

import com.zhongbai233.net_music_can_play_bili.editor.core.media.ControlConsoleConsumerGain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlConsoleConsumerGainTest {
    @Test
    void combinesSpatialAndTemporalFadesForEveryVisualConsumer() {
        assertEquals(1.0F, ControlConsoleConsumerGain.combine(1.0F, 1.0F));
        assertEquals(0.5F, ControlConsoleConsumerGain.combine(0.5F, 1.0F));
        assertEquals(0.25F, ControlConsoleConsumerGain.combine(0.5F, 0.5F));
        assertEquals(0.0F, ControlConsoleConsumerGain.combine(0.0F, 1.0F));
    }

    @Test
    void clampsInvalidOrOutOfRangeInputs() {
        assertEquals(1.0F, ControlConsoleConsumerGain.combine(2.0F, 2.0F));
        assertEquals(0.0F, ControlConsoleConsumerGain.combine(-1.0F, 1.0F));
        assertEquals(0.0F, ControlConsoleConsumerGain.combine(Float.NaN, 1.0F));
    }
}