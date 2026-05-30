package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.bili.codec.OpenALSpatialAudio;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 立体声音频的 OpenAL 空间播放器
 * 将解码后的 float32 PCM 送入 2 声道 OpenAL 管线，复用 Dolby 链路的空间定位能力
 */
public class StereoOpenALHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SAMPLES_PER_BLOCK = 256;
    private static final int QUEUE_CAPACITY = 2048;
    private static final int PREBUFFER_BLOCKS = 192;
    private static final int MAX_BLOCKS_PER_TICK = 64;

    private final BlockingQueue<float[][]> pcmQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread worker;
    private volatile boolean closed;
    private volatile boolean started;
    private long lastFrameFeedNanos;
    private double frameBudget;

    private OpenALSpatialAudio spatialAudio;
    private boolean initialized;
    private int frameCount;
    private int sampleRate = 48000;
    private float[][] pendingBlock = new float[2][SAMPLES_PER_BLOCK];
    private int pendingSamples;

    public StereoOpenALHandler() {
        worker = new Thread(this::workerLoop, "StereoOpenALWorker");
        worker.setDaemon(true);
        worker.start();
    }

    /** 将解码后的立体声 float32 PCM 拆成固定 256-sample block 入队，避免 FLAC 可变块长影响播放速度 */
    public boolean enqueuePcm(float[][] stereoBlock) {
        if (stereoBlock == null || closed)
            return false;
        int samples = stereoBlock.length > 0 ? stereoBlock[0].length : 0;
        if (samples <= 0)
            return false;
        boolean queuedAny = false;
        for (int sample = 0; sample < samples && !closed; sample++) {
            for (int ch = 0; ch < 2; ch++) {
                if (ch < stereoBlock.length && stereoBlock[ch] != null && sample < stereoBlock[ch].length) {
                    pendingBlock[ch][pendingSamples] = stereoBlock[ch][sample];
                } else {
                    pendingBlock[ch][pendingSamples] = 0.0f;
                }
            }
            pendingSamples++;
            if (pendingSamples == SAMPLES_PER_BLOCK) {
                float[][] block = pendingBlock;
                pendingBlock = new float[2][SAMPLES_PER_BLOCK];
                pendingSamples = 0;
                if (!enqueueBlock(block)) {
                    return queuedAny;
                }
                queuedAny = true;
            }
        }
        return queuedAny;
    }

    public boolean finishInput() {
        if (closed || pendingSamples <= 0) {
            return false;
        }
        float[][] block = pendingBlock;
        pendingBlock = new float[2][SAMPLES_PER_BLOCK];
        pendingSamples = 0;
        return enqueueBlock(block);
    }

    private boolean enqueueBlock(float[][] block) {
        try {
            while (!closed) {
                if (pcmQueue.offer(block, 250, TimeUnit.MILLISECONDS)) {
                    frameCount++;
                    return true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /** 每 tick 调用：推进 OpenAL 播放 + 更新空间位置 */
    public void tick(float[] machinePos, float[] listenerPos) {
        if (closed || !initialized)
            return;
        if (!started) {
            if (pcmQueue.size() < PREBUFFER_BLOCKS)
                return;
            started = true;
            lastFrameFeedNanos = System.nanoTime();
            frameBudget = Math.min(MAX_BLOCKS_PER_TICK, sampleRate / 20.0 / SAMPLES_PER_BLOCK);
            LOGGER.info("Stereo OpenAL 预缓冲完成: {} blocks, 开始播放", pcmQueue.size());
        }

        updateFrameBudget();
        int allowed = Math.min((int) frameBudget, MAX_BLOCKS_PER_TICK);
        int fed = 0;
        float[][] block;
        while (fed < allowed && (block = pcmQueue.poll()) != null) {
            spatialAudio.updateBedBlock(block);
            fed++;
        }
        frameBudget = Math.max(0.0, frameBudget - fed);

        if (initialized && spatialAudio != null) {
            spatialAudio.pumpQueuedAudio();
            if (listenerPos != null && machinePos != null) {
                float[][] bedPos = {
                        { -0.5f, 0, 0.866f }, { 0.5f, 0, 0.866f },
                };
                float[] forward = { machinePos[0] - listenerPos[0], 0f, machinePos[2] - listenerPos[2] };
                spatialAudio.updatePositions(bedPos, new float[0][0], listenerPos, forward);
                spatialAudio.setBedGain(0, 1.0f);
                spatialAudio.setBedGain(1, 1.0f);
            }
        }
    }

    private void updateFrameBudget() {
        long now = System.nanoTime();
        if (lastFrameFeedNanos == 0L) {
            lastFrameFeedNanos = now;
            return;
        }
        double elapsed = Math.max(0.0, (now - lastFrameFeedNanos) / 1_000_000_000.0);
        lastFrameFeedNanos = now;
        double blocksPerSecond = sampleRate / (double) SAMPLES_PER_BLOCK; // 187.5@48k, 375@96k
        frameBudget = Math.min(MAX_BLOCKS_PER_TICK, frameBudget + elapsed * blocksPerSecond);
    }

    /** 设置实际音频采样率（如 96000 for Hi-Res FLAC），必须在初始化前调用 */
    public void setSampleRate(int sr) {
        this.sampleRate = sr > 0 ? sr : 48000;
    }

    public void cleanup() {
        closed = true;
        pcmQueue.clear();
        pendingBlock = new float[2][SAMPLES_PER_BLOCK];
        pendingSamples = 0;
        worker.interrupt();
        if (spatialAudio != null) {
            spatialAudio.cleanup();
            spatialAudio = null;
        }
        initialized = false;
        LOGGER.debug("StereoOpenALHandler closed ({} blocks)", frameCount);
    }

    public List<String> describeState() {
        return List.of(String.format(
                "Stereo OpenAL: initialized=%s started=%s sampleRate=%d queue=%d pendingSamples=%d blocks=%d",
                initialized, started, sampleRate, pcmQueue.size(), pendingSamples, frameCount));
    }

    private void workerLoop() {
        // Worker 仅负责延迟初始化 OpenAL（避免阻塞 HTTP 下载线程）
        while (!closed) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!initialized && !pcmQueue.isEmpty()) {
                spatialAudio = new OpenALSpatialAudio();
                spatialAudio.init(2, 0, sampleRate);
                initialized = true;
                LOGGER.debug("Stereo OpenAL 初始化: 2 声道立体声");
                break;
            }
        }
    }

    /** 将 16-bit 交错 PCM (short[]) 转换为 float32 planar [2][samples] */
    public static float[][] shortToFloatPlanar(byte[] pcmBytes, int channels) {
        int bytesPerSample = 2;
        int sourceChannels = Math.max(1, channels);
        int outChannels = Math.min(2, sourceChannels);
        int samples = pcmBytes.length / (bytesPerSample * sourceChannels);
        float[][] planar = new float[outChannels][samples];
        for (int s = 0; s < samples; s++) {
            for (int ch = 0; ch < outChannels; ch++) {
                int idx = (s * sourceChannels + ch) * 2;
                short val = (short) ((pcmBytes[idx + 1] << 8) | (pcmBytes[idx] & 0xFF));
                planar[ch][s] = val / 32768f;
            }
        }
        return planar;
    }

    /** 根据 AudioFormat 自动选择位深度（16/24/32 bit）转换 PCM */
    public static float[][] pcmToFloatPlanar(byte[] pcmBytes, javax.sound.sampled.AudioFormat format) {
        int bits = format.getSampleSizeInBits();
        int sourceChannels = Math.max(1, format.getChannels());
        int outChannels = Math.min(2, sourceChannels);
        boolean bigEndian = format.isBigEndian();

        if (bits <= 16) {
            return shortToFloatPlanar(pcmBytes, sourceChannels);
        }

        // 24-bit 或 32-bit: 使用 int 精度读取
        int bytesPerSample = bits / 8;
        if (bytesPerSample < 2)
            bytesPerSample = 2;
        int samples = pcmBytes.length / (bytesPerSample * sourceChannels);

        float[][] planar = new float[outChannels][samples];
        for (int s = 0; s < samples; s++) {
            for (int ch = 0; ch < outChannels; ch++) {
                int idx = (s * sourceChannels + ch) * bytesPerSample;
                int val;
                if (bytesPerSample == 3) {
                    // 24-bit PCM
                    int b0 = pcmBytes[idx] & 0xFF;
                    int b1 = pcmBytes[idx + 1] & 0xFF;
                    int b2 = pcmBytes[idx + 2] & 0xFF;
                    val = bigEndian ? ((b0 << 16) | (b1 << 8) | b2) : ((b2 << 16) | (b1 << 8) | b0);
                    int sign = bigEndian ? b0 : b2;
                    if ((sign & 0x80) != 0)
                        val |= 0xFF000000; // sign-extend
                    planar[ch][s] = val / 8388608f;
                } else {
                    // 32-bit PCM
                    int b0 = pcmBytes[idx] & 0xFF;
                    int b1 = pcmBytes[idx + 1] & 0xFF;
                    int b2 = pcmBytes[idx + 2] & 0xFF;
                    int b3 = pcmBytes[idx + 3] & 0xFF;
                    val = bigEndian
                            ? ((b0 << 24) | (b1 << 16) | (b2 << 8) | b3)
                            : ((b3 << 24) | (b2 << 16) | (b1 << 8) | b0);
                    planar[ch][s] = val / 2147483648f;
                }
            }
        }
        return planar;
    }
}
