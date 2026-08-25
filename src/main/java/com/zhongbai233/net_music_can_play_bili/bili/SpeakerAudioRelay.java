package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackApproachPredictor;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackPresentationEnvelope;
import com.zhongbai233.net_music_can_play_bili.client.PlaybackLatencyBench;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private volatile boolean takeOverMainOutput = true;
    private volatile float userVolume = 1.0f;
    private volatile float rangeGain = 1.0f;
    private volatile float areaGain = 1.0f;
    private volatile float maxDistance = AudioUtils.MAX_AUDIBLE_DISTANCE;
    private volatile boolean preparationDemand;
    private volatile float[] speakerPos;
    private volatile boolean handlerStarted;
    private final PlaybackPresentationEnvelope presentationEnvelope = new PlaybackPresentationEnvelope();
    private volatile boolean paused;
    private int pendingFed = 0;
    private long totalSamplesFed = 0L;
    private long timelineBaselineSamples;
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

    /** 是否会实际领取至少一个输入声道并尝试输出。 */
    public boolean hasOutputIntent() {
        return channelIndex >= 0 && channelIndex <= 11 && userVolume > 0.0F;
    }

    public void setTakeOverMainOutput(boolean takeOverMainOutput) {
        this.takeOverMainOutput = takeOverMainOutput;
    }

    public boolean takesOverMainOutput() {
        return takeOverMainOutput;
    }

    public void setUserVolume(float v) {
        this.userVolume = AudioPlaybackRange.clampVolume(v);
    }

    public void setRangeGain(float gain) {
        this.rangeGain = AudioUtils.clampGain(gain);
    }

    public void setAreaGain(float gain) {
        this.areaGain = AudioUtils.clampGain(gain);
    }
    public void setPreparationDemand(boolean preparationDemand) {
        this.preparationDemand = preparationDemand;
    }


    public void setMaxDistance(float distance) {
        this.maxDistance = AudioPlaybackRange.normalizeConfiguredDistance(distance);
    }

    public float getMaxDistance() {
        return maxDistance;
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
            next.setPaused(paused);
            pendingFed = 0;
            totalSamplesFed = timelineBaselineSamples;
            started = false;
            initialized = true;
            if (timelineBaselineSamples > 0L) {
                next.flushQueuedAudio(timelineBaselineSamples);
            }
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
        tick(listenerPos, false);
    }

    public void tick(float[] listenerPos, boolean muted) {
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
        float geometricGain = muted ? 0.0F : rangeAt(listenerPos).gain() * rangeGain;
        float entryGain = presentationEnvelope.gain(geometricGain > 0.0F, System.nanoTime());
        float g = geometricGain * areaGain * entryGain * gameVol();
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

    public void setPaused(boolean paused) {
        this.paused = paused;
        OpenALSpatialAudio sa = spatialAudio;
        if (sa != null) {
            sa.setPaused(paused);
        }
    }

    /** 音响是否已度过初始静音期，正在输出真实音频 */
    public boolean isStarted() {
        return started;
    }

    /** Whether this relay currently contributes a non-zero acoustic gain. */
    public boolean isAudibleAt(float[] listenerPos) {
        return rangeGain > 0.0F && areaGain > 0.001F && hasOutputIntent() && rangeAt(listenerPos).audible();
    }

    /** Preparation stays alive behind an acoustic wall while the listener remains in physical range. */
    public boolean hasGeometricDemand(float[] listenerPos) {
        return rangeGain > 0.0F && hasOutputIntent() && rangeAt(listenerPos).audible();
    }

    public boolean hasAnticipatedDemand(float[] listenerPos, float[] velocity) {
        float[] currentSpeakerPos = speakerPos;
        if (listenerPos == null || velocity == null || currentSpeakerPos == null || !hasOutputIntent()) {
            return false;
        }
        if (preparationDemand) {
            return true;
        }
        if (rangeGain <= 0.0F) {
            return false;
        }
        AudioPlaybackRange.Profile profile = AudioPlaybackRange.profile(maxDistance, userVolume, userVolume);
        return PlaybackApproachPredictor.willEnterSphere(listenerPos[0], listenerPos[1], listenerPos[2],
                velocity[0], velocity[1], velocity[2], currentSpeakerPos[0], currentSpeakerPos[1],
                currentSpeakerPos[2], profile.fadeEndDistance());
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
        long baseline = sa != null ? sa.getConsumedSamples() : totalSamplesFed;
        flushQueuedAudio(baseline);
    }

    /** Flushes stale buffers while preserving the shared handler media timeline. */
    public void flushQueuedAudio(long mediaPositionSamples) {
        long baseline = Math.max(0L, mediaPositionSamples);
        timelineBaselineSamples = Math.max(timelineBaselineSamples, baseline);
        OpenALSpatialAudio sa = spatialAudio;
        totalSamplesFed = sa != null
                ? Math.max(baseline, sa.flushQueuedAudio(baseline))
                : Math.max(totalSamplesFed, baseline);
        pendingFed = 0;
    }

    long timelineBaselineSamples() {
        return timelineBaselineSamples;
    }

    public void hardStopOutput() {
        started = false;
        handlerStarted = false;
        pendingFed = 0;
        totalSamplesFed = 0L;
        presentationEnvelope.reset();
        timelineBaselineSamples = 0L;
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

    private AudioPlaybackRange.SphereResult rangeAt(float[] listenerPos) {
        float[] currentSpeakerPos = speakerPos;
        if (listenerPos == null || currentSpeakerPos == null) {
            return AudioPlaybackRange.evaluateSphere(Float.POSITIVE_INFINITY, maxDistance,
                    userVolume, userVolume, false);
        }
        return AudioPlaybackRange.evaluateSphere(distance(listenerPos, currentSpeakerPos), maxDistance,
                userVolume, userVolume, false);
    }

    private static float gameVol() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.options == null)
            return 1.0f;
        return mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER)
                * mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.RECORDS);
    }

    private volatile boolean closed;
    private final AtomicBoolean cleanupStarted = new AtomicBoolean();

    public void cleanup() {
        if (!cleanupStarted.compareAndSet(false, true)) {
            return;
        }
        closed = true;
        hardStopOutput();
        started = false;
        handlerStarted = false;
        pendingFed = 0;
        totalSamplesFed = 0L;
        initialized = false;
    }
}
