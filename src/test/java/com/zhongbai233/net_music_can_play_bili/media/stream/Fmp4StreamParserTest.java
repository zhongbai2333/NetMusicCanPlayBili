package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Fmp4StreamParserTest {
    @Test
    void rejectsOversizedPayloadBeforeReading() {
        InputStream mustNotRead = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("oversized payload must be rejected before reading or allocating");
            }
        };

        IOException failure = assertThrows(IOException.class,
                () -> Fmp4StreamParser.readFully(mustNotRead, Long.MAX_VALUE));
        assertTrue(failure.getMessage().contains("too large"));
    }
}