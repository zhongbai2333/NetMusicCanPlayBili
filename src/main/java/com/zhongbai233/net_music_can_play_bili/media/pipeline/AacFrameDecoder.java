package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.github.tartaricacid.netmusic.soundlibs.net.sourceforge.jaad.SampleBuffer;
import com.github.tartaricacid.netmusic.soundlibs.net.sourceforge.jaad.aac.Decoder;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4StreamParser;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

final class AacFrameDecoder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MIN_AAC_SAMPLE_SIZE = 20;

    private final Decoder decoder;
    private final SampleBuffer sampleBuffer;
    private final AudioFormat format;
    private final BooleanSupplier shouldStop;
    private int[] pendingSampleSizes;
    private long decodedFrames;

    AacFrameDecoder(byte[] asc, BooleanSupplier shouldStop) {
        this.decoder = Decoder.create(asc);
        this.format = decoder.getAudioFormat();
        this.sampleBuffer = new SampleBuffer(format);
        this.sampleBuffer.setBigEndian(format.isBigEndian());
        this.shouldStop = shouldStop != null ? shouldStop : () -> false;
    }

    AudioFormat format() {
        return format;
    }

    void onMoof(int[] sampleSizes) {
        pendingSampleSizes = sampleSizes;
        if (sampleSizes == null || sampleSizes.length == 0) {
            LOGGER.trace("fMP4 AAC moof had no sample sizes");
        }
    }

    long onMdat(InputStream input, long length, PcmSink sink) throws IOException {
        if (pendingSampleSizes == null || pendingSampleSizes.length == 0) {
            Fmp4StreamParser.skipFully(input, length);
            return 0L;
        }

        long remaining = length;
        long decodedThisBox = 0L;
        for (int sampleSize : pendingSampleSizes) {
            if (shouldStop.getAsBoolean() || remaining <= 0) {
                break;
            }
            if (sampleSize <= 0) {
                continue;
            }
            if (sampleSize > remaining) {
                decodedThisBox += decodePartialTail(input, remaining, sink);
                remaining = 0L;
                break;
            }

            byte[] sample = Fmp4StreamParser.readFully(input, sampleSize);
            remaining -= sampleSize;
            if (sampleSize < MIN_AAC_SAMPLE_SIZE) {
                continue;
            }
            if (decodeOne(sample, sink)) {
                decodedThisBox++;
            }
        }
        if (remaining > 0) {
            Fmp4StreamParser.skipFully(input, remaining);
        }
        decodedFrames += decodedThisBox;
        pendingSampleSizes = null;
        return decodedThisBox;
    }

    long decodedFrames() {
        return decodedFrames;
    }

    private long decodePartialTail(InputStream input, long remaining, PcmSink sink) throws IOException {
        if (remaining < MIN_AAC_SAMPLE_SIZE) {
            Fmp4StreamParser.skipFully(input, remaining);
            return 0L;
        }
        byte[] partial = Fmp4StreamParser.readFully(input, remaining);
        try {
            return decodeOne(partial, sink) ? 1L : 0L;
        } catch (Exception e) {
            LOGGER.trace("Skipping truncated AAC tail frame size={}: {}", remaining, e.getMessage());
            return 0L;
        }
    }

    private boolean decodeOne(byte[] sample, PcmSink sink) throws IOException {
        try {
            decoder.decodeFrame(sample, sampleBuffer);
            byte[] pcm = sampleBuffer.getData();
            if (pcm == null || pcm.length == 0) {
                return false;
            }
            sink.accept(pcm, format);
            return true;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.trace("Skipping undecodable AAC frame(size={}): {}", sample.length, e.getMessage());
            return false;
        }
    }

    @FunctionalInterface
    interface PcmSink {
        void accept(byte[] pcm, AudioFormat format) throws IOException;
    }
}
