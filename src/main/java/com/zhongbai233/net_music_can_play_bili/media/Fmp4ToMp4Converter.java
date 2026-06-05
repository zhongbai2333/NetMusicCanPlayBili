package com.zhongbai233.net_music_can_play_bili.media;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class Fmp4ToMp4Converter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private Fmp4ToMp4Converter() {
    }

    private static final int TRUN_DATA_OFFSET = 0x000001;
    private static final int TRUN_FIRST_SAMPLE_FLAGS = 0x000004;
    private static final int TRUN_SAMPLE_DURATION = 0x000100;
    private static final int TRUN_SAMPLE_SIZE = 0x000200;
    private static final int TRUN_SAMPLE_FLAGS = 0x000400;
    private static final int TRUN_SAMPLE_COMP_TIME = 0x000800;
    private static final int TFHD_BASE_DATA_OFFSET = 0x000001;
    private static final int TFHD_SAMPLE_DESCRIPTION_INDEX = 0x000002;
    private static final int TFHD_DEFAULT_SAMPLE_DURATION = 0x000008;
    private static final int TFHD_DEFAULT_SAMPLE_SIZE = 0x000010;
    private static final int TFHD_DEFAULT_SAMPLE_FLAGS = 0x000020;
    private static final byte[] DEFAULT_ASC = { 0x11, (byte) 0x90 };

    public static byte[] convertToStandardMp4(byte[] fmp4Data) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(fmp4Data).order(ByteOrder.BIG_ENDIAN);
        byte[] asc = null;
        int audioTimescale = 48_000;
        List<Integer> allSizes = new ArrayList<>();
        List<Integer> allDurs = new ArrayList<>();
        List<byte[]> mdatChunks = new ArrayList<>();
        while (buf.remaining() >= 8) {
            BoxHeader box = readBoxHeader(buf);
            if (box == null)
                break;
            long dataSize = box.dataSize();
            if ("moov".equals(box.type)) {
                byte[] moovData = new byte[(int) dataSize];
                buf.get(moovData);
                ParseResult pr = parseMoov(moovData);
                if (pr.asc != null) {
                    asc = pr.asc;
                }
                if (pr.timescale > 0) {
                    audioTimescale = pr.timescale;
                }
            } else if ("moof".equals(box.type)) {
                byte[] moofData = new byte[(int) dataSize];
                buf.get(moofData);
                ParseResult pr = parseMoof(moofData);
                if (pr.sampleSizes != null) {
                    for (int i = 0; i < pr.sampleSizes.length; i++) {
                        int sz = pr.sampleSizes[i];
                        allSizes.add(sz);
                        allDurs.add(sampleDuration(pr, i, audioTimescale));
                    }
                } else if (pr.defaultSampleSize > 0 && pr.sampleCount > 0) {
                    for (int i = 0; i < pr.sampleCount; i++) {
                        allSizes.add(pr.defaultSampleSize);
                        allDurs.add(sampleDuration(pr, i, audioTimescale));
                    }
                }
            } else if ("mdat".equals(box.type)) {
                if (dataSize > 0) {
                    byte[] mdat = new byte[(int) Math.min(dataSize, (long) Integer.MAX_VALUE)];
                    buf.get(mdat);
                    mdatChunks.add(mdat);
                }
            } else {
                buf.position(buf.position() + (int) Math.min(dataSize, buf.remaining()));
            }
        }
        if (asc == null) {
            LOGGER.warn("AAC ASC 提取失败，回退到默认 ASC (48000Hz 立体声)。低品质流可能产生噪音。");
            asc = DEFAULT_ASC;
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (byte[] c : mdatChunks)
            raw.write(c);
        byte[] full = raw.toByteArray();
        byte[] audioData = full;
        int[] sizes = allSizes.stream().mapToInt(i -> i).toArray();
        int[] durs = allDurs.stream().mapToInt(i -> i).toArray();
        LOGGER.debug("[Fmp4ToAdts] MP4转换: {}帧, {}B AAC", sizes.length, audioData.length);
        return buildStandardMp4(asc, sizes, durs, audioData, audioTimescale);
    }

    private static int sampleDuration(ParseResult pr, int index, int fallbackTimescale) {
        if (pr.sampleDurations != null && index >= 0 && index < pr.sampleDurations.length
                && pr.sampleDurations[index] > 0L) {
            return (int) Math.min(Integer.MAX_VALUE, pr.sampleDurations[index]);
        }
        if (pr.defaultSampleDuration > 0L) {
            return (int) Math.min(Integer.MAX_VALUE, pr.defaultSampleDuration);
        }
        return 1024;
    }

    /** 从 fMP4 头部的 moov box 快速提取音频格式，用于流式播放前预知采样率/声道 */
    public static AudioFormat sniffAudioFormat(byte[] partialFmp4) {
        ByteBuffer buf = ByteBuffer.wrap(partialFmp4).order(ByteOrder.BIG_ENDIAN);
        while (buf.remaining() >= 8) {
            BoxHeader box = readBoxHeader(buf);
            if (box == null)
                break;
            long dataSize = box.dataSize();
            if ("moov".equals(box.type) && dataSize > 0 && dataSize <= buf.remaining()) {
                byte[] moovData = new byte[(int) dataSize];
                buf.get(moovData);
                ParseResult pr = parseMoov(moovData);
                if (pr.asc != null) {
                    return ascToAudioFormat(pr.asc);
                }
                break;
            }
            int skip = (int) Math.min(dataSize, buf.remaining());
            buf.position(buf.position() + skip);
        }
        return null;
    }

    /** 从 fLaC stsd entry 里提取子 box dfLa 的 payload。 */
    private static byte[] extractDfLaFromFlacStsd(byte[] flacEntry) {
        // fLaC 条目：6B 保留 + 2B dref + 子 box
        if (flacEntry.length < 28 + 8)
            return null;
        int pos = 28; // skip reserved + dref, start at first child box
        while (pos + 8 <= flacEntry.length) {
            int boxSize = readIntBE(flacEntry, pos);
            String boxType = new String(flacEntry, pos + 4, 4);
            if (boxSize < 8 || pos + boxSize > flacEntry.length)
                break;
            if ("dfLa".equals(boxType) && boxSize >= 12) {
                byte[] dfLa = new byte[boxSize - 8];
                System.arraycopy(flacEntry, pos + 8, dfLa, 0, dfLa.length);
                return dfLa;
            }
            pos += boxSize;
        }
        return null;
    }

    /** 从 dfLa 的 FLAC 元数据块中提取 AudioFormat。 */
    public static AudioFormat flacDfLaToAudioFormat(byte[] dfLa) {
        if (dfLa == null || dfLa.length < 4)
            return null;
        // dfLa：version(1) + flags(3) + 拼接的元数据块
        int pos = 4;
        while (pos + 4 <= dfLa.length) {
            int header = (dfLa[pos] & 0xFF) << 24 | (dfLa[pos + 1] & 0xFF) << 16
                    | (dfLa[pos + 2] & 0xFF) << 8 | (dfLa[pos + 3] & 0xFF);
            boolean lastBlock = (header & 0x80000000) != 0;
            int blockType = (header >> 24) & 0x7F;
            int blockLen = header & 0x00FFFFFF;
            pos += 4;
            if (pos + blockLen > dfLa.length)
                break;
            if (blockType == 0 && blockLen >= 18) {
                // STREAMINFO：跳过 min/max blocksize(4B)、min/max framesize(6B)
                // byte 10-12：sample_rate(20bit) + num_channels(3bit) + bps(5bit)
                int b10 = dfLa[pos + 10] & 0xFF;
                int b11 = dfLa[pos + 11] & 0xFF;
                int b12 = dfLa[pos + 12] & 0xFF;
                int sampleRate = (b10 << 12) | (b11 << 4) | ((b12 >> 4) & 0x0F);
                int channels = ((b12 >> 1) & 0x07) + 1;
                int bps = (((b12 & 0x01) << 4) | ((dfLa[pos + 13] & 0xF0) >> 4)) + 1;
                if (bps < 4)
                    bps = 16;
                return new AudioFormat(sampleRate, bps, channels, true, false);
            }
            pos += blockLen;
            if (lastBlock)
                break;
        }
        return null;
    }

    /**
     * dfLa 是 MP4 FullBox payload：version(1) + flags(3) + FLAC metadata blocks。
     * 原生 FLAC 流在 "fLaC" marker 后只能接 metadata blocks，因此写流时必须跳过前 4 字节。
     */
    public static byte[] dfLaToNativeFlacMetadata(byte[] dfLa) {
        if (dfLa == null || dfLa.length <= 4) {
            return new byte[0];
        }
        byte[] metadata = new byte[dfLa.length - 4];
        System.arraycopy(dfLa, 4, metadata, 0, metadata.length);
        return metadata;
    }

    /** 从 moof box 的 payload 中提取当前 fragment 的 AAC sample 大小列表。 */
    public static int[] extractSampleSizesFromMoof(byte[] moofData) {
        ParseResult pr = parseMoof(moofData);
        if (pr.sampleSizes != null) {
            return pr.sampleSizes;
        }
        if (pr.defaultSampleSize > 0 && pr.sampleCount > 0) {
            int[] sizes = new int[pr.sampleCount];
            for (int i = 0; i < sizes.length; i++) {
                sizes[i] = pr.defaultSampleSize;
            }
            return sizes;
        }
        return new int[0];
    }

    public static SampleTable extractSampleTableFromMoof(byte[] moofData, int timescale, int fallbackFps) {
        ParseResult pr = parseMoof(moofData);
        int count = pr.sampleCount;
        if (count <= 0 && pr.sampleSizes != null) {
            count = pr.sampleSizes.length;
        }
        if (count <= 0) {
            return SampleTable.EMPTY;
        }
        int[] sizes = pr.sampleSizes;
        if ((sizes == null || sizes.length < count) && pr.defaultSampleSize > 0) {
            sizes = new int[count];
            java.util.Arrays.fill(sizes, pr.defaultSampleSize);
        }
        if (sizes == null) {
            sizes = new int[0];
        }

        long[] ptsNanos = new long[count];
        long decodeTime = Math.max(0L, pr.baseMediaDecodeTime);
        int scale = Math.max(1, timescale);
        long fallbackDuration = Math.max(1L, Math.round(scale / (double) Math.max(1, fallbackFps)));
        for (int i = 0; i < count; i++) {
            long duration = pr.sampleDurations != null && i < pr.sampleDurations.length && pr.sampleDurations[i] > 0L
                    ? pr.sampleDurations[i]
                    : pr.defaultSampleDuration > 0L ? pr.defaultSampleDuration : fallbackDuration;
            long compositionOffset = pr.sampleCompositionOffsets != null && i < pr.sampleCompositionOffsets.length
                    ? pr.sampleCompositionOffsets[i]
                    : 0L;
            ptsNanos[i] = Math.max(0L,
                    Math.round(Math.max(0L, decodeTime + compositionOffset) * 1_000_000_000.0D / scale));
            decodeTime += duration;
        }
        return new SampleTable(sizes, ptsNanos);
    }

    /** AAC ASC（AudioSpecificConfig）→ AudioFormat */
    private static AudioFormat ascToAudioFormat(byte[] asc) {
        if (asc == null || asc.length < 2)
            return null;
        int b0 = asc[0] & 0xFF, b1 = asc[1] & 0xFF;
        int freqIndex = ((b0 & 0x07) << 1) | ((b1 >> 7) & 0x01);
        int channels = (b1 >> 3) & 0x0F;
        if (channels < 1)
            channels = 2;
        int[] rates = { 96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350 };
        float rate = freqIndex < rates.length ? rates[freqIndex] : 44100.0f;
        return new AudioFormat(rate, 16, channels, true, false);
    }

    private static byte[] buildStandardMp4(byte[] asc, int[] sizes, int[] durs, byte[] audioData, int timescale) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] ftyp = makeBox("ftyp", toBytes("isom\u0000\u0000\u0000\u0000isomiso2mp41"));
        out.write(ftyp, 0, ftyp.length);
        long mdatPayloadOffset = ftyp.length + 8L;
        byte[] moov = buildMoov(asc, sizes, durs, mdatPayloadOffset, timescale);
        mdatPayloadOffset = ftyp.length + moov.length + 8L;
        moov = buildMoov(asc, sizes, durs, mdatPayloadOffset, timescale);
        out.write(moov, 0, moov.length);
        writeInt(out, 8 + audioData.length);
        out.write(toBytes("mdat"), 0, 4);
        out.write(audioData, 0, audioData.length);
        return out.toByteArray();
    }

    private static byte[] buildMoov(byte[] asc, int[] sizes, int[] durs, long mdatOff, int timescale) {
        timescale = Math.max(1, timescale);
        int totalSamples = sizes.length;
        long duration = 0;
        for (int d : durs)
            duration += d;
        byte[] mvhd = makeMvhd(timescale, duration);
        byte[] tkhd = makeTkhd(1, duration);
        byte[] mdhd = makeMdhd(timescale, duration);
        byte[] hdlr = makeHdlr();
        byte[] stsd = makeStsd(asc, timescale);
        byte[] stts = makeStts(totalSamples, durs);
        byte[] stsz = makeStsz(sizes);
        byte[] stsc = makeStsc(totalSamples);
        byte[] stco = makeStco(mdatOff);
        byte[] smhd = makeFullBox("smhd", new byte[4]);
        byte[] dinf = makeDinf();
        byte[] stbl = concat("stbl", stsd, stts, stsz, stsc, stco);
        byte[] minf = concat("minf", smhd, dinf, stbl);
        byte[] mdia = concat("mdia", mdhd, hdlr, minf);
        byte[] trak = concat("trak", tkhd, mdia);
        return concat("moov", mvhd, trak);
    }

    private static byte[] concat(String type, byte[]... boxes) {
        int len = 0;
        for (byte[] b : boxes)
            len += b.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 8 + len);
        out.write(toBytes(type), 0, 4);
        for (byte[] b : boxes)
            out.write(b, 0, b.length);
        return out.toByteArray();
    }

    private static byte[] makeBox(String type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 8 + payload.length);
        out.write(toBytes(type), 0, 4);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static byte[] makeFullBox(String type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 12 + payload.length);
        out.write(toBytes(type), 0, 4);
        writeInt(out, 0);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static byte[] makeDinf() {
        ByteArrayOutputStream url = new ByteArrayOutputStream();
        writeInt(url, 12);
        url.write(toBytes("url "), 0, 4);
        writeInt(url, 1);
        byte[] urlBox = url.toByteArray();
        ByteArrayOutputStream drefPayload = new ByteArrayOutputStream();
        writeInt(drefPayload, 1);
        drefPayload.write(urlBox, 0, urlBox.length);
        return makeBox("dinf", makeFullBox("dref", drefPayload.toByteArray()));
    }

    private static byte[] makeStsd(byte[] asc, int timescale) {
        byte[] esds = makeEsds(asc);
        ByteArrayOutputStream mp4a = new ByteArrayOutputStream();
        writeInt(mp4a, 8 + 28 + esds.length);
        mp4a.write(toBytes("mp4a"), 0, 4);
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
        stsd.write(toBytes("stsd"), 0, 4);
        writeInt(stsd, 0);
        writeInt(stsd, 1);
        stsd.write(mp4aBytes, 0, mp4aBytes.length);
        return stsd.toByteArray();
    }

    private static byte[] makeEsds(byte[] asc) {
        ByteArrayOutputStream dsi = new ByteArrayOutputStream();
        dsi.write(0x05);
        writeVarLen(dsi, asc.length);
        dsi.write(asc, 0, asc.length);
        byte[] dsiB = dsi.toByteArray();
        ByteArrayOutputStream dc = new ByteArrayOutputStream();
        dc.write(0x04);
        writeVarLen(dc, 13 + dsiB.length);
        dc.write(0x40);
        dc.write(0x15);
        writeBytes(dc, new byte[] { 0, 0, 0 });
        writeInt(dc, 0x1FFFFF);
        writeInt(dc, 0x1FFFFF);
        dc.write(dsiB, 0, dsiB.length);
        byte[] dcB = dc.toByteArray();
        byte[] sl = { 0x06, 0x01, 0x02 };
        ByteArrayOutputStream es = new ByteArrayOutputStream();
        es.write(0x03);
        writeVarLen(es, 3 + dcB.length + sl.length);
        writeShort(es, 1);
        es.write(0);
        es.write(dcB, 0, dcB.length);
        es.write(sl, 0, sl.length);
        byte[] esB = es.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 12 + esB.length);
        out.write(toBytes("esds"), 0, 4);
        writeInt(out, 0);
        out.write(esB, 0, esB.length);
        return out.toByteArray();
    }

    private static byte[] makeStts(int totalSamples, int[] durs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 16 + 8);
        out.write(toBytes("stts"), 0, 4);
        writeInt(out, 0);
        writeInt(out, 1);
        writeInt(out, totalSamples);
        writeInt(out, durs[0]);
        return out.toByteArray();
    }

    private static byte[] makeStsz(int[] sizes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 20 + sizes.length * 4);
        out.write(toBytes("stsz"), 0, 4);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, sizes.length);
        for (int s : sizes)
            writeInt(out, s);
        return out.toByteArray();
    }

    private static byte[] makeStsc(int totalSamples) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 16 + 12);
        out.write(toBytes("stsc"), 0, 4);
        writeInt(out, 0);
        writeInt(out, 1);
        writeInt(out, 1);
        writeInt(out, totalSamples);
        writeInt(out, 1);
        return out.toByteArray();
    }

    private static byte[] makeStco(long offset) {
        // stco box 用 32-bit offset；对大于 4GB 的文件应使用 co64。
        // 对于音频流，stco offset 为 mdat 起始偏移，通常远小于 4GB。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 16 + 4);
        out.write(toBytes("stco"), 0, 4);
        writeInt(out, 0);
        writeInt(out, 1);
        writeInt(out, (int) offset);
        return out.toByteArray();
    }

    private static byte[] makeMdhd(int timescale, long duration) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 32);
        out.write(toBytes("mdhd"), 0, 4);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, timescale);
        writeInt(out, (int) duration);
        writeShort(out, 0x55C4);
        writeShort(out, 0);
        return out.toByteArray();
    }

    private static byte[] makeHdlr() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 45);
        out.write(toBytes("hdlr"), 0, 4);
        writeInt(out, 0);
        writeInt(out, 0);
        out.write(toBytes("soun"), 0, 4);
        writeBytes(out, new byte[12]);
        writeBytes(out, new byte[] { 'S', 'o', 'u', 'n', 'd', 'H', 'a', 'n', 'd', 'l', 'e', 'r', 0 });
        return out.toByteArray();
    }

    private static byte[] makeTkhd(int trackId, long duration) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 92);
        out.write(toBytes("tkhd"), 0, 4);
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

    private static byte[] makeMvhd(int timescale, long duration) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 108);
        out.write(toBytes("mvhd"), 0, 4);
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

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    private static void writeVarLen(ByteArrayOutputStream out, int size) {
        if (size < 0x80) {
            out.write(size);
            return;
        }
        int n = 0;
        for (int t = size; t > 0; t >>= 7)
            n++;
        for (int i = n - 1; i >= 0; i--)
            out.write(((size >> (7 * i)) & 0x7F) | (i > 0 ? 0x80 : 0));
    }

    private static byte[] toBytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    private static BoxHeader readBoxHeader(ByteBuffer buf) {
        if (buf.remaining() < 8)
            return null;
        long size = buf.getInt() & 0xFFFFFFFFL;
        byte[] tb = new byte[4];
        buf.get(tb);
        String type = new String(tb);
        int hs = 8;
        if (size == 1) {
            if (buf.remaining() < 8)
                return null;
            size = buf.getLong();
            hs = 16;
        } else if (size == 0)
            size = buf.remaining() + hs;
        if (size < hs)
            return null;
        return new BoxHeader(size, type, hs);
    }

    private static class BoxHeader {
        final long size;
        final String type;
        final int headerSize;

        BoxHeader(long s, String t, int h) {
            size = s;
            type = t;
            headerSize = h;
        }

        long dataSize() {
            return size - headerSize;
        }
    }

    public static ParseResult parseMoof(byte[] data) {
        ParseResult r = new ParseResult();
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int td = 0;
        while (b.remaining() >= 8) {
            int s = b.getInt();
            String t = read4cc(b);
            if (s < 8 || s - 8 > b.remaining())
                break;
            int cs = s - 8;
            byte[] cd = new byte[cs];
            b.get(cd);
            if ("traf".equals(t))
                td = parseTraf(cd, r, td);
        }
        return r;
    }

    private static int parseTraf(byte[] data, ParseResult r, int prevDefault) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int ds = prevDefault;
        while (b.remaining() >= 8) {
            int s = b.getInt();
            String t = read4cc(b);
            if (s < 8 || s - 8 > b.remaining())
                break;
            int cs = s - 8;
            byte[] cd = new byte[cs];
            b.get(cd);
            if ("tfhd".equals(t))
                ds = parseTfhd(cd, r);
            else if ("tfdt".equals(t))
                parseTfdt(cd, r);
            else if ("trun".equals(t))
                parseTrun(cd, r, ds);
        }
        return ds;
    }

    private static int parseTfhd(byte[] cd, ParseResult r) {
        if (cd.length < 8)
            return 0;
        int flags = ((cd[1] & 0xFF) << 16) | ((cd[2] & 0xFF) << 8) | (cd[3] & 0xFF);
        ByteBuffer bb = ByteBuffer.wrap(cd).order(ByteOrder.BIG_ENDIAN);
        int pos = 8;
        if ((flags & TFHD_BASE_DATA_OFFSET) != 0) {
            pos += 8;
        }
        if ((flags & TFHD_SAMPLE_DESCRIPTION_INDEX) != 0) {
            pos += 4;
        }
        if ((flags & TFHD_DEFAULT_SAMPLE_DURATION) != 0) {
            if (pos + 4 <= cd.length) {
                r.defaultSampleDuration = bb.getInt(pos) & 0xFFFFFFFFL;
            }
            pos += 4;
        }
        if ((flags & TFHD_DEFAULT_SAMPLE_SIZE) != 0 && pos + 4 <= cd.length) {
            return bb.getInt(pos);
        }
        if ((flags & TFHD_DEFAULT_SAMPLE_FLAGS) != 0) {
            pos += 4;
        }
        return 0;
    }

    private static void parseTfdt(byte[] cd, ParseResult r) {
        if (cd.length < 8) {
            return;
        }
        int version = cd[0] & 0xFF;
        ByteBuffer bb = ByteBuffer.wrap(cd).order(ByteOrder.BIG_ENDIAN);
        if (version == 1 && cd.length >= 12) {
            r.baseMediaDecodeTime = Math.max(0L, bb.getLong(4));
        } else {
            r.baseMediaDecodeTime = bb.getInt(4) & 0xFFFFFFFFL;
        }
    }

    private static void parseTrun(byte[] cd, ParseResult r, int defaultSampleSize) {
        if (cd.length < 8)
            return;
        int flags = ((cd[1] & 0xFF) << 16) | ((cd[2] & 0xFF) << 8) | (cd[3] & 0xFF);
        ByteBuffer trun = ByteBuffer.wrap(cd).order(ByteOrder.BIG_ENDIAN);
        int pos = 4;
        int sc = trun.getInt(pos);
        pos += 4;
        boolean hdo = (flags & TRUN_DATA_OFFSET) != 0, hfs = (flags & TRUN_FIRST_SAMPLE_FLAGS) != 0;
        boolean hsd = (flags & TRUN_SAMPLE_DURATION) != 0, hss = (flags & TRUN_SAMPLE_SIZE) != 0;
        boolean hsf = (flags & TRUN_SAMPLE_FLAGS) != 0, hct = (flags & TRUN_SAMPLE_COMP_TIME) != 0;
        if (hdo && pos + 4 <= cd.length)
            pos += 4;
        if (hfs && pos + 4 <= cd.length)
            pos += 4;
        r.sampleCount += sc;
        r.ensureTimingCapacity(r.sampleDurations != null ? r.sampleDurations.length + sc : sc);
        int ti = r.sampleDurations.length - sc;
        if (hss && sc > 0) {
            r.ensureCapacity(r.sampleSizes != null ? r.sampleSizes.length + sc : sc);
            int wi = r.sampleSizes.length - sc;
            for (int i = 0; i < sc; i++) {
                if (hsd && pos + 4 <= cd.length) {
                    r.sampleDurations[ti + i] = trun.getInt(pos) & 0xFFFFFFFFL;
                    pos += 4;
                }
                if (pos + 4 <= cd.length) {
                    r.sampleSizes[wi + i] = trun.getInt(pos);
                    pos += 4;
                }
                if (hsf && pos + 4 <= cd.length)
                    pos += 4;
                if (hct && pos + 4 <= cd.length) {
                    r.sampleCompositionOffsets[ti + i] = trun.getInt(pos);
                    pos += 4;
                }
            }
        } else {
            for (int i = 0; i < sc; i++) {
                if (hsd && pos + 4 <= cd.length) {
                    r.sampleDurations[ti + i] = trun.getInt(pos) & 0xFFFFFFFFL;
                    pos += 4;
                }
                if (hss && pos + 4 <= cd.length)
                    pos += 4;
                if (hsf && pos + 4 <= cd.length)
                    pos += 4;
                if (hct && pos + 4 <= cd.length) {
                    r.sampleCompositionOffsets[ti + i] = trun.getInt(pos);
                    pos += 4;
                }
            }
            if (defaultSampleSize > 0)
                r.defaultSampleSize = defaultSampleSize;
        }
    }

    public static ParseResult parseMoov(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        ParseResult r = new ParseResult();
        while (b.remaining() >= 8) {
            int s = b.getInt();
            String t = read4cc(b);
            if (s < 8 || s - 8 > b.remaining())
                break;
            int cs = s - 8;
            byte[] cd = new byte[cs];
            b.get(cd);
            if ("trak".equals(t))
                parseTrak(cd, r);
        }
        return r;
    }

    public static int parseVideoTimescale(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        while (b.remaining() >= 8) {
            int s = b.getInt();
            String t = read4cc(b);
            if (s < 8 || s - 8 > b.remaining())
                break;
            int cs = s - 8;
            byte[] cd = new byte[cs];
            b.get(cd);
            if ("trak".equals(t) && isTrackType(cd, "vide")) {
                int timescale = parseTrackTimescale(cd);
                if (timescale > 0) {
                    return timescale;
                }
            }
        }
        return 0;
    }

    private static void parseTrak(byte[] data, ParseResult r) {
        if (!isAudioTrack(data))
            return;
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        while (b.remaining() >= 8) {
            int s = b.getInt();
            String t = read4cc(b);
            if (s < 8 || s - 8 > b.remaining())
                break;
            int cs = s - 8;
            byte[] cd = new byte[cs];
            b.get(cd);
            if ("mdia".equals(t))
                parseMdia(cd, r);
        }
    }

    private static boolean isAudioTrack(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);
        while (b.remaining() >= 8) {
            int s = b.getInt();
            String t = read4cc(b);
            if (s < 8 || s - 8 > b.remaining())
                break;
            int cs = s - 8;
            if ("hdlr".equals(t)) {
                byte[] hd = new byte[cs];
                b.get(hd);
                if (cs >= 12 && "soun".equals(new String(hd, 8, 4)))
                    return true;
            } else if ("mdia".equals(t)) {
                byte[] md = new byte[cs];
                b.get(md);
                if (isAudioTrack(md))
                    return true;
            } else
                b.position(b.position() + cs);
        }
        return indexOf(d, "mp4a".getBytes()) >= 0;
    }

    private static boolean isTrackType(byte[] d, String handlerType) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);
        while (b.remaining() >= 8) {
            int s = b.getInt();
            String t = read4cc(b);
            if (s < 8 || s - 8 > b.remaining())
                break;
            int cs = s - 8;
            if ("hdlr".equals(t)) {
                byte[] hd = new byte[cs];
                b.get(hd);
                if (cs >= 12 && handlerType.equals(new String(hd, 8, 4)))
                    return true;
            } else if ("mdia".equals(t)) {
                byte[] md = new byte[cs];
                b.get(md);
                if (isTrackType(md, handlerType))
                    return true;
            } else
                b.position(b.position() + cs);
        }
        return false;
    }

    private static int parseTrackTimescale(byte[] trakData) {
        ByteBuffer b = ByteBuffer.wrap(trakData).order(ByteOrder.BIG_ENDIAN);
        while (b.remaining() >= 8) {
            int s = b.getInt();
            String t = read4cc(b);
            if (s < 8 || s - 8 > b.remaining())
                break;
            int cs = s - 8;
            byte[] cd = new byte[cs];
            b.get(cd);
            if ("mdia".equals(t)) {
                int timescale = parseMdiaTimescale(cd);
                if (timescale > 0) {
                    return timescale;
                }
            }
        }
        return 0;
    }

    private static int parseMdiaTimescale(byte[] mdiaData) {
        ByteBuffer b = ByteBuffer.wrap(mdiaData).order(ByteOrder.BIG_ENDIAN);
        while (b.remaining() >= 8) {
            int s = b.getInt();
            String t = read4cc(b);
            if (s < 8 || s - 8 > b.remaining())
                break;
            int cs = s - 8;
            byte[] cd = new byte[cs];
            b.get(cd);
            if ("mdhd".equals(t)) {
                return readMdhdTimescale(cd);
            }
        }
        return 0;
    }

    private static int readMdhdTimescale(byte[] cd) {
        if (cd.length < 20) {
            return 0;
        }
        int version = cd[0] & 0xFF;
        ByteBuffer bb = ByteBuffer.wrap(cd).order(ByteOrder.BIG_ENDIAN);
        int timescale = 0;
        if (version == 1 && cd.length >= 32) {
            timescale = bb.getInt(20);
        } else if (version == 0) {
            timescale = bb.getInt(12);
        }
        return Math.max(0, timescale);
    }

    private static void parseMdia(byte[] d, ParseResult r) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);
        while (b.remaining() >= 8) {
            int s = b.getInt();
            String t = read4cc(b);
            if (s < 8 || s - 8 > b.remaining())
                break;
            int cs = s - 8;
            byte[] cd = new byte[cs];
            b.get(cd);
            if ("mdhd".equals(t))
                parseMdhd(cd, r);
            else if ("minf".equals(t))
                parseMinf(cd, r);
        }
    }

    private static void parseMdhd(byte[] cd, ParseResult r) {
        if (cd.length < 20) {
            return;
        }
        int version = cd[0] & 0xFF;
        ByteBuffer bb = ByteBuffer.wrap(cd).order(ByteOrder.BIG_ENDIAN);
        int timescale = 0;
        if (version == 1 && cd.length >= 32) {
            timescale = bb.getInt(20);
        } else if (version == 0) {
            timescale = bb.getInt(12);
        }
        if (timescale > 0) {
            r.timescale = timescale;
        }
    }

    private static void parseMinf(byte[] d, ParseResult r) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);
        while (b.remaining() >= 8) {
            int s = b.getInt();
            String t = read4cc(b);
            if (s < 8 || s - 8 > b.remaining())
                break;
            int cs = s - 8;
            byte[] cd = new byte[cs];
            b.get(cd);
            if ("stbl".equals(t))
                parseStbl(cd, r);
        }
    }

    private static void parseStbl(byte[] d, ParseResult r) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);
        while (b.remaining() >= 8) {
            int s = b.getInt();
            String t = read4cc(b);
            if (s < 8 || s - 8 > b.remaining())
                break;
            int cs = s - 8;
            byte[] cd = new byte[cs];
            b.get(cd);
            if ("stsd".equals(t))
                parseStsd(cd, r);
        }
    }

    private static void parseStsd(byte[] d, ParseResult r) {
        if (d.length < 16)
            return;
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);
        b.position(4);
        int ec = b.getInt();
        int rem = d.length - 8;
        for (int i = 0; i < ec && rem >= 8; i++) {
            int es = b.getInt();
            String et = read4cc(b);
            if (es < 8 || es > rem)
                break;
            if ("mp4a".equals(et)) {
                byte[] md = new byte[es - 8];
                b.get(md);
                r.asc = extractAscFromMp4a(md);
                r.audioCodec = "mp4a";
                if (r.asc != null) {
                    AudioFormat af = ascToAudioFormat(r.asc);
                    if (af != null) {
                        LOGGER.debug("AAC ASC 提取成功: {}Hz/{}ch", af.getSampleRate(), af.getChannels());
                    }
                    return;
                }
            } else if ("fLaC".equals(et) && es >= 8 + 28) {
                byte[] md = new byte[es - 8];
                b.get(md);
                r.flacDfLa = extractDfLaFromFlacStsd(md);
                r.audioCodec = "fLaC";
                if (r.flacDfLa != null)
                    return;
            } else if ("ec-3".equals(et)) {
                r.audioCodec = "ec-3";
                return;
            } else
                b.position(b.position() + es - 8);
            rem -= es;
        }
    }

    private static byte[] extractAscFromMp4a(byte[] d) {
        if (d.length < 28)
            return null;
        int pos = 28;
        while (pos + 8 <= d.length) {
            int bs = readIntBE(d, pos);
            String bt = new String(d, pos + 4, 4);
            if (bs == 0) {
                pos += 4;
                continue;
            }
            if (bs < 8 || pos + bs > d.length) {
                pos += 4;
                continue;
            }
            if ("esds".equals(bt))
                return extractAscFromEsds(d, pos + 8, bs - 8);
            pos += bs;
        }
        return null;
    }

    private static byte[] extractAscFromEsds(byte[] d, int start, int len) {
        int pos = start + 4;
        int end = Math.min(d.length, start + len);
        return findAscDescriptor(d, pos, end);
    }

    private static byte[] findAscDescriptor(byte[] d, int start, int end) {
        int pos = start;
        while (pos < end - 1) {
            int tag = d[pos++] & 0xFF;
            if (tag == 0)
                continue;
            int dl = 0;
            for (int i = 0; i < 4 && pos < end; i++) {
                int b = d[pos++] & 0xFF;
                dl = (dl << 7) | (b & 0x7F);
                if ((b & 0x80) == 0)
                    break;
            }
            int ps = pos, pe = Math.min(end, ps + dl);
            if (tag == 0x05 && dl >= 2) {
                byte[] asc = new byte[pe - ps];
                System.arraycopy(d, ps, asc, 0, asc.length);
                return asc;
            }
            int ns = ps;
            if (tag == 0x03 && ps + 3 <= pe) {
                int flags = d[ps + 2] & 0xFF;
                ns = ps + 3;
                if ((flags & 0x80) != 0)
                    ns += 2;
                if ((flags & 0x40) != 0 && ns < pe)
                    ns += 1 + (d[ns] & 0xFF);
                if ((flags & 0x20) != 0)
                    ns += 2;
            } else if (tag == 0x04)
                ns = ps + 13;
            if (ns > ps && ns < pe) {
                byte[] nested = findAscDescriptor(d, ns, pe);
                if (nested != null)
                    return nested;
            }
            pos = pe;
        }
        return null;
    }

    private static String read4cc(ByteBuffer b) {
        byte[] x = new byte[4];
        b.get(x);
        return new String(x);
    }

    private static int readIntBE(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16) | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    private static int indexOf(byte[] h, byte[] n) {
        outer: for (int i = 0; i <= h.length - n.length; i++) {
            for (int j = 0; j < n.length; j++)
                if (h[i + j] != n[j])
                    continue outer;
            return i;
        }
        return -1;
    }

    /** 扫描 moov 中所有音频轨的 fourcc 编码类型，用于诊断不支持的编码格式。 */
    public static String listAudioCodecs(byte[] moovData) {
        java.util.List<String> codecs = new java.util.ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(moovData).order(ByteOrder.BIG_ENDIAN);
        while (buf.remaining() >= 8) {
            int s = buf.getInt();
            String t = read4cc(buf);
            if (s < 8 || s - 8 > buf.remaining())
                break;
            int cs = s - 8;
            byte[] cd = new byte[cs];
            buf.get(cd);
            if ("trak".equals(t)) {
                String c = findStsdCodec(cd);
                if (c != null)
                    codecs.add(c);
            }
        }
        return codecs.isEmpty() ? "(未找到音频轨)" : String.join(", ", codecs);
    }

    private static String findStsdCodec(byte[] trakData) {
        int stsd = indexOf(trakData, "stsd".getBytes());
        if (stsd < 0)
            return null;
        ByteBuffer b = ByteBuffer.wrap(trakData).order(ByteOrder.BIG_ENDIAN);
        if (stsd + 16 > b.remaining())
            return null;
        b.position(stsd + 12);
        int ec = b.getInt();
        if (ec < 1 || b.remaining() < 8)
            return null;
        b.getInt(); // skip entry size
        return read4cc(b); // first entry fourcc
    }

    public static class ParseResult {
        public String audioCodec;
        public byte[] asc;
        public byte[] flacDfLa;
        public int defaultSampleSize;
        public long defaultSampleDuration;
        public int sampleCount;
        public int[] sampleSizes;
        public long[] sampleDurations;
        public long[] sampleCompositionOffsets;
        public int timescale;
        public long baseMediaDecodeTime = -1L;

        void ensureCapacity(int min) {
            if (sampleSizes == null)
                sampleSizes = new int[min];
            else if (sampleSizes.length < min) {
                int[] o = sampleSizes;
                sampleSizes = new int[min];
                System.arraycopy(o, 0, sampleSizes, 0, o.length);
            }
        }

        void ensureTimingCapacity(int min) {
            if (sampleDurations == null) {
                sampleDurations = new long[min];
                sampleCompositionOffsets = new long[min];
            } else if (sampleDurations.length < min) {
                long[] oldDurations = sampleDurations;
                long[] oldOffsets = sampleCompositionOffsets;
                sampleDurations = new long[min];
                sampleCompositionOffsets = new long[min];
                System.arraycopy(oldDurations, 0, sampleDurations, 0, oldDurations.length);
                System.arraycopy(oldOffsets, 0, sampleCompositionOffsets, 0, oldOffsets.length);
            }
        }
    }

    public record SampleTable(int[] sampleSizes, long[] ptsNanos) {
        public static final SampleTable EMPTY = new SampleTable(new int[0], new long[0]);
    }
}
