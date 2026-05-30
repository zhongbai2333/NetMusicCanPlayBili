package com.zhongbai233.net_music_can_play_bili.bili.pipeline;

import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioHandler;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
import net.minecraft.core.BlockPos;

import javax.sound.sampled.AudioFormat;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DolbyEc3Pipeline implements AudioDecodePipeline {
    private static final int STREAM_BUFFER_SIZE = 256 * 1024;
    private static final int EC3_MAX_FRAME_SIZE = 4096;
    private static final int EC3_SCAN_TAIL_SIZE = 8;

    private final AudioFormat format = new AudioFormat(48000, 16, 2, true, false);
    private final AtomicBoolean ownerClosed;
    private final AtomicBoolean cleaned = new AtomicBoolean(false);
    private final DolbyAudioHandler dolby;
    private final String container;

    private long mdatBoxes;
    private long mdatBytes;
    private long ec3Frames;

    public DolbyEc3Pipeline(String container, AtomicBoolean ownerClosed) {
        this(container, ownerClosed, null);
    }

    public DolbyEc3Pipeline(String container, AtomicBoolean ownerClosed, BlockPos sourcePos) {
        this.container = container;
        this.ownerClosed = ownerClosed;
        this.dolby = new DolbyAudioHandler();
        DolbyAudioRegistry.register(dolby, sourcePos);
    }

    @Override
    public AudioFormat format() {
        return format;
    }

    @Override
    public String container() {
        return container;
    }

    @Override
    public String codec() {
        return "ec-3";
    }

    @Override
    public String detail() {
        return "Dolby Atmos";
    }

    @Override
    public boolean usesOpenAlOutput() {
        return true;
    }

    @Override
    public long onMdat(InputStream input, long length) throws IOException {
        Ec3ScanStats stats = scanEc3FramesFromStream(input, length);
        if (stats.framesFound() > 0 || length < 0) {
            mdatBoxes++;
            mdatBytes += stats.bytesRead();
            ec3Frames += stats.framesFound();
        }
        return stats.framesFound();
    }

    @Override
    public long decodedFrames() {
        return ec3Frames;
    }

    @Override
    public String statsSummary() {
        return "DolbyMdat=" + mdatBoxes
                + ", DolbyBytes=" + mdatBytes
                + ", DolbyFrames=" + ec3Frames
                + ", DolbyQueue=" + dolby.queuedFrames();
    }

    @Override
    public void close() {
        if (cleaned.compareAndSet(false, true)) {
            DolbyAudioRegistry.unregister(dolby);
            dolby.cleanup();
        }
    }

    private Ec3ScanStats scanEc3FramesFromStream(InputStream in, long length) throws IOException {
        byte[] readBuffer = new byte[STREAM_BUFFER_SIZE];
        byte[] scanBuffer = new byte[STREAM_BUFFER_SIZE + EC3_MAX_FRAME_SIZE + EC3_SCAN_TAIL_SIZE];
        int carry = 0;
        long remaining = length;
        long bytesRead = 0L;
        long framesFound = 0L;

        while (!ownerClosed.get() && (length < 0 || remaining > 0)) {
            int toRead = length < 0 ? readBuffer.length : (int) Math.min(readBuffer.length, remaining);
            int n = in.read(readBuffer, 0, toRead);
            if (n < 0) {
                if (length >= 0 && remaining > 0) {
                    throw new EOFException("EOF while reading EC-3 payload");
                }
                break;
            }

            bytesRead += n;
            if (length >= 0) {
                remaining -= n;
            }
            System.arraycopy(readBuffer, 0, scanBuffer, carry, n);

            int scanLen = carry + n;
            Ec3ChunkScanResult result = scanEc3FramesInto(scanBuffer, scanLen);
            framesFound += result.framesFound();

            carry = Math.min(result.carryLength(), EC3_MAX_FRAME_SIZE + EC3_SCAN_TAIL_SIZE);
            if (carry > 0) {
                System.arraycopy(scanBuffer, scanLen - carry, scanBuffer, 0, carry);
            }
        }

        return new Ec3ScanStats(bytesRead, framesFound);
    }

    private Ec3ChunkScanResult scanEc3FramesInto(byte[] data, int len) {
        int pos = 0;
        int carryStart = Math.max(0, len - EC3_SCAN_TAIL_SIZE);
        long framesFound = 0L;

        while (pos < len - 1) {
            if ((data[pos] & 0xFF) == 0x0B && (data[pos + 1] & 0xFF) == 0x77) {
                if (pos + 4 > len) {
                    carryStart = pos;
                    break;
                }

                int fszRaw = ((data[pos + 2] & 0x07) << 8) | (data[pos + 3] & 0xFF);
                int fsz = (fszRaw + 1) * 2;
                if (fsz >= 16 && fsz <= EC3_MAX_FRAME_SIZE) {
                    if (pos + fsz > len) {
                        carryStart = pos;
                        break;
                    }

                    byte[] ec3Frame = new byte[fsz];
                    System.arraycopy(data, pos, ec3Frame, 0, fsz);
                    if (dolby.enqueueFrame(ec3Frame)) {
                        framesFound++;
                    }
                    pos += fsz;
                    carryStart = Math.max(pos, len - EC3_SCAN_TAIL_SIZE);
                    continue;
                }
            }
            pos++;
        }

        int carryLength = Math.max(0, len - Math.min(carryStart, len));
        return new Ec3ChunkScanResult(framesFound, carryLength);
    }

    private record Ec3ScanStats(long bytesRead, long framesFound) {
    }

    private record Ec3ChunkScanResult(long framesFound, int carryLength) {
    }
}
