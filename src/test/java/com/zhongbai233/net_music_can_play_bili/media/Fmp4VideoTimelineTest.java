package com.zhongbai233.net_music_can_play_bili.media;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class Fmp4VideoTimelineTest {
    @Test
    void versionZeroCompositionOffsetsRemainUnsigned() {
        byte[] moof = moof(0, 0L,
                new int[] { 1_000 }, new int[] { 8 }, new int[] { 0x8000_0000 });

        Fmp4ToMp4Converter.SampleTable table = Fmp4ToMp4Converter.extractSampleTableFromMoof(
                moof, 1_000, 25);

        assertArrayEquals(new long[] { 2_147_483_648_000_000L }, table.ptsNanos());
    }

    @Test
    void versionOneSignedOffsetsPreserveDecodeOrderPresentationReordering() {
        byte[] moof = moof(1, 1_000L,
                new int[] { 1_000, 1_000 }, new int[] { 8, 8 }, new int[] { 1_000, -1_000 });

        Fmp4ToMp4Converter.SampleTable table = Fmp4ToMp4Converter.extractSampleTableFromMoof(
                moof, 1_000, 25);

        assertArrayEquals(new long[] { 2_000_000_000L, 1_000_000_000L }, table.ptsNanos());
    }

    private static byte[] moof(int trunVersion, long decodeTime,
            int[] durations, int[] sizes, int[] compositionOffsets) {
        ByteBuffer tfdt = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        tfdt.putInt(0); // version 0 + flags
        tfdt.putInt((int) decodeTime);

        int flags = 0x000100 | 0x000200 | 0x000800;
        ByteBuffer trun = ByteBuffer.allocate(8 + durations.length * 12).order(ByteOrder.BIG_ENDIAN);
        trun.put((byte) trunVersion);
        trun.put((byte) (flags >>> 16));
        trun.put((byte) (flags >>> 8));
        trun.put((byte) flags);
        trun.putInt(durations.length);
        for (int i = 0; i < durations.length; i++) {
            trun.putInt(durations[i]);
            trun.putInt(sizes[i]);
            trun.putInt(compositionOffsets[i]);
        }
        return box("traf", concat(box("tfdt", tfdt.array()), box("trun", trun.array())));
    }

    private static byte[] box(String type, byte[] payload) {
        ByteBuffer box = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.BIG_ENDIAN);
        box.putInt(8 + payload.length);
        box.put(type.getBytes(StandardCharsets.ISO_8859_1));
        box.put(payload);
        return box.array();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(first.length + second.length);
        out.writeBytes(first);
        out.writeBytes(second);
        return out.toByteArray();
    }
}
