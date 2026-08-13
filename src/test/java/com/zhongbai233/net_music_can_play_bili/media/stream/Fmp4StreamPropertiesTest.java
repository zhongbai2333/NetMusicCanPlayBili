package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Fmp4StreamPropertiesTest {
    @AfterEach
    void clearProperty() {
        System.clearProperty(Fmp4StreamProperties.MAX_BUFFERED_PAYLOAD_BYTES);
    }

    @Test
    void defaultRemainsCompatible() {
        assertEquals(64 * 1024 * 1024, Fmp4StreamProperties.maxBufferedPayloadBytes());
    }

    @Test
    void explicitLimitRemainsConfigurable() {
        System.setProperty(Fmp4StreamProperties.MAX_BUFFERED_PAYLOAD_BYTES, "2097152");
        assertEquals(2 * 1024 * 1024, Fmp4StreamProperties.maxBufferedPayloadBytes());
    }

    @Test
    void invalidValuesUseDefaultAndSmallLimitsAreClamped() {
        System.setProperty(Fmp4StreamProperties.MAX_BUFFERED_PAYLOAD_BYTES, "invalid");
        assertEquals(64 * 1024 * 1024, Fmp4StreamProperties.maxBufferedPayloadBytes());

        System.setProperty(Fmp4StreamProperties.MAX_BUFFERED_PAYLOAD_BYTES, "0");
        assertEquals(1024 * 1024, Fmp4StreamProperties.maxBufferedPayloadBytes());
    }
}
