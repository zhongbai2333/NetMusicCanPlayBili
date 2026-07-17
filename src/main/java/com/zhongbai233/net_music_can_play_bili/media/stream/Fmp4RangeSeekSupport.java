package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 音频和视频 DASH 流共用的 fMP4 范围跳转辅助工具。
 */
public final class Fmp4RangeSeekSupport {
    private Fmp4RangeSeekSupport() {
    }

    public static InitSegment extractInitSegment(byte[] prefix, long contentLength, TimescaleResolver resolver) {
        int pos = 0;
        while (pos + 8 <= prefix.length) {
            Mp4Box box = readCompleteMp4Box(prefix, pos, prefix.length);
            if (box == null) {
                return null;
            }
            if (isBoxType(prefix, pos + 4, 'm', 'o', 'o', 'v')) {
                byte[] initBytes = Arrays.copyOf(prefix, pos + (int) box.size());
                byte[] moovPayload = Arrays.copyOfRange(prefix, pos + box.headerSize(), pos + (int) box.size());
                Fmp4ToMp4Converter.ParseResult moov = Fmp4ToMp4Converter.parseMoov(moovPayload);
                int timescale = resolver != null ? resolver.resolve(moovPayload, moov) : moov.timescale;
                return new InitSegment(initBytes, contentLength, timescale);
            }
            pos += (int) box.size();
        }
        return null;
    }

