package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.AudioStartupSync;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.IOException;

/**
 * 压缩音频从文件头建立解码状态后，在 PCM 域完成起播追赶的统一策略。
 *
 * <p>
 * 所有读取都保持完整 PCM frame 对齐，并把建立解码器期间经过的时间补偿到最终时间线。
 * </p>
 */
public final class PcmStartupSeekPolicy {
    private static final int MAX_COMPENSATION_PASSES = 4;
    private static final int SKIP_BUFFER_BYTES = 64 * 1024;

    private PcmStartupSeekPolicy() {
    }

    public static Result seekToCurrentPlayback(AudioInputStream stream, AudioFormat format,
            PlaybackRequest request, float initialSkipSeconds) throws IOException {
        long bytesPerSecond = Math.max(1L, Math.round(format.getSampleRate()) * (long) format.getFrameSize());
        int frameSize = Math.max(1, format.getFrameSize());
        long initialSkipBytes = PcmFrameAlignment.alignDown(bytesForSeconds(format, initialSkipSeconds), frameSize);
        long skippedBytes = 0L;
        int passes = 0;
        byte[] buffer = new byte[SKIP_BUFFER_BYTES];
        while (passes < MAX_COMPENSATION_PASSES) {
            long setupMillis = AudioStartupSync.elapsedSinceCaptureMillis(request.capturedNanos(), System.nanoTime());
            long targetBytes = PcmFrameAlignment.alignDown(
                    saturatedAdd(initialSkipBytes, millisToBytes(bytesPerSecond, setupMillis)), frameSize);
            long remaining = Math.max(0L, targetBytes - skippedBytes);
            if (remaining <= bytesPerSecond / 20L) {
                break;
            }
            long skippedThisPass = skip(stream, remaining, buffer, frameSize);
            skippedBytes = saturatedAdd(skippedBytes, skippedThisPass);
            passes++;
            if (skippedThisPass < remaining) {
                break;
            }
        }
        long compensatedBytes = Math.max(0L, skippedBytes - initialSkipBytes);
        long compensatedMillis = Math.round(compensatedBytes * 1000.0D / bytesPerSecond);
        long timelineOffsetMillis = AudioStartupSync.compensatedOffsetMillis(request.elapsedMillis(),
                request.totalMillis(), compensatedMillis);
        return new Result(timelineOffsetMillis, passes, skippedBytes, frameSize);
    }

    public static long skipFixedOffset(AudioInputStream stream, AudioFormat format, float seconds) throws IOException {
        return skip(stream, bytesForSeconds(format, seconds), new byte[SKIP_BUFFER_BYTES],
                Math.max(1, format.getFrameSize()));
    }

    private static long bytesForSeconds(AudioFormat format, float seconds) {
        if (seconds <= 0f) {
            return 0L;
        }
        return Math.round(format.getSampleRate() * seconds) * (long) format.getFrameSize();
    }

    private static long skip(AudioInputStream stream, long bytesToSkip, byte[] buffer, int frameSize)
            throws IOException {
        long alignedTarget = PcmFrameAlignment.alignDown(Math.max(0L, bytesToSkip), frameSize);
        long remaining = alignedTarget;
        while (remaining > 0L) {
            int request = PcmFrameAlignment.alignedRequest(remaining, buffer.length, frameSize);
            if (request <= 0) {
                break;
            }
            int read = stream.read(buffer, 0, request);
            if (read < 0) {
                break;
            }
            if (read > 0) {
                remaining -= read;
            }
        }
        return alignedTarget - remaining;
    }

    private static long millisToBytes(long bytesPerSecond, long millis) {
        if (bytesPerSecond <= 0L || millis <= 0L) {
            return 0L;
        }
        double bytes = bytesPerSecond * (millis / 1000.0D);
        return bytes >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(bytes);
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    public record Result(long timelineOffsetMillis, int passes, long skippedBytes, int frameSize) {
        public float timelineOffsetSeconds() {
            return timelineOffsetMillis / 1000.0f;
        }

        public boolean isFrameAligned() {
            return frameSize > 0 && skippedBytes % frameSize == 0L;
        }
    }
}