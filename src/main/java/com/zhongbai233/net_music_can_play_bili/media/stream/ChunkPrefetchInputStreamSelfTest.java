package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Lightweight regression tests for transactional bounded Range reads. */
public final class ChunkPrefetchInputStreamSelfTest {
    private ChunkPrefetchInputStreamSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        readsCompleteChunkExactly();
        rejectsShortChunkWithoutReturningPartialBytes();
        rejectsInvalidExpectedLengths();
        System.out.println("ChunkPrefetchInputStreamSelfTest passed");
    }

    private static void readsCompleteChunkExactly() throws Exception {
        byte[] expected = new byte[37_111];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i * 31);
        }
        byte[] actual = ChunkPrefetchInputStream.readCompleteChunk(
                new ByteArrayInputStream(expected), expected.length);
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("complete bounded chunk changed during transactional read");
        }
    }

    private static void rejectsShortChunkWithoutReturningPartialBytes() throws Exception {
        byte[] partial = new byte[8192];
        InputStream closesHalfway = new ByteArrayInputStream(partial);
        try {
            ChunkPrefetchInputStream.readCompleteChunk(closesHalfway, partial.length * 2L);
            throw new AssertionError("short CDN chunk should fail instead of returning partial bytes");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("ended early") || !expected.getMessage().contains("got 8192")) {
                throw new AssertionError("unexpected short-read error", expected);
            }
        }
    }

    private static void rejectsInvalidExpectedLengths() throws Exception {
        assertInvalidLength(0L);
        assertInvalidLength(-1L);
        assertInvalidLength((long) Integer.MAX_VALUE + 1L);
    }

    private static void assertInvalidLength(long length) throws Exception {
        try {
            ChunkPrefetchInputStream.readCompleteChunk(InputStream.nullInputStream(), length);
            throw new AssertionError("invalid chunk length should fail: " + length);
        } catch (IOException expected) {
            if (!expected.getMessage().contains("invalid bounded chunk length")) {
                throw new AssertionError("unexpected invalid-length error", expected);
            }
        }
    }
}