    public static MoofProbe readMoofProbe(InputStream range, float targetSeconds, int timescale,
            int maxScanBytes, double targetEpsilonSeconds, double closeFragmentSeconds) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[256 * 1024];
        MoofCandidate best = null;
        while (out.size() < maxScanBytes) {
            int request = Math.min(buffer.length, maxScanBytes - out.size());
            int n = range.read(buffer, 0, request);
            if (n < 0) {
                break;
            }
            if (n == 0) {
                continue;
            }
            out.write(buffer, 0, n);
            byte[] data = out.toByteArray();
            best = findBestMoofCandidate(data, data.length, targetSeconds, timescale, targetEpsilonSeconds);
            if (isCloseCandidate(best, targetSeconds, targetEpsilonSeconds, closeFragmentSeconds)) {
                return new MoofProbe(data, best);
            }
        }
        byte[] data = out.toByteArray();
        best = best != null ? best
                : findBestMoofCandidate(data, data.length, targetSeconds, timescale,
                        targetEpsilonSeconds);
        return best != null ? new MoofProbe(data, best) : null;
    }

    public static boolean shouldRetry(MoofCandidate candidate, float targetSeconds,
            double targetEpsilonSeconds, double closeFragmentSeconds) {
        if (candidate == null || Double.isNaN(candidate.fragmentSeconds())) {
            return false;
        }
        double delta = targetSeconds - candidate.fragmentSeconds();
        return delta < -targetEpsilonSeconds || delta > closeFragmentSeconds;
    }

    public static boolean isAfterTargetCandidate(MoofCandidate candidate, float targetSeconds,
            double targetEpsilonSeconds) {
        return candidate != null
                && !Double.isNaN(candidate.fragmentSeconds())
                && candidate.fragmentSeconds() > targetSeconds + targetEpsilonSeconds;
    }

    public static long nextRangeStart(MoofCandidate candidate, float targetSeconds, double durationSeconds,
            long contentLength, long absoluteMoofOffset, int initLength, long prerollBytes) {
        double bytesPerSecond = contentLength / Math.max(1.0D, durationSeconds);
        double deltaSeconds = targetSeconds - candidate.fragmentSeconds();
        long adjusted = absoluteMoofOffset + Math.round(deltaSeconds * bytesPerSecond) - prerollBytes;
        return Math.max(initLength, Math.min(contentLength - 1L, adjusted));
    }

    public static float residualSeconds(float targetSeconds, MoofCandidate candidate, double durationSeconds,
            long contentLength, long absoluteMoofOffset) {
        double fragmentSeconds = candidate.fragmentSeconds();
        if (Double.isNaN(fragmentSeconds) && contentLength > 0L && durationSeconds > 0.0D) {
            fragmentSeconds = durationSeconds * (absoluteMoofOffset / (double) contentLength);
        }
        if (Double.isNaN(fragmentSeconds)) {
            return targetSeconds;
        }
        return (float) Math.max(0.0D, Math.min(targetSeconds, targetSeconds - fragmentSeconds));
    }

    public static SidxIndex parseSidx(byte[] data, long absoluteStart) {
        int sidxOffset = -1;
        Mp4Box sidxBox = null;
        for (int pos = 0; pos + 8 <= data.length;) {
            Mp4Box box = readCompleteMp4Box(data, pos, data.length);
            if (box == null || box.size() <= 0L || box.size() > Integer.MAX_VALUE) {
                break;
            }
            if (isBoxType(data, pos + 4, 's', 'i', 'd', 'x')) {
                sidxOffset = pos;
                sidxBox = box;
                break;
            }
            pos += (int) box.size();
        }
        if (sidxOffset < 0 || sidxBox == null) {
            return null;
        }
        int p = sidxOffset + sidxBox.headerSize();
        int end = sidxOffset + (int) sidxBox.size();
        if (p + 12 > end) {
            return null;
        }
        int version = data[p] & 0xFF;
        p += 4; // version + flags
        p += 4; // reference_ID
        long timescale = readUInt32(data, p);
        p += 4;
        long earliestPresentationTime;
        long firstOffset;
        if (version == 0) {
            if (p + 8 > end) {
                return null;
            }
            earliestPresentationTime = readUInt32(data, p);
            p += 4;
            firstOffset = readUInt32(data, p);
            p += 4;
        } else {
            if (p + 16 > end) {
                return null;
            }
            earliestPresentationTime = readUInt64(data, p);
            p += 8;
            firstOffset = readUInt64(data, p);
            p += 8;
        }
        if (p + 4 > end || timescale <= 0L) {
            return null;
        }
        p += 2; // reserved
        int referenceCount = ((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF);
        p += 2;
        long currentTime = earliestPresentationTime;
        long currentByte = absoluteStart + sidxOffset + sidxBox.size() + firstOffset;
        List<SidxEntry> entries = new ArrayList<>();
        for (int i = 0; i < referenceCount && p + 12 <= end; i++) {
            long ref = readUInt32(data, p);
            p += 4;
            boolean referenceType = (ref & 0x8000_0000L) != 0L;
            long size = ref & 0x7FFF_FFFFL;
            long duration = readUInt32(data, p);
            p += 4;
            long sap = readUInt32(data, p);
            p += 4;
            boolean startsWithSap = (sap & 0x8000_0000L) != 0L;
            if (!referenceType && size > 0L) {
                entries.add(new SidxEntry(currentTime / (double) timescale, currentByte, currentByte + size - 1L,
                        startsWithSap));
            }
            currentTime += duration;
            currentByte += size;
        }
        return entries.isEmpty() ? null : new SidxIndex(timescale, entries);
    }

    public static Mp4Box readCompleteMp4Box(byte[] data, int offset, int length) {
        if (offset < 0 || offset + 8 > length) {
            return null;
        }
        long size = readUInt32(data, offset);
        int headerSize = 8;
        if (size == 1L) {
            if (offset + 16 > length) {
                return null;
            }
            size = readUInt64(data, offset + 8);
            headerSize = 16;
        }
        if (size < headerSize || size > length - offset) {
            return null;
        }
        return new Mp4Box(size, headerSize);
    }

    public static long readUInt32(byte[] data, int offset) {
        return ((long) data[offset] & 0xFF) << 24
                | ((long) data[offset + 1] & 0xFF) << 16
                | ((long) data[offset + 2] & 0xFF) << 8
                | ((long) data[offset + 3] & 0xFF);
    }

    public static long readUInt64(byte[] data, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }

    public static boolean isBoxType(byte[] data, int offset, char a, char b, char c, char d) {
        return offset >= 0 && offset + 4 <= data.length
                && data[offset] == (byte) a
                && data[offset + 1] == (byte) b
                && data[offset + 2] == (byte) c
                && data[offset + 3] == (byte) d;
    }

    private static boolean isCloseCandidate(MoofCandidate candidate, float targetSeconds,
            double targetEpsilonSeconds, double closeFragmentSeconds) {
        if (candidate == null || Double.isNaN(candidate.fragmentSeconds())) {
            return false;
        }
        double delta = targetSeconds - candidate.fragmentSeconds();
        return delta >= -targetEpsilonSeconds && delta <= closeFragmentSeconds;
    }

    private static MoofCandidate findBestMoofCandidate(byte[] probe, int length, float targetSeconds, int timescale,
            double targetEpsilonSeconds) {
        MoofCandidate first = null;
        MoofCandidate bestBeforeTarget = null;
        for (int i = 0; i + 8 <= length; i++) {
            if (!isBoxType(probe, i + 4, 'm', 'o', 'o', 'f')) {
                continue;
            }
            MoofCandidate candidate = readMoofCandidate(probe, i, length, timescale);
            if (candidate == null) {
                continue;
            }
            if (first == null) {
                first = candidate;
            }
            if (!Double.isNaN(candidate.fragmentSeconds())) {
                if (candidate.fragmentSeconds() <= targetSeconds + targetEpsilonSeconds) {
                    bestBeforeTarget = candidate;
                } else if (bestBeforeTarget != null) {
                    break;
                }
            }
            Mp4Box box = readCompleteMp4Box(probe, i, length);
            if (box != null && box.size() <= Integer.MAX_VALUE) {
                i += Math.max(0, (int) box.size() - 1);
            }
        }
        return bestBeforeTarget != null ? bestBeforeTarget : first;
    }

    private static MoofCandidate readMoofCandidate(byte[] probe, int offset, int length, int timescale) {
        Mp4Box box = readCompleteMp4Box(probe, offset, length);
        if (box == null || box.size() > Integer.MAX_VALUE || box.size() < box.headerSize()) {
            return null;
        }
        byte[] moofPayload = Arrays.copyOfRange(probe, offset + box.headerSize(), offset + (int) box.size());
        Fmp4ToMp4Converter.ParseResult moof = Fmp4ToMp4Converter.parseMoof(moofPayload);
        if (moof.sampleCount <= 0 && moof.baseMediaDecodeTime < 0L) {
            return null;
        }
        double fragmentSeconds = moof.baseMediaDecodeTime >= 0L && timescale > 0
                ? moof.baseMediaDecodeTime / (double) timescale
                : Double.NaN;
        return new MoofCandidate(offset, fragmentSeconds);
    }

    @FunctionalInterface
    public interface TimescaleResolver {
        int resolve(byte[] moovPayload, Fmp4ToMp4Converter.ParseResult moov);
    }

    public record InitSegment(byte[] bytes, long contentLength, int timescale) {
    }

    public record SidxIndex(long timescale, List<SidxEntry> entries) {
    }

    public record SidxEntry(double timeSeconds, long byteStart, long byteEnd, boolean startsWithSap) {
    }

    public record MoofCandidate(int offset, double fragmentSeconds) {
    }

    public record MoofProbe(byte[] bytes, MoofCandidate candidate) {
    }

    public record Mp4Box(long size, int headerSize) {
    }
}
