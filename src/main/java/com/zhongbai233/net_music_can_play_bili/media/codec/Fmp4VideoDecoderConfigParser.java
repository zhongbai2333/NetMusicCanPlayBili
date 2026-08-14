package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Parses codec configuration boxes and converts length-prefixed H.264 samples. */
final class Fmp4VideoDecoderConfigParser {
    private static final int CODEC_AV1 = 13;

    private Fmp4VideoDecoderConfigParser() {
    }

    static void writeLengthPrefixedSampleAsAnnexB(byte[] sample, int lengthSize, ByteArrayOutputStream out)
            throws IOException {
        int pos = 0;
        while (pos + lengthSize <= sample.length) {
            int nalSize = 0;
            for (int i = 0; i < lengthSize; i++) {
                nalSize = (nalSize << 8) | (sample[pos + i] & 0xFF);
            }
            pos += lengthSize;
            if (nalSize <= 0 || pos + nalSize > sample.length) {
                break;
            }
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(sample, pos, nalSize);
            pos += nalSize;
        }
    }

    static boolean isH264KeyframeSample(byte[] sample, int lengthSize) {
        int pos = 0;
        while (pos + lengthSize < sample.length) {
            int nalSize = 0;
            for (int i = 0; i < lengthSize; i++) {
                nalSize = (nalSize << 8) | (sample[pos + i] & 0xFF);
            }
            pos += lengthSize;
            if (nalSize <= 0 || pos + nalSize > sample.length) {
                return false;
            }
            if ((sample[pos] & 0x1F) == 5) {
                return true;
            }
            pos += nalSize;
        }
        return false;
    }

    static Fmp4NativeVideoDecoder.DecoderConfig extract(byte[] moovData, int codecId) {
        String configBox = codecId == CODEC_AV1 ? "av1C" : "avcC";
        byte[] config = findBoxPayloadRecursive(moovData, configBox);
        if (config == null) {
            return null;
        }
        return codecId == CODEC_AV1 ? parseAv1C(config) : parseAvcC(config);
    }

    static Fmp4NativeVideoDecoder.DecoderConfig parseAvcC(byte[] avcC) {
        if (avcC.length < 7) {
            return null;
        }
        int lengthSize = (avcC[4] & 0x03) + 1;
        int pos = 5;
        int spsCount = avcC[pos++] & 0x1F;
        List<byte[]> nalus = new ArrayList<>();
        for (int i = 0; i < spsCount && pos + 2 <= avcC.length; i++) {
            int len = readU16(avcC, pos);
            pos += 2;
            if (pos + len > avcC.length) {
                return null;
            }
            nalus.add(slice(avcC, pos, len));
            pos += len;
        }
        if (pos >= avcC.length) {
            return null;
        }
        int ppsCount = avcC[pos++] & 0xFF;
        for (int i = 0; i < ppsCount && pos + 2 <= avcC.length; i++) {
            int len = readU16(avcC, pos);
            pos += 2;
            if (pos + len > avcC.length) {
                return null;
            }
            nalus.add(slice(avcC, pos, len));
            pos += len;
        }
        return new Fmp4NativeVideoDecoder.DecoderConfig(lengthSize, toAnnexB(nalus));
    }

    static byte[] parseAv1ConfigObus(byte[] av1C) {
        if (av1C == null || av1C.length < 4 || (av1C[0] & 0x80) == 0 || (av1C[0] & 0x7F) != 1) {
            return null;
        }
        return slice(av1C, 4, av1C.length - 4);
    }

    private static Fmp4NativeVideoDecoder.DecoderConfig parseAv1C(byte[] av1C) {
        byte[] configObus = parseAv1ConfigObus(av1C);
        return configObus != null ? new Fmp4NativeVideoDecoder.DecoderConfig(0, configObus) : null;
    }

    private static byte[] toAnnexB(List<byte[]> nalus) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] nalu : nalus) {
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(nalu, 0, nalu.length);
        }
        return out.toByteArray();
    }

    private static byte[] findBoxPayloadRecursive(byte[] data, String targetType) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        while (buf.remaining() >= 8) {
            int boxStart = buf.position();
            long size = buf.getInt() & 0xFFFFFFFFL;
            String type = read4cc(buf);
            int headerSize = 8;
            if (size == 1) {
                if (buf.remaining() < 8) {
                    return null;
                }
                size = buf.getLong();
                headerSize = 16;
            } else if (size == 0) {
                size = data.length - boxStart;
            }
            long payloadSize = size - headerSize;
            if (size < headerSize || payloadSize < 0 || boxStart + size > data.length) {
                return null;
            }
            int payloadStart = boxStart + headerSize;
            if (targetType.equals(type)) {
                return slice(data, payloadStart, (int) payloadSize);
            }
            if (isContainerBox(type) && payloadSize > 0 && payloadSize <= Integer.MAX_VALUE) {
                int childOffset = childPayloadOffset(type);
                if (childOffset < payloadSize) {
                    byte[] nested = slice(data, payloadStart + childOffset, (int) payloadSize - childOffset);
                    byte[] found = findBoxPayloadRecursive(nested, targetType);
                    if (found != null) {
                        return found;
                    }
                }
            }
            buf.position((int) (boxStart + size));
        }
        return null;
    }

    private static boolean isContainerBox(String type) {
        return switch (type) {
            case "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "moof", "traf", "mvex" -> true;
            case "stsd", "avc1", "avc3", "av01" -> true;
            default -> false;
        };
    }

    private static int childPayloadOffset(String type) {
        return switch (type) {
            case "stsd" -> 8;
            case "avc1", "avc3", "av01" -> 78;
            default -> 0;
        };
    }

    private static String read4cc(ByteBuffer buffer) {
        byte[] bytes = new byte[4];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }
}
