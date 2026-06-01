package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.bili.codec.OpenALSpatialAudio;

/**
 * 音响独立音频输出 — 单声道，只接收已拆分好的目标声道 PCM
 * 与主 handler 同时初始化（首帧 PCM 到达时），共享相同的 256ms init 静音期，
 * 确保主输出与 relay 时间线对齐，切换时无回跳
 */
public class SpeakerAudioRelay {
    private static final int SAMPLES_PER_BLOCK = 256;
    private static final float[] MONO_POS = { 0, 0, 1.0f };
    private static final int MIN_PUMP_PENDING = 40;

    private volatile OpenALSpatialAudio spatialAudio;
    private volatile boolean initialized;
    private volatile boolean started;
    private volatile int channelIndex = -1;
    private volatile float userVolume = 1.0f;
    private volatile float[] speakerPos;
    private long initNanos;
    private int pendingFed = 0;
    private int sampleRate = 48000;

    public void setChannelIndex(int idx) {
        this.channelIndex = idx;
    }

    public int getChannelIndex() {
        return channelIndex;
    }

    public void setUserVolume(float v) {
        this.userVolume = AudioUtils.clampGain(v);
    }

    public void setSpeakerPos(float[] pos) {
        this.speakerPos = AudioUtils.copyPos3(pos);
    }

    public void setSampleRate(int sr) {
        if (!initialized && sr > 0)
            this.sampleRate = sr;
    }

    public void feedChannel(float[] monoPcm) {
        if (closed || monoPcm == null || monoPcm.length == 0)
            return;

        if (!initialized) {
            OpenALSpatialAudio old = spatialAudio;
            if (old != null)
                old.cleanup();
            spatialAudio = new OpenALSpatialAudio();
            spatialAudio.init(1, 0, sampleRate); // 必须用正确采样率，否则播速偏差
            pendingFed = 0;
            initNanos = System.nanoTime();
            initialized = true;
        }

        OpenALSpatialAudio sa = spatialAudio;
        if (sa == null)
            return;
        int numBlocks = monoPcm.length / SAMPLES_PER_BLOCK;
        if (numBlocks == 0)
            numBlocks = 1;
        float[][] w = new float[1][];
        for (int blk = 0; blk < numBlocks; blk++) {
            w[0] = monoPcm;
            sa.updateBedFrameBlock(w, blk * SAMPLES_PER_BLOCK);
        }
        pendingFed += numBlocks;
    }

    public void tick(float[] listenerPos) {
        if (closed || speakerPos == null)
            return;
        if (!initialized)
            return;
        OpenALSpatialAudio sa = spatialAudio;
        if (sa == null)
            return;
        // 检查是否已度过 init 静音期
        if (!started && initNanos > 0L) {
            long elapsedMs = (System.nanoTime() - initNanos) / 1_000_000L;
            if (elapsedMs >= 280) {
                started = true;
            }
        }
        sa.updatePositions(new float[][] { MONO_POS }, new float[0][0], listenerPos,
                forward(speakerPos, listenerPos));
        float g = gainForDistance(distance(listenerPos, speakerPos)) * userVolume * gameVol();
        sa.setBedGain(0, g);
        if (sa.isDeviceLost()) {
            sa.cleanup();
            spatialAudio = null;
            initialized = false;
            started = false;
            pendingFed = 0;
        } else if (pendingFed >= MIN_PUMP_PENDING)
            sa.pumpQueuedAudio();
    }

    /** 音响是否已度过初始静音期，正在输出真实音频 */
    public boolean isStarted() {
        return started;
    }

    private static float[] forward(float[] sp, float[] lp) {
        float dx = sp[0] - lp[0], dz = sp[2] - lp[2];
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        return len < 0.001f ? new float[] { 0, 0, 1 } : new float[] { dx / len, 0, dz / len };
    }

    private static float distance(float[] a, float[] b) {
        float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 使用与主 handler 一致的距离衰减曲线 */
    private static float gainForDistance(float d) {
        return AudioUtils.gainForDistance(d);
    }

    private static float gameVol() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.options == null)
            return 1.0f;
        return mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER)
                * mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.RECORDS);
    }

    private volatile boolean closed;

    public void cleanup() {
        closed = true;
        started = false;
        initNanos = 0L;
        pendingFed = 0;
        if (spatialAudio != null) {
            spatialAudio.cleanup();
            spatialAudio = null;
        }
        initialized = false;
    }
}
