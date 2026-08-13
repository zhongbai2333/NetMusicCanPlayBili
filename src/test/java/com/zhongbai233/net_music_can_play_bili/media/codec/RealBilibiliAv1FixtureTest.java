package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4RangeSeekSupport;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4StreamParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealBilibiliAv1FixtureTest {
    private static final String FIRST_FIXTURE = "/bili/real-av1/init-index-first-fragment.m4s.b64";
    private static final String SEEK_FIXTURE = "/bili/real-av1/seek-fragment-35s.m4s.b64";
    private static final int TIMESCALE = 16_000;
    private static final int FPS = 25;

    @Test
    void frozenBytesAndSidxDescribeExactRealRanges() throws Exception {
        byte[] first = fixture(FIRST_FIXTURE);
        byte[] seek = fixture(SEEK_FIXTURE);

        assertEquals(117_150, first.length);
        assertEquals("39a7b50ce1ca0aae0906f69968d2c24cd9da6a88cd6c456615534969a754c349",
                sha256(first));
        assertEquals(61_959, seek.length);
        assertEquals("c2abab80f42df19e261375e9b7f67d924da87c07bcad18c5fb6e2b8ccb5bd215",
                sha256(seek));

        byte[] sidxBytes = Arrays.copyOfRange(first, 992, 1_492);
        Fmp4RangeSeekSupport.SidxIndex sidx = Fmp4RangeSeekSupport.parseSidx(sidxBytes, 992L);
        assertNotNull(sidx);
        assertEquals(TIMESCALE, sidx.timescale());
        assertEquals(39, sidx.entries().size());
        assertEntry(sidx.entries().get(0), 0.0D, 1_492L, 117_149L);
        assertEntry(sidx.entries().get(7), 35.0D, 900_893L, 962_851L);
    }

    @Test
    void productionParserReadsRealAv1ConfigSamplesAndPresentationTimeline() throws Exception {
        byte[] first = fixture(FIRST_FIXTURE);
        ParsedFragment parsed = parse(first);

        assertEquals(Fmp4StreamParser.ContainerKind.FMP4, parsed.kind());
        assertEquals(1, parsed.moofCount());
        assertEquals(115_062, parsed.mdat().length);
        assertEquals(115_062, Arrays.stream(parsed.sampleSizes()).sum());
        assertEquals(125, parsed.sampleSizes().length);
        assertEquals(52_808, parsed.sampleSizes()[0]);
        assertEquals(TIMESCALE, Fmp4ToMp4Converter.parseVideoTimescale(parsed.moov()));

        Fmp4NativeVideoDecoder.DecoderConfig config = Fmp4NativeVideoDecoder.extractDecoderConfig(
                parsed.moov(), 13);
        assertNotNull(config);
        assertEquals(0, config.nalLengthSize());
        assertEquals(16, config.packetPrefix().length);
        assertEquals("6390be5a9f89257f0b1eece5bc850b9cf8cc9f1cefc2f3101841ff6758654b34",
                sha256(config.packetPrefix()));

        Fmp4ToMp4Converter.SampleTable table = Fmp4ToMp4Converter.extractSampleTableFromMoof(
                parsed.moof(), TIMESCALE, FPS);
        assertArrayEquals(parsed.sampleSizes(), table.sampleSizes());
        assertEquals(0L, table.ptsNanos()[0]);
        assertEquals(40_000_000L, table.ptsNanos()[1]);
        assertEquals(4_960_000_000L, table.ptsNanos()[124]);
        assertStrictlyIncreasing(table.ptsNanos());
    }

    @Test
    void sidxRangeReconstructionStartsAtThirtyFiveSecondTfdt() throws Exception {
        byte[] first = fixture(FIRST_FIXTURE);
        byte[] seek = fixture(SEEK_FIXTURE);
        byte[] reconstructed = concat(Arrays.copyOfRange(first, 0, 940), seek);

        ParsedFragment parsed = parse(reconstructed);
        assertEquals(Fmp4StreamParser.ContainerKind.FMP4, parsed.kind());
        assertEquals(1, parsed.moofCount());
        assertEquals(61_363, parsed.mdat().length);
        assertEquals(125, parsed.sampleSizes().length);
        assertEquals(parsed.mdat().length, Arrays.stream(parsed.sampleSizes()).sum());

        Fmp4RangeSeekSupport.MoofProbe probe = Fmp4RangeSeekSupport.readMoofProbe(
                new ByteArrayInputStream(seek), 35.0F, TIMESCALE, seek.length,
                0.05D, 3.0D);
        assertNotNull(probe);
        assertEquals(0, probe.candidate().offset());
        assertEquals(35.0D, probe.candidate().fragmentSeconds(), 0.000_001D);

        Fmp4ToMp4Converter.SampleTable table = Fmp4ToMp4Converter.extractSampleTableFromMoof(
                parsed.moof(), TIMESCALE, FPS);
        assertEquals(35_000_000_000L, table.ptsNanos()[0]);
        assertEquals(39_960_000_000L, table.ptsNanos()[124]);
        assertStrictlyIncreasing(table.ptsNanos());
    }

    private static ParsedFragment parse(byte[] bytes) throws Exception {
        AtomicReference<byte[]> moov = new AtomicReference<>();
        AtomicReference<byte[]> moof = new AtomicReference<>();
        AtomicReference<int[]> sampleSizes = new AtomicReference<>();
        AtomicReference<byte[]> mdat = new AtomicReference<>();
        AtomicInteger moofCount = new AtomicInteger();
        Fmp4StreamParser.ContainerKind kind = new Fmp4StreamParser().parse(
                new ByteArrayInputStream(bytes), new AtomicBoolean(), new Fmp4StreamParser.Callback() {
                    @Override
                    public void onMoov(Fmp4ToMp4Converter.ParseResult ignored, byte[] moovData) {
                        moov.set(moovData);
                    }

                    @Override
                    public void onMoof(int[] sizes, byte[] moofData) {
                        moofCount.incrementAndGet();
                        sampleSizes.set(sizes);
                        moof.set(moofData);
                    }

                    @Override
                    public void onMdat(InputStream payload, long size) throws IOException {
                        mdat.set(Fmp4StreamParser.readFully(payload, size));
                    }

                    @Override
                    public void onRawEac3(InputStream payload) {
                        throw new AssertionError("real AV1 fixture was classified as raw E-AC-3");
                    }
                });
        assertNotNull(moov.get());
        assertNotNull(moof.get());
        assertNotNull(sampleSizes.get());
        assertNotNull(mdat.get());
        return new ParsedFragment(kind, moov.get(), moof.get(), sampleSizes.get(), mdat.get(), moofCount.get());
    }

    private static byte[] fixture(String resource) throws IOException {
        try (InputStream stream = RealBilibiliAv1FixtureTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            return Base64.getMimeDecoder().decode(stream.readAllBytes());
        }
    }

    private static byte[] concat(byte[] first, byte[] second) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(first.length + second.length);
        out.write(first);
        out.write(second);
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void assertEntry(Fmp4RangeSeekSupport.SidxEntry entry, double seconds,
            long start, long end) {
        assertEquals(seconds, entry.timeSeconds(), 0.000_001D);
        assertEquals(start, entry.byteStart());
        assertEquals(end, entry.byteEnd());
        assertTrue(entry.startsWithSap());
    }

    private static void assertStrictlyIncreasing(long[] values) {
        assertFalse(values.length == 0);
        for (int i = 1; i < values.length; i++) {
            assertTrue(values[i] > values[i - 1], "PTS must increase at index " + i);
        }
    }

    private record ParsedFragment(Fmp4StreamParser.ContainerKind kind, byte[] moov, byte[] moof,
            int[] sampleSizes, byte[] mdat, int moofCount) {
    }
}
