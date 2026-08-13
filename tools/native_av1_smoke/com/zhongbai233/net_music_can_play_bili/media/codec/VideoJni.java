package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Standalone real-AV1 JNI smoke. The package/class intentionally matches the
 * production JNI symbol names, while remaining outside the Gradle source sets.
 */
public final class VideoJni {
    private static final int FIXTURE_WIDTH = 682;
    private static final int FIXTURE_HEIGHT = 360;
    private static final int FIXTURE_TIMESCALE = 16_000;
    private static final int FIXTURE_FPS = 25;

    private VideoJni() {
    }

    private static native long decoderOpenForCodecWithHwaccel(
            int codecId, int targetWidth, int targetHeight, String hwaccel);

    private static native int sendPacketWithPts(
            long handle, byte[] data, int offset, int length, long ptsNanos);

    private static native int receiveFrameNoCopy(long handle);

    private static native int getVideoFrameNv12IntoDirect(long handle, ByteBuffer output);

    private static native long getLastFramePtsNanos(long handle);

    private static native int sendEndOfStream(long handle);

    private static native String getHwaccelName(long handle);

    private static native long getDimensions(long handle);

    private static native void flush(long handle);

    private static native void close(long handle);

    private static native long[] getNativeMemoryStats();

    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4) {
            throw new IllegalArgumentException(
                    "usage: VideoJni <native-platform-directory> <fixture.m4s.b64|fragment.m4s|sample.ivf> <hwaccel> [seek-fragment.m4s.b64]");
        }
        Path nativeDirectory = Path.of(args[0]).toAbsolutePath();
        Path fixturePath = Path.of(args[1]).toAbsolutePath();
        loadLibraries(nativeDirectory);
        byte[] fixtureBytes = readFixture(fixturePath);
        String fixtureName = fixturePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        byte[] configObus = fixtureName.endsWith(".ivf") ? new byte[0] : parseAv1Config(fixtureBytes);
        SmokeInput input = fixtureName.endsWith(".ivf")
                ? parseIvf(fixtureBytes)
                : fromFragment(parseFragment(fixtureBytes), configObus);
        SmokeInput seekInput = null;
        if (args.length == 4) {
            if (fixtureName.endsWith(".ivf")) {
                throw new IllegalArgumentException("seek-fragment validation requires an fMP4 initialization fixture");
            }
            seekInput = fromFragment(parseFragment(readFixture(Path.of(args[3]).toAbsolutePath())), configObus);
        }

        long[] baselineStats = requireNativeMemoryStats("before decoder open");
        long handle = decoderOpenForCodecWithHwaccel(13, input.width(), input.height(), args[2]);
        if (handle == 0L) {
            throw new IllegalStateException("AV1 decoder open returned null handle for " + args[2]);
        }
        String actual = "unknown";
        DecodeResult initialResult = null;
        DecodeResult seekResult = null;
        long[] activeStats = null;
        Throwable decodeFailure = null;
        try {
            actual = getHwaccelName(handle);
            String normalizedActual = actual == null
                    ? "" : actual.toLowerCase(java.util.Locale.ROOT);
            boolean requireHardware = !"auto".equalsIgnoreCase(args[2]);
            if (actual == null || actual.isBlank()
                    || normalizedActual.startsWith("cpu")
                    || requireHardware && !normalizedActual.equals(args[2].toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalStateException("AV1 hardware decoder was not selected: actual=" + actual);
            }
            initialResult = decodeSegment(handle, input, "initial");
            flush(handle);
            int afterReset = receiveFrameNoCopy(handle);
            if (afterReset != 0) {
                throw new IllegalStateException("seek flush unexpectedly exposed a frame: status=" + afterReset);
            }
            if (seekInput != null) {
                seekResult = decodeSegment(handle, seekInput, "seek");
                if (seekResult.firstPts() <= initialResult.lastPts()) {
                    throw new IllegalStateException(
                            "seek output did not advance the presentation timeline: initialLastPts="
                                    + initialResult.lastPts() + ", seekFirstPts=" + seekResult.firstPts());
                }
                flush(handle);
                int afterSeekReset = receiveFrameNoCopy(handle);
                if (afterSeekReset != 0) {
                    throw new IllegalStateException(
                            "post-seek flush unexpectedly exposed a frame: status=" + afterSeekReset);
                }
            }
            activeStats = requireNativeMemoryStats("while decoder is active");
        } catch (Throwable error) {
            decodeFailure = error;
        } finally {
            try {
                close(handle);
            } catch (Throwable closeFailure) {
                if (decodeFailure == null) {
                    decodeFailure = closeFailure;
                } else {
                    decodeFailure.addSuppressed(closeFailure);
                }
            }
        }
        long[] afterCloseStats = null;
        try {
            afterCloseStats = requireNativeMemoryStats("after decoder close");
            assertCurrentResourcesReturnedToBaseline(baselineStats, afterCloseStats);
        } catch (Throwable cleanupFailure) {
            if (decodeFailure == null) {
                decodeFailure = cleanupFailure;
            } else {
                decodeFailure.addSuppressed(cleanupFailure);
            }
        }
        if (decodeFailure != null) {
            System.err.printf(
                    "real AV1 JNI cleanup: baselineResources=%s afterCloseResources=%s%n",
                    currentResources(baselineStats),
                    afterCloseStats != null ? currentResources(afterCloseStats) : "unavailable");
            rethrow(decodeFailure);
        }

        System.out.printf(
                "real AV1 JNI smoke: requested=%s actual=%s width=%d height=%d fps=%d outputFormat=NV12 packets=%d framesBeforeEofDrain=%d framesDrainedAtEof=%d framesAfterEofDrain=%d firstPts=%d lastPts=%d initialDecodeNanos=%d seekPackets=%d seekFramesBeforeEofDrain=%d seekFramesDrainedAtEof=%d seekFramesAfterEofDrain=%d seekFirstPts=%d seekLastPts=%d seekDecodeNanos=%d baselineResources=%s activeResources=%s afterCloseResources=%s%n",
                args[2], actual, input.width(), input.height(), FIXTURE_FPS,
                input.packets().size(), initialResult.framesBeforeEofDrain(),
                initialResult.framesDrainedAtEof(), initialResult.framesAfterEofDrain(),
                initialResult.firstPts(), initialResult.lastPts(), initialResult.decodeNanos(),
                seekInput != null ? seekInput.packets().size() : 0,
                seekResult != null ? seekResult.framesBeforeEofDrain() : 0,
                seekResult != null ? seekResult.framesDrainedAtEof() : 0,
                seekResult != null ? seekResult.framesAfterEofDrain() : 0,
                seekResult != null ? seekResult.firstPts() : -1L,
                seekResult != null ? seekResult.lastPts() : -1L,
                seekResult != null ? seekResult.decodeNanos() : 0L,
                currentResources(baselineStats), currentResources(activeStats), currentResources(afterCloseStats));
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(failure);
    }

    private static DecodeResult decodeSegment(long handle, SmokeInput input, String label) {
        long startedNanos = System.nanoTime();
        int frames = 0;
        List<Long> framePts = new ArrayList<>();
        ByteBuffer firstNv12 = ByteBuffer.allocateDirect(
                Math.multiplyExact(Math.multiplyExact(input.width(), input.height()), 3) / 2);
        boolean outputValidated = false;
        for (int i = 0; i < input.packets().size(); i++) {
            byte[] packet = input.packets().get(i);
            long ptsNanos = input.ptsNanos().get(i);
            if (sendPacketWithPts(handle, packet, 0, packet.length, ptsNanos) != 0) {
                throw new IllegalStateException(label + " sendPacket failed at sample " + i);
            }
            ReceiveBatch batch = receiveAvailable(
                    handle, input, label + " sample " + i, firstNv12, outputValidated, framePts);
            frames += batch.frames();
            outputValidated = batch.outputValidated();
        }
        int framesBeforeEofDrain = frames;
        int eofStatus;
        try {
            eofStatus = sendEndOfStream(handle);
        } catch (UnsatisfiedLinkError oldBundle) {
            throw new IllegalStateException("native bundle has no sendEndOfStream JNI symbol", oldBundle);
        }
        if (eofStatus != 0) {
            throw new IllegalStateException(label + " sendEndOfStream failed: status=" + eofStatus);
        }
        ReceiveBatch eofBatch = receiveAvailable(
                handle, input, label + " EOF", firstNv12, outputValidated, framePts);
        frames += eofBatch.frames();
        outputValidated = eofBatch.outputValidated();
        if (frames != input.expectedFrames()) {
            throw new IllegalStateException(
                    label + " EOF drain is incomplete: packets=" + input.packets().size()
                            + ", expectedFrames=" + input.expectedFrames()
                            + ", framesAfterEofDrain=" + frames);
        }
        if (!outputValidated) {
            throw new IllegalStateException(label + " produced no verifiable NV12 output frame");
        }
        assertStrictlyIncreasing(framePts);
        return new DecodeResult(framesBeforeEofDrain, frames - framesBeforeEofDrain, frames,
                framePts.isEmpty() ? -1L : framePts.get(0),
                framePts.isEmpty() ? -1L : framePts.get(framePts.size() - 1),
                Math.max(1L, System.nanoTime() - startedNanos));
    }

    private static ReceiveBatch receiveAvailable(long handle, SmokeInput input, String phase,
            ByteBuffer firstNv12, boolean outputValidated, List<Long> framePts) {
        int frames = 0;
        while (true) {
            int received;
            if (!outputValidated) {
                firstNv12.clear();
                received = getVideoFrameNv12IntoDirect(handle, firstNv12);
            } else {
                received = receiveFrameNoCopy(handle);
            }
            if (received == 0) {
                return new ReceiveBatch(frames, outputValidated);
            }
            if (received < 0) {
                throw new IllegalStateException(phase + " native frame receive failed");
            }
            if (!outputValidated) {
                long dimensions = getDimensions(handle);
                int width = (int) (dimensions >>> 32);
                int height = (int) dimensions;
                if (width != input.width() || height != input.height()) {
                    throw new IllegalStateException(
                            phase + " decoded dimensions mismatch: expected=" + input.width() + "x"
                                    + input.height() + ", actual=" + width + "x" + height);
                }
                outputValidated = true;
            }
            frames++;
            framePts.add(getLastFramePtsNanos(handle));
        }
    }

    private static SmokeInput fromFragment(Fragment fragment, byte[] configObus) {
        List<byte[]> packets = new ArrayList<>(fragment.sampleSizes().length);
        int offset = 0;
        for (int i = 0; i < fragment.sampleSizes().length; i++) {
            int size = fragment.sampleSizes()[i];
            byte[] sample = Arrays.copyOfRange(fragment.mdat(), offset, offset + size);
            offset += size;
            packets.add(i == 0 ? concat(configObus, sample) : sample);
        }
        return new SmokeInput(FIXTURE_WIDTH, FIXTURE_HEIGHT, packets.size(),
                List.copyOf(packets), fragment.ptsNanos());
    }

    private static SmokeInput parseIvf(byte[] bytes) throws IOException {
        if (bytes.length < 32 || bytes[0] != 'D' || bytes[1] != 'K' || bytes[2] != 'I' || bytes[3] != 'F') {
            throw new IOException("invalid IVF signature/header");
        }
        int headerSize = readLe16(bytes, 6);
        if (headerSize < 32 || headerSize > bytes.length) {
            throw new IOException("invalid IVF header size " + headerSize);
        }
        int width = readLe16(bytes, 12);
        int height = readLe16(bytes, 14);
        long rate = Integer.toUnsignedLong(readLe32(bytes, 16));
        long scale = Integer.toUnsignedLong(readLe32(bytes, 20));
        int declaredFrames = readLe32(bytes, 24);
        if (width <= 0 || height <= 0 || rate == 0L || scale == 0L) {
            throw new IOException("invalid IVF dimensions/timebase");
        }
        List<byte[]> packets = new ArrayList<>();
        List<Long> pts = new ArrayList<>();
        int cursor = headerSize;
        while (cursor < bytes.length) {
            if (cursor + 12 > bytes.length) {
                throw new IOException("truncated IVF frame header");
            }
            long sizeLong = Integer.toUnsignedLong(readLe32(bytes, cursor));
            long timestamp = readLe64(bytes, cursor + 4);
            cursor += 12;
            if (sizeLong > Integer.MAX_VALUE || cursor + sizeLong > bytes.length) {
                throw new IOException("truncated/oversized IVF frame payload");
            }
            int size = (int) sizeLong;
            packets.add(Arrays.copyOfRange(bytes, cursor, cursor + size));
            pts.add(Math.round(timestamp * (double) scale * 1_000_000_000.0D / rate));
            cursor += size;
        }
        int expectedFrames = declaredFrames > 0 ? declaredFrames : packets.size();
        return new SmokeInput(width, height, expectedFrames, List.copyOf(packets), List.copyOf(pts));
    }

    private static int readLe16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | (bytes[offset + 1] & 0xFF) << 8;
    }

    private static int readLe32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | (bytes[offset + 1] & 0xFF) << 8
                | (bytes[offset + 2] & 0xFF) << 16
                | (bytes[offset + 3] & 0xFF) << 24;
    }

    private static long readLe64(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(readLe32(bytes, offset))
                | Integer.toUnsignedLong(readLe32(bytes, offset + 4)) << 32;
    }

    private static void loadLibraries(Path directory) {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> libraries;
        if (os.contains("mac")) {
            libraries = List.of("libavutil.61.dylib", "libswscale.10.dylib", "libavcodec.63.dylib",
                    "libvideo_jni.dylib");
        } else if (os.contains("win")) {
            libraries = List.of("libwinpthread-1.dll", "avutil-61.dll", "swscale-10.dll",
                    "avcodec-63.dll", "video_jni.dll");
        } else {
            libraries = List.of("libavutil.so.61", "libswscale.so.10", "libavcodec.so.63", "libvideo_jni.so");
        }
        for (String library : libraries) {
            System.load(directory.resolve(library).toString());
        }
    }

    private static byte[] readFixture(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".b64")
                ? Base64.getMimeDecoder().decode(bytes)
                : bytes;
    }

    private static byte[] parseAv1Config(byte[] bytes) throws IOException {
        byte[] av1c = boxPayload(bytes, "av1C");
        if (av1c == null || av1c.length < 5 || (av1c[0] & 0xFF) != 0x81) {
            throw new IOException("real fixture has no valid av1C");
        }
        return Arrays.copyOfRange(av1c, 4, av1c.length);
    }

    private static Fragment parseFragment(byte[] bytes) throws IOException {
        byte[] moof = boxPayload(bytes, "moof");
        byte[] mdat = boxPayload(bytes, "mdat");
        byte[] trun = moof != null ? boxPayload(moof, "trun") : null;
        byte[] tfhd = moof != null ? boxPayload(moof, "tfhd") : null;
        byte[] tfdt = moof != null ? boxPayload(moof, "tfdt") : null;
        if (trun == null || tfdt == null || mdat == null || trun.length < 12) {
            throw new IOException("real fixture has no complete moof/trun/mdat");
        }
        int version = trun[0] & 0xFF;
        int flags = ((trun[1] & 0xFF) << 16) | ((trun[2] & 0xFF) << 8) | (trun[3] & 0xFF);
        int count = readInt(trun, 4);
        if (count <= 0) {
            throw new IOException("real fixture trun has no samples");
        }
        TrackDefaults defaults = parseTrackDefaults(tfhd);
        long decodeTime = parseBaseMediaDecodeTime(tfdt);
        long fallbackDuration = Math.max(1L, Math.round(FIXTURE_TIMESCALE / (double) FIXTURE_FPS));
        int cursor = 8;
        if ((flags & 0x000001) != 0) {
            cursor = checkedAdvance(trun, cursor, 4, "trun data offset");
        }
        if ((flags & 0x000004) != 0) {
            cursor = checkedAdvance(trun, cursor, 4, "trun first-sample flags");
        }
        int[] sizes = new int[count];
        List<Long> pts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long duration = defaults.sampleDuration() > 0L ? defaults.sampleDuration() : fallbackDuration;
            if ((flags & 0x000100) != 0) {
                requireBytes(trun, cursor, 4, "trun sample duration");
                duration = Integer.toUnsignedLong(readInt(trun, cursor));
                cursor += 4;
            }
            if ((flags & 0x000200) != 0) {
                requireBytes(trun, cursor, 4, "trun sample size");
                sizes[i] = readInt(trun, cursor);
                cursor += 4;
            } else if (defaults.sampleSize() > 0) {
                sizes[i] = defaults.sampleSize();
            } else {
                throw new IOException("real fixture trun has no sample size");
            }
            if ((flags & 0x000400) != 0) {
                cursor = checkedAdvance(trun, cursor, 4, "trun sample flags");
            }
            long compositionOffset = 0L;
            if ((flags & 0x000800) != 0) {
                requireBytes(trun, cursor, 4, "trun composition offset");
                int rawOffset = readInt(trun, cursor);
                compositionOffset = version == 0 ? Integer.toUnsignedLong(rawOffset) : rawOffset;
                cursor += 4;
            }
            long presentationTicks = Math.max(0L, decodeTime + compositionOffset);
            pts.add(Math.round(presentationTicks * 1_000_000_000.0D / FIXTURE_TIMESCALE));
            decodeTime += Math.max(1L, duration);
        }
        long payloadBytes = Arrays.stream(sizes).mapToLong(Integer::toUnsignedLong).sum();
        if (payloadBytes != mdat.length) {
            throw new IOException("real fixture trun/mdat byte count mismatch");
        }
        return new Fragment(sizes, List.copyOf(pts), mdat);
    }

    private static TrackDefaults parseTrackDefaults(byte[] tfhd) throws IOException {
        if (tfhd == null || tfhd.length < 8) {
            return new TrackDefaults(0L, 0);
        }
        int flags = ((tfhd[1] & 0xFF) << 16) | ((tfhd[2] & 0xFF) << 8) | (tfhd[3] & 0xFF);
        int cursor = 8;
        if ((flags & 0x000001) != 0) {
            cursor = checkedAdvance(tfhd, cursor, 8, "tfhd base data offset");
        }
        if ((flags & 0x000002) != 0) {
            cursor = checkedAdvance(tfhd, cursor, 4, "tfhd sample description index");
        }
        long duration = 0L;
        if ((flags & 0x000008) != 0) {
            requireBytes(tfhd, cursor, 4, "tfhd default sample duration");
            duration = Integer.toUnsignedLong(readInt(tfhd, cursor));
            cursor += 4;
        }
        int size = 0;
        if ((flags & 0x000010) != 0) {
            requireBytes(tfhd, cursor, 4, "tfhd default sample size");
            size = readInt(tfhd, cursor);
            cursor += 4;
        }
        if ((flags & 0x000020) != 0) {
            checkedAdvance(tfhd, cursor, 4, "tfhd default sample flags");
        }
        return new TrackDefaults(duration, size);
    }

    private static long parseBaseMediaDecodeTime(byte[] tfdt) throws IOException {
        if (tfdt.length < 8) {
            throw new IOException("real fixture has truncated tfdt");
        }
        int version = tfdt[0] & 0xFF;
        if (version == 1) {
            requireBytes(tfdt, 4, 8, "tfdt version 1 decode time");
            return Math.max(0L, readLong(tfdt, 4));
        }
        return Integer.toUnsignedLong(readInt(tfdt, 4));
    }

    private static int checkedAdvance(byte[] bytes, int cursor, int length, String field) throws IOException {
        requireBytes(bytes, cursor, length, field);
        return cursor + length;
    }

    private static void requireBytes(byte[] bytes, int cursor, int length, String field) throws IOException {
        if (cursor < 0 || length < 0 || cursor > bytes.length - length) {
            throw new IOException("real fixture has truncated " + field);
        }
    }

    private static byte[] boxPayload(byte[] bytes, String target) {
        for (int offset = 0; offset + 8 <= bytes.length; offset++) {
            if (bytes[offset + 4] != target.charAt(0) || bytes[offset + 5] != target.charAt(1)
                    || bytes[offset + 6] != target.charAt(2) || bytes[offset + 7] != target.charAt(3)) {
                continue;
            }
            int size = readInt(bytes, offset);
            if (size >= 8 && offset + size <= bytes.length) {
                return Arrays.copyOfRange(bytes, offset + 8, offset + size);
            }
        }
        return null;
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 24 | (bytes[offset + 1] & 0xFF) << 16
                | (bytes[offset + 2] & 0xFF) << 8 | bytes[offset + 3] & 0xFF;
    }

    private static long readLong(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(readInt(bytes, offset)) << 32
                | Integer.toUnsignedLong(readInt(bytes, offset + 4));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static void assertStrictlyIncreasing(List<Long> pts) {
        for (int i = 1; i < pts.size(); i++) {
            if (pts.get(i) <= pts.get(i - 1)) {
                throw new IllegalStateException(
                        "non-increasing output PTS at " + i + ": " + pts.get(i - 1) + " -> " + pts.get(i));
            }
        }
    }

    private static long[] requireNativeMemoryStats(String phase) {
        long[] stats;
        try {
            stats = getNativeMemoryStats();
        } catch (UnsatisfiedLinkError missingSymbol) {
            throw new IllegalStateException("native bundle has no memory-statistics JNI symbol at " + phase,
                    missingSymbol);
        }
        if (stats == null || stats.length < 11) {
            throw new IllegalStateException("native memory statistics are unavailable at " + phase);
        }
        return stats;
    }

    private static void assertCurrentResourcesReturnedToBaseline(long[] baseline, long[] afterClose) {
        int[] currentIndexes = { 0, 5, 7, 9 };
        String[] labels = { "ffmpegBytes", "d3d11Textures", "d3d11Surfaces", "d3d11LogicalBytes" };
        for (int i = 0; i < currentIndexes.length; i++) {
            int index = currentIndexes[i];
            if (afterClose[index] != baseline[index]) {
                throw new IllegalStateException(
                        "native resource did not return to baseline: " + labels[i]
                                + " baseline=" + baseline[index] + " afterClose=" + afterClose[index]);
            }
        }
    }

    private static String currentResources(long[] stats) {
        return stats[0] + "/" + stats[5] + "/" + stats[7] + "/" + stats[9];
    }

    private record TrackDefaults(long sampleDuration, int sampleSize) {
    }

    private record Fragment(int[] sampleSizes, List<Long> ptsNanos, byte[] mdat) {
    }

    private record SmokeInput(int width, int height, int expectedFrames,
            List<byte[]> packets, List<Long> ptsNanos) {
    }

    private record ReceiveBatch(int frames, boolean outputValidated) {
    }

    private record DecodeResult(int framesBeforeEofDrain, int framesDrainedAtEof,
            int framesAfterEofDrain, long firstPts, long lastPts, long decodeNanos) {
    }
}
