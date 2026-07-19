package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPrefetchInputStreamTest {
    private static final int COPY_BUFFER_SIZE = 256 * 1024;

    @Test
    void readsCompleteChunkExactly() throws IOException {
        byte[] expected = new byte[37_111];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i * 31);
        }

        byte[] actual = CompleteChunkReader.read(new ByteArrayInputStream(expected), expected.length,
                COPY_BUFFER_SIZE);

        assertArrayEquals(expected, actual);
    }

    @Test
    void rejectsShortChunkWithoutReturningPartialBytes() {
        byte[] partial = new byte[8192];

        IOException error = assertThrows(IOException.class, () -> CompleteChunkReader
                .read(new ByteArrayInputStream(partial), partial.length * 2L, COPY_BUFFER_SIZE));

        assertTrue(error.getMessage().contains("ended early"));
        assertTrue(error.getMessage().contains("got 8192"));
    }

    @Test
    void rejectsInvalidExpectedLengths() {
        assertInvalidLength(0L);
        assertInvalidLength(-1L);
        assertInvalidLength((long) Integer.MAX_VALUE + 1L);
    }

    private static void assertInvalidLength(long length) {
        IOException error = assertThrows(IOException.class,
                () -> CompleteChunkReader.read(InputStream.nullInputStream(), length, COPY_BUFFER_SIZE));
        assertTrue(error.getMessage().contains("invalid bounded chunk length"));
    }
}