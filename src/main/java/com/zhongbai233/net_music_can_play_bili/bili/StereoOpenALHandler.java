package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;

import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.sync.AudioSyncPolicy;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.LifecycleClose;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
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
public class StereoOpenALHandler implements com.zhongbai233.net_music_can_play_bili.client.audio.AudioOutputHandle {
    private static final boolean MUTE_MAIN_WHEN_RELAYS_CONNECTED = Boolean.parseBoolean(
            System.getProperty("ncpb.bili.audio.relay.mute_main_when_connected",
                    System.getProperty("ncpb.bili.audio.relay.mute_main_when_started", "true")));

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SAMPLES_PER_BLOCK = 256;
    private static final int QUEUE_CAPACITY = 512;
    private static final int PREBUFFER_BLOCKS = 96;
    private static final int MAX_BLOCKS_PER_TICK = 64;
    private static final AudioSyncPolicy SYNC_POLICY = AudioSyncPolicy.fromSystemProperties();
    private static final float[][] BED_POSITIONS = {
            { -0.5f, 0, 0.866f }, { 0.5f, 0, 0.866f },
    };
    private static final float[][] EMPTY_OBJECT_POSITIONS = new float[0][0];

    private final BlockingQueue<float[][]> pcmQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final float[] forwardToMachine = new float[3];
    private final SpatialFrontSmoother frontSmoother = new SpatialFrontSmoother();
    private final Thread worker;
    private volatile boolean closed;
    private volatile boolean started;
    private long totalSamplesFed;
    private volatile long totalInputSamples;
    private volatile long discardInputUntilSample;
    private volatile boolean resetPendingInput;
    private long lastFrameFeedNanos;
    private double frameBudget;

