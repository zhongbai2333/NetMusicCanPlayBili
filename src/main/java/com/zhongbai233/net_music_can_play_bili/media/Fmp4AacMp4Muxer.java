package com.zhongbai233.net_music_can_play_bili.media;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Builds the small single-track AAC MP4 emitted by {@link Fmp4ToMp4Converter}. */
final class Fmp4AacMp4Muxer {
    private Fmp4AacMp4Muxer() {
    }

    static byte[] build(byte[] asc, int[] sizes, int[] durations, byte[] audioData, int timescale) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] ftyp = box("ftyp", bytes("isom\u0000\u0000\u0000\u0000isomiso2mp41"));
        out.write(ftyp, 0, ftyp.length);
        long mdatPayloadOffset = ftyp.length + 8L;
        byte[] moov = moov(asc, sizes, durations, mdatPayloadOffset, timescale);
        mdatPayloadOffset = ftyp.length + moov.length + 8L;
        moov = moov(asc, sizes, durations, mdatPayloadOffset, timescale);
        out.write(moov, 0, moov.length);
        writeInt(out, 8 + audioData.length);
        out.write(bytes("mdat"), 0, 4);
        out.write(audioData, 0, audioData.length);
        return out.toByteArray();
    }

    private static byte[] moov(byte[] asc, int[] sizes, int[] durations, long mdatOffset, int timescale) {
        timescale = Math.max(1, timescale);
        int totalSamples = sizes.length;
        long duration = 0;
        for (int sampleDuration : durations) {
            duration += sampleDuration;
        }
        byte[] mvhd = mvhd(timescale, duration);
        byte[] tkhd = tkhd(1, duration);
        byte[] mdhd = mdhd(timescale, duration);
        byte[] hdlr = hdlr();
        byte[] stsd = stsd(asc, timescale);
        byte[] stts = stts(totalSamples, durations);
        byte[] stsz = stsz(sizes);
        byte[] stsc = stsc(totalSamples);
        byte[] stco = stco(mdatOffset);
        byte[] smhd = fullBox("smhd", new byte[4]);
        byte[] dinf = dinf();
        byte[] stbl = container("stbl", stsd, stts, stsz, stsc, stco);
        byte[] minf = container("minf", smhd, dinf, stbl);
        byte[] mdia = container("mdia", mdhd, hdlr, minf);
        byte[] trak = container("trak", tkhd, mdia);
        return container("moov", mvhd, trak);
    }

    private static byte[] container(String type, byte[]... boxes) {
        int length = 0;
        for (byte[] child : boxes) {
            length += child.length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 8 + length);
        out.write(bytes(type), 0, 4);
        for (byte[] child : boxes) {
            out.write(child, 0, child.length);
        }
        return out.toByteArray();
    }

    private static byte[] box(String type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 8 + payload.length);
        out.write(bytes(type), 0, 4);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static byte[] fullBox(String type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 12 + payload.length);
        out.write(bytes(type), 0, 4);
        writeInt(out, 0);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static byte[] dinf() {
        ByteArrayOutputStream url = new ByteArrayOutputStream();
        writeInt(url, 12);
        url.write(bytes("url "), 0, 4);
        writeInt(url, 1);
        byte[] urlBox = url.toByteArray();
        ByteArrayOutputStream drefPayload = new ByteArrayOutputStream();
        writeInt(drefPayload, 1);
        drefPayload.write(urlBox, 0, urlBox.length);
        return box("dinf", fullBox("dref", drefPayload.toByteArray()));
    }

    private static byte[] stsd(byte[] asc, int timescale) {
        byte[] esds = esds(asc);
        ByteArrayOutputStream mp4a = new ByteArrayOutputStream();
        writeInt(mp4a, 8 + 28 + esds.length);
        mp4a.write(bytes("mp4a"), 0, 4);
        writeBytes(mp4a, new byte[6]);
        writeShort(mp4a, 1);
        writeShort(mp4a, 0);
        writeShort(mp4a, 0);
        writeInt(mp4a, 0);
        writeShort(mp4a, 2);
        writeShort(mp4a, 16);
        writeShort(mp4a, 0);
        writeShort(mp4a, 0);
        writeInt(mp4a, Math.max(1, timescale) << 16);
        mp4a.write(esds, 0, esds.length);
        byte[] mp4aBytes = mp4a.toByteArray();
        ByteArrayOutputStream stsd = new ByteArrayOutputStream();
        writeInt(stsd, 16 + mp4aBytes.length);
        stsd.write(bytes("stsd"), 0, 4);
        writeInt(stsd, 0);
        writeInt(stsd, 1);
        stsd.write(mp4aBytes, 0, mp4aBytes.length);
        return stsd.toByteArray();
    }

    private static byte[] esds(byte[] asc) {
        ByteArrayOutputStream dsi = new ByteArrayOutputStream();
        dsi.write(0x05);
        writeVarLen(dsi, asc.length);
        dsi.write(asc, 0, asc.length);
        byte[] dsiBytes = dsi.toByteArray();
        ByteArrayOutputStream decoderConfig = new ByteArrayOutputStream();
        decoderConfig.write(0x04);
        writeVarLen(decoderConfig, 13 + dsiBytes.length);
        decoderConfig.write(0x40);
        decoderConfig.write(0x15);
        writeBytes(decoderConfig, new byte[] { 0, 0, 0 });
        writeInt(decoderConfig, 0x1FFFFF);
        writeInt(decoderConfig, 0x1FFFFF);
        decoderConfig.write(dsiBytes, 0, dsiBytes.length);
        byte[] decoderConfigBytes = decoderConfig.toByteArray();
        byte[] sl = { 0x06, 0x01, 0x02 };
        ByteArrayOutputStream es = new ByteArrayOutputStream();
        es.write(0x03);
        writeVarLen(es, 3 + decoderConfigBytes.length + sl.length);
        writeShort(es, 1);
        es.write(0);
        es.write(decoderConfigBytes, 0, decoderConfigBytes.length);
        es.write(sl, 0, sl.length);
        byte[] esBytes = es.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 12 + esBytes.length);
        out.write(bytes("esds"), 0, 4);
        writeInt(out, 0);
        out.write(esBytes, 0, esBytes.length);
        return out.toByteArray();
    }

    private static byte[] stts(int totalSamples, int[] durations) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 24);
        out.write(bytes("stts"), 0, 4);
        writeInt(out, 0);
        writeInt(out, 1);
        writeInt(out, totalSamples);
        writeInt(out, durations[0]);
        return out.toByteArray();
    }

    private static byte[] stsz(int[] sizes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 20 + sizes.length * 4);
        out.write(bytes("stsz"), 0, 4);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, sizes.length);
        for (int size : sizes) {
            writeInt(out, size);
        }
        return out.toByteArray();
    }

    private static byte[] stsc(int totalSamples) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 28);
        out.write(bytes("stsc"), 0, 4);
        writeInt(out, 0);
        writeInt(out, 1);
        writeInt(out, 1);
        writeInt(out, totalSamples);
        writeInt(out, 1);
        return out.toByteArray();
    }

    private static byte[] stco(long offset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 20);
        out.write(bytes("stco"), 0, 4);
        writeInt(out, 0);
        writeInt(out, 1);
        writeInt(out, (int) offset);
        return out.toByteArray();
    }

    private static byte[] mdhd(int timescale, long duration) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 32);
        out.write(bytes("mdhd"), 0, 4);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, timescale);
        writeInt(out, (int) duration);
        writeShort(out, 0x55C4);
        writeShort(out, 0);
        return out.toByteArray();
    }

    private static byte[] hdlr() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 45);
        out.write(bytes("hdlr"), 0, 4);
        writeInt(out, 0);
        writeInt(out, 0);
        out.write(bytes("soun"), 0, 4);
        writeBytes(out, new byte[12]);
        writeBytes(out, new byte[] { 'S', 'o', 'u', 'n', 'd', 'H', 'a', 'n', 'd', 'l', 'e', 'r', 0 });
        return out.toByteArray();
    }

    private static byte[] tkhd(int trackId, long duration) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 92);
        out.write(bytes("tkhd"), 0, 4);
        writeInt(out, 7);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, trackId);
        writeInt(out, 0);
        writeInt(out, (int) duration);
        writeBytes(out, new byte[8]);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0x0100);
        writeShort(out, 0);
        writeInt(out, 0x00010000);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0x00010000);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0x40000000);
        writeInt(out, 0);
        writeInt(out, 0);
        return out.toByteArray();
    }

    private static byte[] mvhd(int timescale, long duration) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 108);
        out.write(bytes("mvhd"), 0, 4);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, timescale);
        writeInt(out, (int) duration);
        writeInt(out, 0x00010000);
        writeShort(out, 0x0100);
        writeShort(out, 0);
        writeBytes(out, new byte[8]);
        writeInt(out, 0x00010000);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0x00010000);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0x40000000);
        writeBytes(out, new byte[24]);
        writeInt(out, 2);
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] value) {
        out.write(value, 0, value.length);
    }

    private static void writeVarLen(ByteArrayOutputStream out, int size) {
        if (size < 0x80) {
            out.write(size);
            return;
        }
        int bytes = 0;
        for (int remaining = size; remaining > 0; remaining >>= 7) {
            bytes++;
        }
        for (int index = bytes - 1; index >= 0; index--) {
            out.write(((size >> (7 * index)) & 0x7F) | (index > 0 ? 0x80 : 0));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
