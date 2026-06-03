package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Fmp4StreamParser {
    private static final int FORMAT_SNIFF_SIZE = 64;
    private static final int MAX_METADATA_BOX_SIZE = 16 * 1024 * 1024;
    private static final int SKIP_BUFFER_SIZE = 8192;

    public void parse(InputStream source, AtomicBoolean closed, Callback callback)
            throws IOException, UnsupportedAudioFileException {
        PushbackInputStream in = new PushbackInputStream(source, FORMAT_SNIFF_SIZE);
        byte[] sniff = new byte[12];
        int sniffLen = readSniffBytes(in, sniff, sniff.length);
        if (sniffLen > 0) {
            in.unread(sniff, 0, sniffLen);
        }

        boolean isFmp4 = sniffLen >= 8
                && sniff[4] == 'f' && sniff[5] == 't' && sniff[6] == 'y' && sniff[7] == 'p';
        boolean isRawEac3 = sniffLen >= 2
                && (sniff[0] & 0xFF) == 0x0B && (sniff[1] & 0xFF) == 0x77;

        if (isRawEac3) {
            callback.onRawEac3(in);
            return;
        }
        if (!isFmp4) {
            throw new UnsupportedAudioFileException("unsupported HTTP audio stream: not fMP4 or raw E-AC-3");
        }

        BoxHeader box;
        while (!closed.get() && (box = readBoxHeader(in)) != null) {
            if (box.dataSize < 0) {
                throw new IOException("unknown-length MP4 box is not supported: " + box.type);
            }
            switch (box.type) {
                case "ftyp" -> {
                    callback.onFtyp(box.dataSize);
                    skipFully(in, box.dataSize);
                }
                case "moov" -> {
                    byte[] moovData = readFullyBounded(in, box.dataSize, MAX_METADATA_BOX_SIZE, "moov");
                    callback.onMoov(Fmp4ToMp4Converter.parseMoov(moovData), moovData);
                }
                case "moof" -> {
                    byte[] moofData = readFullyBounded(in, box.dataSize, MAX_METADATA_BOX_SIZE, "moof");
                    callback.onMoof(Fmp4ToMp4Converter.extractSampleSizesFromMoof(moofData), moofData);
                }
                case "mdat" -> {
                    BoundedInputStream payload = new BoundedInputStream(in, box.dataSize);
                    callback.onMdat(payload, box.dataSize);
                    if (closed.get()) {
                        return;
                    }
                    payload.drain();
                }
                default -> skipFully(in, box.dataSize);
            }
        }
    }

    public static byte[] readFully(InputStream in, long length) throws IOException {
        if (length > Integer.MAX_VALUE) {
            throw new IOException("MP4 box too large to buffer: " + length);
        }
        byte[] data = new byte[(int) length];
        readFullyInto(in, data, 0, data.length);
        return data;
    }

    public static void skipFully(InputStream in, long length) throws IOException {
        byte[] buffer = new byte[SKIP_BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            int toRead = (int) Math.min(buffer.length, remaining);
            int n = in.read(buffer, 0, toRead);
            if (n < 0) {
                throw new EOFException("EOF while skipping MP4 payload");
            }
            remaining -= n;
        }
    }

    private static byte[] readFullyBounded(InputStream in, long length, int maxLength, String boxType)
            throws IOException {
        if (length > maxLength) {
            throw new IOException("MP4 " + boxType + " box too large: " + length + " > " + maxLength);
        }
        return readFully(in, length);
    }

    private static void readFullyInto(InputStream in, byte[] data, int offset, int length) throws IOException {
        int readTotal = 0;
        while (readTotal < length) {
            int n = in.read(data, offset + readTotal, length - readTotal);
            if (n < 0) {
                throw new EOFException("EOF while reading MP4 payload");
            }
            readTotal += n;
        }
    }

    private static int readSniffBytes(InputStream in, byte[] buf, int maxLen) throws IOException {
        int total = 0;
        while (total < maxLen) {
            int r = in.read(buf, total, maxLen - total);
            if (r < 0) {
                break;
            }
            total += r;
        }
        return total;
    }

    private static BoxHeader readBoxHeader(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
        }

        byte[] header = new byte[8];
        header[0] = (byte) first;
        readFullyInto(in, header, 1, 7);

        long size = readUInt32(header, 0);
        String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
        int headerSize = 8;
        if (size == 1) {
            byte[] ext = readFully(in, 8);
            size = readUInt64(ext, 0);
            headerSize = 16;
        } else if (size == 0) {
            return new BoxHeader(type, -1);
        }

        long dataSize = size - headerSize;
        if (dataSize < 0) {
            throw new IOException("invalid MP4 box size: type=" + type + ", size=" + size);
        }
        return new BoxHeader(type, dataSize);
    }

    private static long readUInt32(byte[] data, int offset) {
        return ((long) data[offset] & 0xFF) << 24
                | ((long) data[offset + 1] & 0xFF) << 16
                | ((long) data[offset + 2] & 0xFF) << 8
                | ((long) data[offset + 3] & 0xFF);
    }

    private static long readUInt64(byte[] data, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }

    public interface Callback {
        default void onFtyp(long size) throws IOException {
        }

        void onMoov(Fmp4ToMp4Converter.ParseResult parseResult, byte[] moovData)
                throws IOException, UnsupportedAudioFileException;

        void onMoof(int[] sampleSizes, byte[] moofData) throws IOException;

        void onMdat(InputStream payload, long size) throws IOException;

        void onRawEac3(InputStream payload) throws IOException, UnsupportedAudioFileException;
    }

    private static final class BoundedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private BoundedInputStream(InputStream delegate, long length) {
            this.delegate = delegate;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int n = delegate.read(b, off, toRead);
            if (n < 0) {
                throw new EOFException("EOF inside bounded MP4 payload");
            }
            remaining -= n;
            return n;
        }

        private void drain() throws IOException {
            skipFully(this, remaining);
        }
    }

    private record BoxHeader(String type, long dataSize) {
    }
}
