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
    private static final float[][] BED_POSITIONS = {
            { -0.5f, 0, 0.866f }, { 0.5f, 0, 0.866f },
    };
    private static final float[][] EMPTY_OBJECT_POSITIONS = new float[0][0];

    private final BlockingQueue<float[][]> pcmQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final float[] forwardToMachine = new float[3];
    private final Thread worker;
    private volatile boolean closed;
    private volatile boolean started;
    private long totalSamplesFed;
    private long lastFrameFeedNanos;
    private double frameBudget;

    private OpenALSpatialAudio spatialAudio;
    private boolean initialized;
    private int frameCount;
    private int sampleRate = 48000;
    private float[][] pendingBlock = new float[2][SAMPLES_PER_BLOCK];
    private int pendingSamples;
    private volatile float lastDistance = Float.NaN;
    private volatile float lastGain = 1.0f;
    private volatile float userVolume = 1.0f;
    private volatile float lastAudioLevel;
    private volatile long lastAudioLevelNanos;

    /** 音响转发目标列表 */
    private final java.util.concurrent.CopyOnWriteArrayList<SpeakerAudioRelay> relays = new java.util.concurrent.CopyOnWriteArrayList<>();

    public StereoOpenALHandler() {
        worker = new Thread(this::workerLoop, "StereoOpenALWorker");
        worker.setDaemon(true);
        worker.start();
    }

    /** 将解码后的立体声 float32 PCM 拆成固定 256-sample block 入队，避免 FLAC 可变块长影响播放速度 */
    public boolean enqueuePcm(float[][] stereoBlock) {
        if (stereoBlock == null || closed)
            return false;
        int samples = sampleCount(stereoBlock);
        if (samples <= 0)
            return false;
        boolean queuedAny = false;
        for (int sample = 0; sample < samples && !closed; sample++) {
            float left = sampleAt(stereoBlock, 0, sample);
            float right = sampleAt(stereoBlock, 1, sample);
            float crossfeed = BiliConfig.stereoCrossfeedAmount();
            if (crossfeed > 0.0f) {
                float keep = 1.0f - crossfeed;
                pendingBlock[0][pendingSamples] = softClip(left * keep + right * crossfeed);
                pendingBlock[1][pendingSamples] = softClip(right * keep + left * crossfeed);
            } else {
                pendingBlock[0][pendingSamples] = left;
                pendingBlock[1][pendingSamples] = right;
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

    private static float sampleAt(float[][] stereoBlock, int channel, int sample) {
        if (stereoBlock == null || stereoBlock.length == 0) {
            return 0.0f;
        }
        int sourceChannel = Math.min(channel, stereoBlock.length - 1);
        float[] channelData = stereoBlock[sourceChannel];
        if (channelData == null || sample >= channelData.length) {
            return 0.0f;
        }
        return channelData[sample];
    }

    private static int sampleCount(float[][] stereoBlock) {
        int samples = 0;
        if (stereoBlock != null) {
            for (float[] channelData : stereoBlock) {
                if (channelData != null) {
                    samples = Math.max(samples, channelData.length);
                }
            }
        }
        return samples;
    }

    private static float softClip(float sample) {
        return Math.max(-1.0f, Math.min(1.0f, sample));
    }

    /** 每次客户端位置同步调用：推进 OpenAL 播放 + 更新空间位置 */
    public void tick(float[] machinePos, float[] listenerPos) {
        if (closed || !initialized)
            return;
        if (net.minecraft.client.Minecraft.getInstance().isPaused())
            return;
        if (!started) {
            if (pcmQueue.size() < PREBUFFER_BLOCKS)
                return;
            started = true;
            lastFrameFeedNanos = System.nanoTime();
            frameBudget = Math.min(MAX_BLOCKS_PER_TICK, sampleRate / 20.0 / SAMPLES_PER_BLOCK);
            // 统一通知所有 relay 可以开始播放（替代各自独立的 280ms 计时器）
            for (SpeakerAudioRelay relay : relays) {
                relay.setHandlerStarted(true);
            }
            LOGGER.debug("Stereo OpenAL 预缓冲完成: {} blocks, 开始播放", pcmQueue.size());
        }

        updateFrameBudget();
        int allowed = Math.min((int) frameBudget, MAX_BLOCKS_PER_TICK);
        int fed = 0;
        float[][] block;
        while (fed < allowed && (block = pcmQueue.poll()) != null) {
            updateAudioLevel(block);
            spatialAudio.updateBedBlock(block);
            fed++;
            // 拆分声道转发：每个 relay 只拿到它选的声道数据
            for (SpeakerAudioRelay relay : relays) {
                int ch = relay.getChannelIndex();
                if (ch >= 0 && ch < block.length) {
                    relay.feedChannel(block[ch]);
                }
            }
        }
        totalSamplesFed += (long) fed * SAMPLES_PER_BLOCK;
        frameBudget = Math.max(0.0, frameBudget - fed);

        if (initialized && spatialAudio != null) {
            if (spatialAudio.isDeviceLost()) {
                LOGGER.warn("Stereo OpenAL device lost, reinitializing...");
                spatialAudio.cleanup();
                spatialAudio = null;
                initialized = false;
                started = false;
                return;
            }
            spatialAudio.pumpQueuedAudio();
            if (listenerPos != null && machinePos != null) {
                forwardToMachine[0] = machinePos[0] - listenerPos[0];
                forwardToMachine[1] = 0f;
                forwardToMachine[2] = machinePos[2] - listenerPos[2];
                spatialAudio.updatePositions(BED_POSITIONS, EMPTY_OBJECT_POSITIONS, listenerPos, forwardToMachine);
                float distance = distance(listenerPos, machinePos);
                float gain = gainForDistance(distance);
                lastDistance = distance;
                lastGain = gain;
                float gv = allRelaysStarted() ? 0f : gain * userVolume * gameVolume();
                spatialAudio.setBedGain(0, gv);
                spatialAudio.setBedGain(1, gv);
            }
        }
        // 驱动所有音响 relay（relay 使用自身存储的音响位置，不传 machinePos）
        for (SpeakerAudioRelay relay : relays) {
            relay.tick(listenerPos);
        }
    }

    /** 所有音响 relay 是否都已度过初始静音期，正在输出真实 PCM */
    private boolean allRelaysStarted() {
        if (relays.isEmpty())
            return false;
        for (SpeakerAudioRelay relay : relays) {
            if (!relay.isStarted())
                return false;
        }
        return true;
    }

    private static float gameVolume() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.options == null)
            return 1.0f;
        return mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER)
                * mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.RECORDS);
    }

    public void setUserVolume(float volume) {
        userVolume = clampGain(volume);
    }

    public float userVolume() {
        return userVolume;
    }

    public void addRelay(SpeakerAudioRelay relay) {
        if (relay != null && !relays.contains(relay)) {
            relay.setSampleRate(sampleRate); // 注入正确采样率（如 44100Hz AAC/FLAC），避免 relay 用错误速率播放
            if (started) {
                relay.setHandlerStarted(true); // 已开始播放时新 relay 立即获得启动信号
            }
            relays.add(relay);
        }
    }

    public void removeRelay(SpeakerAudioRelay relay) {
        if (relay != null)
            relays.remove(relay);
    }

    public float audioLevel() {
        long ageNanos = System.nanoTime() - lastAudioLevelNanos;
        if (ageNanos <= 0L) {
            return lastAudioLevel;
        }
        float ageSeconds = ageNanos / 1_000_000_000.0f;
        float decay = Math.max(0.0f, 1.0f - ageSeconds * 2.5f);
        return clampGain(lastAudioLevel * decay);
    }

    private void updateAudioLevel(float[][] block) {
        if (block == null || block.length == 0) {
            return;
        }
        float peak = 0.0f;
        double sum = 0.0;
        int count = 0;
        for (float[] channel : block) {
            if (channel == null) {
                continue;
            }
            for (float sample : channel) {
                float abs = Math.abs(sample);
                peak = Math.max(peak, abs);
                sum += sample * sample;
                count++;
            }
        }
        float rms = count > 0 ? (float) Math.sqrt(sum / count) : 0.0f;
        lastAudioLevel = clampGain(Math.max(peak * 0.7f, rms * 2.2f));
        lastAudioLevelNanos = System.nanoTime();
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
        try {
            worker.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (spatialAudio != null) {
            spatialAudio.cleanup();
            spatialAudio = null;
        }
        initialized = false;
        LOGGER.debug("StereoOpenALHandler closed ({} blocks)", frameCount);
    }

    public boolean hasStarted() {
        return started;
    }

    /**
     * 当前已播放的歌词 tick 数。1 tick = 50ms 音频时间。
     * 使用 OpenAL 实际消费的样本数（而非已喂入数），消除 ~256ms 预缓冲偏差
     * 
     * @return 播放位置（tick），未开始播放时返回 -1
     */
    public long getPositionTicks() {
        if (!started) {
            return -1L;
        }
        OpenALSpatialAudio sa = spatialAudio;
        long consumed = sa != null ? sa.getConsumedSamples() : totalSamplesFed;
        return consumed * 20L / sampleRate;
    }

    public List<String> describeState() {
        return List.of(String.format(
                "Stereo OpenAL: initialized=%s started=%s sampleRate=%d queue=%d pendingSamples=%d blocks=%d distance=%.2f gain=%.3f volume=%.2f level=%.3f",
                initialized, started, sampleRate, pcmQueue.size(), pendingSamples, frameCount, lastDistance, lastGain,
                userVolume, audioLevel()));
    }

    private static float gainForDistance(float d) {
        return AudioUtils.gainForDistance(d);
    }

    private static float clampGain(float gain) {
        return AudioUtils.clampGain(gain);
    }

    private static float distance(float[] a, float[] b) {
        return AudioUtils.distance(a, b);
    }

    private void workerLoop() {
        // Worker 负责延迟初始化 + 设备丢失后重建 OpenAL 管线
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
                // 不 break：设备丢失后 initialized 会被重置为 false，需要继续循环等待重建
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
