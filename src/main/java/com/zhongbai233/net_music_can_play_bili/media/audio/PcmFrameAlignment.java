package com.zhongbai233.net_music_can_play_bili.media.audio;

/** PCM 字节位置与读取长度的完整 frame 对齐工具。 */
public final class PcmFrameAlignment {
    private PcmFrameAlignment() {
    }

    public static long alignDown(long bytes, int frameSize) {
        long safeBytes = Math.max(0L, bytes);
        int safeFrameSize = Math.max(1, frameSize);
        return safeBytes - safeBytes % safeFrameSize;
    }

    public static int alignedRequest(long remaining, int bufferLength, int frameSize) {
        if (remaining <= 0L || bufferLength <= 0) {
            return 0;
        }
        int safeFrameSize = Math.max(1, frameSize);
        long bounded = Math.min(remaining, bufferLength);
        int aligned = (int) alignDown(bounded, safeFrameSize);
        if (aligned > 0) {
            return aligned;
        }
        // 异步解码流可能在一次对齐请求中返回非对齐短读。此时必须把目标位置前
        // 剩余的几个字节读完，保证累计消费量最终回到完整 PCM frame 边界。
        return (int) bounded;
    }
}