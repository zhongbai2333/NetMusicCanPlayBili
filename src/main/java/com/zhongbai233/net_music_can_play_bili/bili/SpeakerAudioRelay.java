package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.client.PlaybackLatencyBench;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;

/**
 * 音响独立音频输出 — 单声道，只接收已拆分好的目标声道 PCM
 * 与主 handler 同时初始化（首帧 PCM 到达时），共享相同的 256ms init 静音期，
 * 确保主输出与 relay 时间线对齐，切换时无回跳
 */
public class SpeakerAudioRelay {
    private static final int SAMPLES_PER_BLOCK = 256;
    private static final float[] MONO_POS = { 0, 0, 1.0f };
    private static final int MIN_PUMP_PENDING = 4;

    private volatile OpenALSpatialAudio spatialAudio;
    private volatile boolean initialized;
    private volatile boolean started;
    private volatile int channelIndex = -1;
    private volatile boolean autoMixJoc;
    private volatile float userVolume = 1.0f;
    private volatile float[] speakerPos;
    private volatile boolean handlerStarted;
    private int pendingFed = 0;
    private long totalSamplesFed = 0L;
    private int sampleRate = 48000;

    public void setChannelIndex(int idx) {
        this.channelIndex = idx;
    }

    public int getChannelIndex() {
        return channelIndex;
    }

    public void setAutoMixJoc(boolean v) {
        this.autoMixJoc = v;
    }

    public boolean isAutoMixJoc() {
        return autoMixJoc;
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

    public void setHandlerStarted(boolean v) {
        this.handlerStarted = v;
    }

    public void feedChannel(float[] monoPcm) {
        feedMono(monoPcm);
    }

    public void feedMono(float[] monoPcm) {
        if (closed || monoPcm == null || monoPcm.length == 0)
            return;

        if (!initialized) {
            OpenALSpatialAudio old = spatialAudio;
            if (old != null)
                old.cleanup();
            OpenALSpatialAudio next = new OpenALSpatialAudio();
            if (!next.init(1, 0, sampleRate)) { // 必须用正确采样率，否则播速偏差
                next.cleanup();
                spatialAudio = null;
                initialized = false;
                return;
            }
            spatialAudio = next;
            pendingFed = 0;
            totalSamplesFed = 0L;
            started = false;
            initialized = true;
            PlaybackLatencyBench.markAudioOpenAlInitialized(this, kind(), sampleRate);
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
        totalSamplesFed += (long) monoPcm.length;
        PlaybackLatencyBench.markAudioFed(this, kind(), numBlocks, totalSamplesFed, pendingFed, sampleRate);
    }

    public void tick(float[] listenerPos) {
        if (closed || speakerPos == null)
            return;
        if (!initialized)
            return;
        OpenALSpatialAudio sa = spatialAudio;
        if (sa == null)
            return;
        if (!started && handlerStarted) {
            started = true;
            PlaybackLatencyBench.markAudioStarted(this, kind(), pendingFed, totalSamplesFed, sampleRate);
        }
        sa.updatePositions(new float[][] { MONO_POS }, new float[0][0], listenerPos,
                forward(speakerPos, listenerPos));
        float g = gainForDistance(distance(listenerPos, speakerPos), userVolume) * gameVol();
        sa.setBedGain(0, g);
        if (sa.isDeviceLost()) {
            sa.cleanup();
            spatialAudio = null;
            initialized = false;
            started = false;
            pendingFed = 0;
        } else if (pendingFed >= MIN_PUMP_PENDING)
            sa.pumpQueuedAudio();
        PlaybackLatencyBench.markAudioConsumed(this, kind(), sa.getConsumedSamples(), sampleRate);
    }

    /** 音响是否已度过初始静音期，正在输出真实音频 */
    public boolean isStarted() {
        return started;
    }

    public long getPositionTicks() {
        long millis = getPositionMillis();
        return millis >= 0L ? millis * 20L / 1000L : -1L;
    }

    public long getPositionMillis() {
        if (!started) {
            return -1L;
        }
        OpenALSpatialAudio sa = spatialAudio;
        if (sa == null) {
            return -1L;
        }
        long consumed = sa.getConsumedSamples();
        PlaybackLatencyBench.markAudioConsumed(this, kind(), consumed, sampleRate);
        return Math.round(consumed * 1000.0D / Math.max(1, sampleRate));
    }

    public long getOutputDelayMillis() {
        if (!started) {
            return 0L;
        }
        OpenALSpatialAudio sa = spatialAudio;
        long delaySamples = sa != null ? sa.getOutputDelaySamples() : 0L;
        return delaySamples > 0L ? Math.round(delaySamples * 1000.0D / Math.max(1, sampleRate)) : 0L;
    }

    public void flushQueuedAudio() {
        OpenALSpatialAudio sa = spatialAudio;
        if (sa != null) {
            sa.flushQueuedAudio();
        }
        pendingFed = 0;
        totalSamplesFed = 0L;
    }

    public void hardStopOutput() {
        started = false;
        handlerStarted = false;
        pendingFed = 0;
        totalSamplesFed = 0L;
        OpenALSpatialAudio sa = spatialAudio;
        if (sa != null) {
            sa.hardStopOutput();
            sa.cleanup();
            spatialAudio = null;
        }
        initialized = false;
    }

    private String kind() {
        return "speaker-relay-ch" + channelIndex;
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

    /** 音响音量会同步收缩最大传播距离，避免低音量仍能被远处听到。 */
    private static float gainForDistance(float d, float volume) {
        return AudioUtils.speakerGainForDistance(d, volume);
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
        hardStopOutput();
        closed = true;
        started = false;
        handlerStarted = false;
        pendingFed = 0;
        totalSamplesFed = 0L;
        if (spatialAudio != null) {
            spatialAudio.cleanup();
            spatialAudio = null;
        }
        initialized = false;
    }
}
