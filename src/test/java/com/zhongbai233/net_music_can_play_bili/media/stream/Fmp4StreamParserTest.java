package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Fmp4StreamParserTest {
    @Test
    void classifiesMinimalFmp4() throws Exception {
        byte[] ftyp = { 0, 0, 0, 8, 'f', 't', 'y', 'p' };

        Fmp4StreamParser.ContainerKind kind = new Fmp4StreamParser().parse(
                new ByteArrayInputStream(ftyp), new AtomicBoolean(), callback());

        assertEquals(Fmp4StreamParser.ContainerKind.FMP4, kind);
    }

    @Test
    void classifiesRawEac3AndInvokesCallback() throws Exception {
        AtomicBoolean rawCalled = new AtomicBoolean();
        byte[] rawEac3 = { 0x0B, 0x77, 1, 2, 3, 4 };

        Fmp4StreamParser.ContainerKind kind = new Fmp4StreamParser().parse(
                new ByteArrayInputStream(rawEac3), new AtomicBoolean(), new TestCallback() {
                    @Override
                    public void onRawEac3(InputStream payload) {
                        rawCalled.set(true);
                    }
                });

        assertEquals(Fmp4StreamParser.ContainerKind.RAW_EAC3, kind);
        assertTrue(rawCalled.get());
    }

    @Test
    void classifiesOrdinaryAudioWithoutThrowing() throws Exception {
        byte[] mp3Prefix = { 'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 1, 2 };

        Fmp4StreamParser.ContainerKind kind = new Fmp4StreamParser().parse(
                new ByteArrayInputStream(mp3Prefix), new AtomicBoolean(), callback());

        assertEquals(Fmp4StreamParser.ContainerKind.OTHER_AUDIO, kind);
    }

    @Test
    void rejectsTruncatedFmp4PayloadInsteadOfFallingBack() {
        byte[] truncated = { 0, 0, 0, 12, 'f', 't', 'y', 'p', 1, 2 };

        assertThrows(IOException.class, () -> new Fmp4StreamParser().parse(
                new ByteArrayInputStream(truncated), new AtomicBoolean(), callback()));
    }

    @Test
    void rejectsInvalidFmp4BoxSize() {
        byte[] invalid = { 0, 0, 0, 4, 'f', 't', 'y', 'p' };

        IOException failure = assertThrows(IOException.class, () -> new Fmp4StreamParser().parse(
                new ByteArrayInputStream(invalid), new AtomicBoolean(), callback()));
        assertTrue(failure.getMessage().contains("invalid MP4 box size"));
    }

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

    private static Fmp4StreamParser.Callback callback() {
        return new TestCallback();
    }

    private static class TestCallback implements Fmp4StreamParser.Callback {
        @Override
        public void onMoov(com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter.ParseResult parseResult,
                byte[] moovData) {
        }

        @Override
        public void onMoof(int[] sampleSizes, byte[] moofData) {
        }

        @Override
        public void onMdat(InputStream payload, long size) {
        }

        @Override
        public void onRawEac3(InputStream payload) throws IOException {
        }
    }
}