    private volatile OpenALSpatialAudio spatialAudio;
    private volatile boolean initialized;
    private int frameCount;
    private volatile int pcmQueueHighWater;
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
        worker = NetMusicThreadFactory.daemonThread("StereoOpenALWorker", this::workerLoop);
        worker.start();
    }

    /** 将解码后的立体声 float32 PCM 拆成固定 256-sample block 入队，避免 FLAC 可变块长影响播放速度 */
    public boolean enqueuePcm(float[][] stereoBlock) {
        if (stereoBlock == null || closed)
            return false;
        int samples = sampleCount(stereoBlock);
        if (samples <= 0)
            return false;
        if (resetPendingInput) {
            pendingBlock = new float[2][SAMPLES_PER_BLOCK];
            pendingSamples = 0;
            resetPendingInput = false;
        }
        boolean queuedAny = false;
        float crossfeed = BiliConfig.stereoCrossfeedAmount();
        float keep = 1.0f - crossfeed;
        for (int sample = 0; sample < samples && !closed; sample++) {
            if (resetPendingInput) {
                pendingBlock = new float[2][SAMPLES_PER_BLOCK];
                pendingSamples = 0;
                resetPendingInput = false;
            }
            long inputSample = totalInputSamples++;
            if (inputSample < discardInputUntilSample) {
                continue;
            }
            float left = sampleAt(stereoBlock, 0, sample);
            float right = sampleAt(stereoBlock, 1, sample);
            if (crossfeed > 0.0f) {
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
                    pcmQueueHighWater = Math.max(pcmQueueHighWater, pcmQueue.size());
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
        tick(machinePos, listenerPos, Long.MAX_VALUE, false);
    }

    /** 每次客户端位置同步调用：推进 OpenAL 播放 + 更新空间位置 */
    public void tick(float[] machinePos, float[] listenerPos, long targetRelativeTicks) {
        tick(machinePos, listenerPos, targetRelativeTicks, false);
    }

    /** 每次客户端位置同步调用：推进 OpenAL 播放 + 更新空间位置 */
    public void tick(float[] machinePos, float[] listenerPos, long targetRelativeTicks,
            boolean followLocalPlayerFront) {
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
        if (hardFlushIfAhead(targetRelativeTicks)) {
            frameBudget = Math.min(frameBudget, 1.0D);
        }
        if (hardDropDecodedBacklog(targetRelativeTicks)) {
            frameBudget = Math.min(frameBudget, 1.0D);
        }
        if (hardFlushIfOutputLagging(targetRelativeTicks)) {
            frameBudget = Math.min(frameBudget, 1.0D);
        }
        int allowed = isAheadOfTarget(targetRelativeTicks) ? 0 : allowedBlocksForTarget(targetRelativeTicks);
        int fed = 0;
        float[][] block;
        OpenALSpatialAudio output = spatialAudio;
        if (output == null) {
            return;
        }
        while (fed < allowed && (block = pcmQueue.peek()) != null) {
            if (!output.updateBedBlock(block)) {
                break;
            }
            pcmQueue.poll();
            updateAudioLevel(block);
            fed++;
            feedRelays(block);
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
                spatialAudio.updatePositions(BED_POSITIONS, EMPTY_OBJECT_POSITIONS, listenerPos,
                        frontSmoother.update(forwardToMachine, followLocalPlayerFront));
                float distance = distance(listenerPos, machinePos);
                float gain = spatialGainForDistance(distance, userVolume);
                lastDistance = distance;
                lastGain = gain;
                float gv = SpeakerRelayMutePolicy.shouldMuteMain(MUTE_MAIN_WHEN_RELAYS_CONNECTED, relays.size())
                        ? 0f
                        : gain * gameVolume();
                spatialAudio.setBedGain(0, gv);
                spatialAudio.setBedGain(1, gv);
            }
        }
        // 驱动所有音响 relay（relay 使用自身存储的音响位置，不传 machinePos）
        for (SpeakerAudioRelay relay : relays) {
            relay.tick(listenerPos);
        }
    }

    private boolean isAheadOfTarget(long targetRelativeTicks) {
        if (!started) {
            return false;
        }
        return SYNC_POLICY.isAhead(getFedPositionTicks(), getPositionTicks(), targetRelativeTicks);
    }

    private boolean hardFlushIfAhead(long targetRelativeTicks) {
        if (targetRelativeTicks == Long.MAX_VALUE || !started || spatialAudio == null) {
            return false;
        }
        long fedTicks = getFedPositionTicks();
        long audibleTicks = getPositionTicks();
        if (SYNC_POLICY.shouldFlushAhead(fedTicks, audibleTicks, targetRelativeTicks)) {
            long consumedSamples = spatialAudio.flushQueuedAudio();
            totalSamplesFed = Math.max(0L, consumedSamples);
            for (SpeakerAudioRelay relay : relays) {
                relay.flushQueuedAudio();
            }
            return true;
        }
        return false;
    }

    private boolean hardFlushIfOutputLagging(long targetRelativeTicks) {
        if (targetRelativeTicks == Long.MAX_VALUE || !started || spatialAudio == null) {
            return false;
        }
        long audibleTicks = getPositionTicks();
        long fedTicks = getFedPositionTicks();
        long audibleLag = targetRelativeTicks - audibleTicks;
        if (!SYNC_POLICY.shouldFlushOutputLag(audibleTicks, fedTicks, targetRelativeTicks)) {
            return false;
        }
        long targetSamples = Math.max(0L, targetRelativeTicks) * samplesPerTick();
        spatialAudio.flushQueuedAudio(targetSamples);
        totalSamplesFed = targetSamples;
        for (SpeakerAudioRelay relay : relays) {
            relay.flushQueuedAudio();
        }
        LOGGER.warn(
                "Stereo OpenAL 输出队列落后过多，已丢弃待播放缓冲以追赶: audible={}ticks target={}ticks fed={}ticks lag={}ticks",
                audibleTicks, targetRelativeTicks, fedTicks, audibleLag);
        return true;
    }

    private boolean hardDropDecodedBacklog(long targetRelativeTicks) {
        if (targetRelativeTicks == Long.MAX_VALUE || !started || spatialAudio == null) {
            return false;
        }
        long audibleTicks = getPositionTicks();
        long fedTicks = getFedPositionTicks();
        if (!SYNC_POLICY.shouldDropDecodedBacklog(audibleTicks, fedTicks, targetRelativeTicks)) {
            return false;
        }
        long targetSamples = Math.max(0L, targetRelativeTicks) * samplesPerTick();
        long inputSamples = totalInputSamples;
        long baselineSamples = Math.max(targetSamples, inputSamples);
        discardInputUntilSample = Math.max(discardInputUntilSample, baselineSamples);
        resetPendingInput = true;
        pcmQueue.clear();
        spatialAudio.flushQueuedAudio(baselineSamples);
        totalSamplesFed = baselineSamples;
        for (SpeakerAudioRelay relay : relays) {
            relay.flushQueuedAudio();
        }
        LOGGER.warn(
                "Stereo OpenAL 解码与输出同时落后，已丢弃陈旧 PCM 追赶: audible={}ticks target={}ticks fed={}ticks input={}samples baseline={}samples",
                audibleTicks, targetRelativeTicks, fedTicks, inputSamples, baselineSamples);
        return true;
    }

    /**
     * 拆分声道转发；开启“融合未分配声道”的音响会额外接收当前普通音频中
     * 没有被其它音响领取的声道。已被任意音响选择的声道绝不再混入其它 relay，
     * 避免 L/R 双音响都开融合时互相串台。
     */
    private void feedRelays(float[][] block) {
        if (relays.isEmpty() || block == null || block.length == 0) {
            return;
        }
        for (SpeakerAudioRelay relay : relays) {
            int ch = relay.getChannelIndex();
            if (ch < 0 || ch >= block.length) {
                continue;
            }
            if (!relay.isAutoMixJoc()) {
                relay.feedChannel(block[ch]);
                continue;
            }
            float[] mixed = block[ch].clone();
            for (int sourceChannel = 0; sourceChannel < block.length; sourceChannel++) {
                if (sourceChannel == ch || isChannelClaimedByAnyRelay(sourceChannel)) {
                    continue;
                }
                mixInto(mixed, block[sourceChannel], stereoMixGain(block.length));
            }
            relay.feedMono(mixed);
        }
    }

    private boolean isChannelClaimedByAnyRelay(int channelIndex) {
        for (SpeakerAudioRelay relay : relays) {
            if (relay.getChannelIndex() == channelIndex) {
                return true;
            }
        }
        return false;
    }

    private static float stereoMixGain(int channelCount) {
        return channelCount <= 2 ? 0.65f : 0.55f;
    }

    private static void mixInto(float[] target, float[] source, float gain) {
        if (target == null || source == null || gain <= 0.0f) {
            return;
        }
        int n = Math.min(target.length, source.length);
        for (int i = 0; i < n; i++) {
            target[i] = softClip(target[i] + source[i] * gain);
        }
    }

    private int allowedBlocksForTarget(long targetRelativeTicks) {
        return SYNC_POLICY.allowedUnits(frameBudget, MAX_BLOCKS_PER_TICK, getFedPositionTicks(), targetRelativeTicks);
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

    private long samplesPerTick() {
        return Math.max(1, sampleRate) / 20L;
    }

    /** 设置实际音频采样率（如 96000 for Hi-Res FLAC），必须在初始化前调用 */
    public void setSampleRate(int sr) {
        this.sampleRate = sr > 0 ? sr : 48000;
    }

    public void cleanup() {
        hardStopOutput();
        closed = true;
        pcmQueue.clear();
        pendingBlock = new float[2][SAMPLES_PER_BLOCK];
        pendingSamples = 0;
        LifecycleClose.interruptAndJoin(worker);
        if (spatialAudio != null) {
            spatialAudio.cleanup();
            spatialAudio = null;
        }
        initialized = false;
        LOGGER.debug("StereoOpenALHandler closed ({} blocks)", frameCount);
    }

    public void hardStopOutput() {
        started = false;
        frameBudget = 0.0D;
        lastFrameFeedNanos = 0L;
        totalSamplesFed = 0L;
        pcmQueue.clear();
        pendingBlock = new float[2][SAMPLES_PER_BLOCK];
        pendingSamples = 0;
        totalInputSamples = 0L;
        discardInputUntilSample = 0L;
        resetPendingInput = false;
        OpenALSpatialAudio sa = spatialAudio;
        if (sa != null) {
            sa.hardStopOutput();
        }
        for (SpeakerAudioRelay relay : relays) {
            relay.hardStopOutput();
        }
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
        long millis = getPositionMillis();
        return millis >= 0L ? millis * 20L / 1000L : -1L;
    }

    public long getPositionMillis() {
        if (!started) {
            return -1L;
        }
        OpenALSpatialAudio sa = spatialAudio;
        long consumed = sa != null ? sa.getConsumedSamples() : totalSamplesFed;
        return Math.round(consumed * 1000.0D / Math.max(1, sampleRate));
    }

    public long getFedPositionTicks() {
        long millis = getFedPositionMillis();
        return millis >= 0L ? millis * 20L / 1000L : -1L;
    }

    public long getFedPositionMillis() {
        if (!started) {
            return -1L;
        }
        return Math.round(Math.max(0L, totalSamplesFed) * 1000.0D / Math.max(1, sampleRate));
    }

    public long getOutputDelayMillis() {
        if (!started) {
            return 0L;
        }
        OpenALSpatialAudio sa = spatialAudio;
        long delaySamples = sa != null ? sa.getOutputDelaySamples() : 0L;
        return delaySamples > 0L ? Math.round(delaySamples * 1000.0D / Math.max(1, sampleRate)) : 0L;
    }

    public List<String> describeState() {
        return List.of(String.format(
                "Stereo OpenAL: initialized=%s started=%s sampleRate=%d queue=%d/%d peak=%d openalPending=%d pendingSamples=%d blocks=%d distance=%.2f gain=%.3f volume=%.2f level=%.3f",
                initialized, started, sampleRate, pcmQueue.size(), QUEUE_CAPACITY, pcmQueueHighWater,
                spatialAudio != null ? spatialAudio.pendingMediaBlocks() : 0, pendingSamples, frameCount, lastDistance,
                lastGain,
                userVolume, audioLevel()));
    }

    private static float spatialGainForDistance(float d, float volume) {
        return AudioUtils.spatialGainForDistance(d, volume);
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
                OpenALSpatialAudio next = new OpenALSpatialAudio();
                if (!next.init(2, 0, sampleRate)) {
                    next.cleanup();
                    spatialAudio = null;
                    continue;
                }
                spatialAudio = next;
                initialized = true;
                LOGGER.debug("Stereo OpenAL 初始化: 2 声道立体声");
                // 不 break：设备丢失后 initialized 会被重置为 false，需要继续循环等待重建
            }
        }
    }

}
