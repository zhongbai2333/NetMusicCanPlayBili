package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;

import com.zhongbai233.net_music_can_play_bili.client.PlaybackLatencyBench;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.codec.*;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.LifecycleClose;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class DolbyAudioHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float SPATIAL_RADIUS = 1.5f;
    private static final float SILENCE_GATE_PEAK = 1.0e-4f;
    private static final float SILENCE_GATE_RMS = 2.0e-5f;
    private static final float PCM_OUTPUT_GAIN = 1.0f;
    private static final float JOC_OBJECT_OUTPUT_GAIN = 0.20f;
    private static final boolean ENABLE_JOC_OBJECTS = true;
    private static final double EC3_FRAMES_PER_SECOND = 48000.0 / 1536.0;
    private static final int MAX_FRAMES_PER_TICK = 8;
    private static final int PREBUFFER_FRAMES = Integer.getInteger("ncpb.bili.audio.openal.dolby_prebuffer_frames", 8);
    private static final boolean MUTE_MAIN_WHEN_RELAYS_CONNECTED = Boolean.parseBoolean(
            System.getProperty("ncpb.bili.audio.relay.mute_main_when_connected",
                    System.getProperty("ncpb.bili.audio.relay.mute_main_when_started", "true")));
    private static final long AUDIO_CATCH_UP_START_TICKS = Long.getLong(
            "bili.audio.sync.catch_up_start_ticks", 8L);
    private static final long AUDIO_CATCH_UP_FULL_TICKS = Long.getLong(
            "bili.audio.sync.catch_up_full_ticks", 28L);
    private static final long OUTPUT_LAG_FLUSH_TICKS = Long.getLong(
            "bili.audio.timeline.flush_output_lag_ticks", 40L);
    private static final long OUTPUT_LAG_FED_NEAR_TARGET_TICKS = Long.getLong(
            "bili.audio.timeline.output_lag_fed_near_target_ticks", 8L);
    private static final int RAW_QUEUE_CAPACITY = 512;
    private static final int PROCESSED_QUEUE_CAPACITY = 512;

    private final BlockingQueue<byte[]> rawQueue = new LinkedBlockingQueue<>(RAW_QUEUE_CAPACITY);
    private final BlockingQueue<ProcessedFrame> processedQueue = new LinkedBlockingQueue<>(PROCESSED_QUEUE_CAPACITY);
    private final Eac3JocDecoder jocDecoder = new Eac3JocDecoder();
    private final float[] forwardToMachine = new float[3];
    private final SpatialFrontSmoother frontSmoother = new SpatialFrontSmoother();
    private final Thread worker;
    private volatile boolean closed;
    private boolean playbackStarted;
    private long lastFrameFeedNanos;
    private double frameBudget;

    private volatile OpenALSpatialAudio spatialAudio;
    private volatile boolean initialized;
    private int numBedChannels;
    private int numObjects;
    private float[][] bedPositions;
    private float[][] objectPositions;
    private volatile int lastJocSequence = -1;
    private volatile int lastJocAudioObjects;
    private volatile float[] lastJocObjectRms = new float[0];
    private volatile float[] lastJocObjectPeak = new float[0];
    private volatile float userVolume = 1.0f;
    private volatile int channelMask; // 0 = 全混音，否则为已启用声道的位掩码
    private volatile boolean forceStaticJoc;
    private volatile float lastAudioLevel;
    private volatile long lastAudioLevelNanos;

    /** 音响转发目标列表（线程安全） */
    private final java.util.concurrent.CopyOnWriteArrayList<SpeakerAudioRelay> relays = new java.util.concurrent.CopyOnWriteArrayList<>();

    private int frameCount;
    private int nullPcmCount;
    /** 已喂入 OpenAL 的 E-AC-3 帧总数（在 feedOpenAL 中递增） */
    private long totalFramesFedToOpenAL;
    private boolean didFirstDiag;
    private boolean didFirstDiagSpatial;
    private boolean didFirstPositionDiag;
    private boolean didJocAudioFailDiag;

    public DolbyAudioHandler() {
        // 预加载 FFmpeg native 库（~1s），在 mdat 数据到达前完成，
        // 避免 worker 线程被初始化阻塞导致 rawQueue 溢出丢帧
        Eac3NativeDecoder.preload();
        worker = NetMusicThreadFactory.daemonThread("DolbyDecodeWorker", this::workerLoop);
        worker.start();
    }

    public boolean enqueueFrame(byte[] ec3Frame) {
        if (ec3Frame == null || ec3Frame.length == 0 || closed)
            return false;
        try {
            while (!closed) {
                if (rawQueue.offer(ec3Frame, 250, TimeUnit.MILLISECONDS)) {
                    PlaybackLatencyBench.markAudioQueued(this, "dolby", rawQueue.size(),
                            totalFramesFedToOpenAL * 1536L, 48000);
                    return true;
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void tick(float[] machinePos, float[] listenerPos) {
        tick(machinePos, listenerPos, Long.MAX_VALUE, false);
    }

    public void tick(float[] machinePos, float[] listenerPos, long targetRelativeTicks) {
        tick(machinePos, listenerPos, targetRelativeTicks, false);
    }

    public void tick(float[] machinePos, float[] listenerPos, long targetRelativeTicks,
            boolean followLocalPlayerFront) {
        if (closed)
            return;
        if (net.minecraft.client.Minecraft.getInstance().isPaused())
            return;
        if (targetRelativeTicks == Long.MIN_VALUE) {
            return;
        }
        if (!playbackStarted) {
            if (processedQueue.size() < PREBUFFER_FRAMES)
                return;
            playbackStarted = true;
            lastFrameFeedNanos = System.nanoTime();
            frameBudget = MAX_FRAMES_PER_TICK;
            PlaybackLatencyBench.markAudioStarted(this, "dolby", processedQueue.size(),
                    totalFramesFedToOpenAL * 1536L, 48000);
            for (SpeakerAudioRelay relay : relays) {
                relay.setHandlerStarted(true);
            }
            LOGGER.debug("Dolby OpenAL 预缓冲完成: {} 帧", processedQueue.size());
        }
        updateFrameBudget();
        if (hardFlushIfAhead(targetRelativeTicks)) {
            frameBudget = Math.min(frameBudget, 1.0D);
        }
        if (hardFlushIfOutputLagging(targetRelativeTicks)) {
            frameBudget = Math.min(frameBudget, 1.0D);
        }
        int processed = 0;
        ProcessedFrame pf;
        boolean audioAhead = isAheadOfTarget(targetRelativeTicks);
        if (!audioAhead) {
            int allowedFrames = allowedFramesForTarget(targetRelativeTicks);
            while (processed < allowedFrames && (pf = processedQueue.poll()) != null) {
                feedOpenAL(pf);
                processed++;
            }
        }
        frameBudget = Math.max(0.0, frameBudget - processed);

        if (initialized && spatialAudio != null) {
            if (spatialAudio.isDeviceLost()) {
                LOGGER.warn("Dolby OpenAL device lost, reinitializing...");
                spatialAudio.cleanup();
                spatialAudio = null;
                initialized = false;
                numBedChannels = 0;
                numObjects = 0;
                return;
            }
            spatialAudio.pumpQueuedAudio();
            updateSpatialState(machinePos, listenerPos, followLocalPlayerFront);
        }
        // 驱动所有音响 relay（relay 使用自身存储的音响位置，不传 machinePos）
        for (SpeakerAudioRelay relay : relays) {
            relay.tick(listenerPos);
        }
    }

    public int queuedFrames() {
        return rawQueue.size();
    }

    private boolean isAheadOfTarget(long targetRelativeTicks) {
        if (targetRelativeTicks == Long.MAX_VALUE || !playbackStarted) {
            return false;
        }
        long fedTicks = getFedPositionTicks();
        if (fedTicks >= 0L && fedTicks > targetRelativeTicks) {
            return true;
        }
        long audibleTicks = getPositionTicks();
        return audibleTicks >= 0L && audibleTicks > targetRelativeTicks;
    }

    private boolean hardFlushIfAhead(long targetRelativeTicks) {
        if (targetRelativeTicks == Long.MAX_VALUE || !playbackStarted || spatialAudio == null) {
            return false;
        }
        long toleranceTicks = Long.getLong("ncpb.bili.audio.timeline.flush_ahead_ticks", 12L);
        long fedTicks = getFedPositionTicks();
        long audibleTicks = getPositionTicks();
        if ((fedTicks >= 0L && fedTicks - targetRelativeTicks > toleranceTicks)
                || (audibleTicks >= 0L && audibleTicks - targetRelativeTicks > toleranceTicks)) {
            long consumedSamples = spatialAudio.flushQueuedAudio();
            totalFramesFedToOpenAL = Math.max(0L, consumedSamples / 1536L);
            for (SpeakerAudioRelay relay : relays) {
                relay.flushQueuedAudio();
            }
            return true;
        }
        return false;
    }

    private boolean hardFlushIfOutputLagging(long targetRelativeTicks) {
        if (targetRelativeTicks == Long.MAX_VALUE || !playbackStarted || spatialAudio == null) {
            return false;
        }
        long lagThreshold = Math.max(0L, OUTPUT_LAG_FLUSH_TICKS);
        if (lagThreshold <= 0L) {
            return false;
        }
        long audibleTicks = getPositionTicks();
        long fedTicks = getFedPositionTicks();
        if (audibleTicks < 0L || fedTicks < 0L) {
            return false;
        }
        long audibleLag = targetRelativeTicks - audibleTicks;
        long fedDistance = targetRelativeTicks - fedTicks;
        if (audibleLag <= lagThreshold
                || fedDistance > Math.max(0L, OUTPUT_LAG_FED_NEAR_TARGET_TICKS)) {
            return false;
        }
        long targetFrames = ticksToEc3Frames(Math.max(0L, targetRelativeTicks));
        spatialAudio.flushQueuedAudio(targetFrames * 1536L);
        totalFramesFedToOpenAL = targetFrames;
        for (SpeakerAudioRelay relay : relays) {
            relay.flushQueuedAudio();
        }
        LOGGER.warn(
                "Dolby OpenAL 输出队列落后过多，已丢弃待播放缓冲以追赶: audible={}ticks target={}ticks fed={}ticks lag={}ticks",
                audibleTicks, targetRelativeTicks, fedTicks, audibleLag);
        return true;
    }

    private int allowedFramesForTarget(long targetRelativeTicks) {
        int base = Math.min((int) frameBudget, MAX_FRAMES_PER_TICK);
        if (targetRelativeTicks == Long.MAX_VALUE || !playbackStarted) {
            return base;
        }
        long positionTicks = getFedPositionTicks();
        if (positionTicks < 0L) {
            return base;
        }
        long behindTicks = targetRelativeTicks - positionTicks;
        if (behindTicks <= AUDIO_CATCH_UP_START_TICKS) {
            return base;
        }
        long fullTicks = Math.max(AUDIO_CATCH_UP_START_TICKS + 1L, AUDIO_CATCH_UP_FULL_TICKS);
        double ratio = Math.min(1.0D, behindTicks / (double) fullTicks);
        int extra = (int) Math.round((MAX_FRAMES_PER_TICK - base) * ratio);
        return Math.max(base, Math.min(MAX_FRAMES_PER_TICK, base + extra));
    }

    public List<String> describeSources(float[] machinePos, float[] listenerPos) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("Dolby: initialized=%s beds=%d objects=%d JOC音频=%s rawQ=%d procQ=%d frames=%d",
                initialized, numBedChannels, numObjects, BiliConfig.dolbyJocEnabled ? "on" : "off",
                rawQueue.size(), processedQueue.size(), frameCount));
        if (machinePos != null && listenerPos != null) {
            float yaw = (float) Math.atan2(machinePos[0] - listenerPos[0], machinePos[2] - listenerPos[2]);
            float distance = distance(listenerPos, machinePos);
            float gain = spatialGainForDistance(distance, userVolume);
            lines.add(String.format("音乐机=%s 玩家耳位=%s 距离=%.2f gain=%.3f yaw=%.1f°",
                    fmtPos(machinePos), fmtPos(listenerPos), distance, gain, Math.toDegrees(yaw)));
        } else {
            lines.add("音乐机/玩家位置尚未同步");
        }
        if (bedPositions != null) {
            String[] names = bedChannelNames(numBedChannels);
            for (int i = 0; i < Math.min(bedPositions.length, 8); i++) {
                String name = names != null && i < names.length ? names[i] : "bed" + i;
                lines.add(String.format("床声道 %s local=%s", name, fmtPos(bedPositions[i])));
            }
        } else {
            lines.add("床声道位置尚未初始化");
        }
        if (objectPositions != null && objectPositions.length > 0) {
            lines.add(String.format("最近JOC音频: seq=%d objs=%d active=%d peakMax=%.5f",
                    lastJocSequence, lastJocAudioObjects, countActiveObjects(lastJocObjectRms),
                    max(lastJocObjectPeak)));
            int limit = Math.min(objectPositions.length, 8);
            for (int i = 0; i < limit; i++) {
                float rms = i < lastJocObjectRms.length ? lastJocObjectRms[i] : 0f;
                float peak = i < lastJocObjectPeak.length ? lastJocObjectPeak[i] : 0f;
                lines.add(String.format("JOC对象 #%02d local=%s rms=%.5f peak=%.5f", i, fmtPos(objectPositions[i]), rms,
                        peak));
            }
            if (objectPositions.length > limit) {
                lines.add("其余 JOC 对象省略：" + (objectPositions.length - limit));
            }
        } else {
            lines.add("JOC对象声源未初始化");
        }
        return lines;
    }

    public void setUserVolume(float volume) {
        userVolume = clampGain(volume);
    }

    public float userVolume() {
        return userVolume;
    }

    /** 设置声道掩码（7.1.4 位掩码），0=全声道混音，其他值按位启用对应声道 */
    public void setChannelMask(int mask) {
        this.channelMask = mask;
    }

    /** 设置是否强制 JOC 对象声源固定在 machinePos（音响模式） */
    public void setForceStaticJoc(boolean v) {
        this.forceStaticJoc = v;
    }

    /** 添加音响转发目标 */
    public void addRelay(SpeakerAudioRelay relay) {
        if (relay != null && !relays.contains(relay)) {
            relay.setSampleRate(48000); // EC-3 Dolby 固定 48kHz，显式设置以防 relay 默认值被意外覆盖
            if (playbackStarted) {
                relay.setHandlerStarted(true); // 已开始播放时新 relay 立即获得启动信号
            }
            relays.add(relay);
        }
    }

    /** 移除音响转发目标 */
    public void removeRelay(SpeakerAudioRelay relay) {
        if (relay != null) {
            relays.remove(relay);
        }
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

    public long getPositionTicks() {
        long millis = getPositionMillis();
        return millis >= 0L ? millis * 20L / 1000L : -1L;
    }

    public long getPositionMillis() {
        if (!playbackStarted) {
            return -1L;
        }
        OpenALSpatialAudio sa = spatialAudio;
        long consumed;
        if (sa != null) {
            consumed = sa.getConsumedSamples();
            if (consumed > 0L) {
                PlaybackLatencyBench.markAudioConsumed(this, "dolby", consumed, 48000);
                return Math.round(consumed * 1000.0D / 48000.0D);
            }
        }

        PlaybackLatencyBench.markAudioConsumed(this, "dolby", totalFramesFedToOpenAL * 1536L, 48000);
        return Math.round(totalFramesFedToOpenAL * 1536L * 1000.0D / 48000.0D);
    }

    /** 已排入 OpenAL 的媒体时间线，用于喂入节流判断，避免把输出端固定延迟误判成音频落后。 */
    public long getFedPositionTicks() {
        long millis = getFedPositionMillis();
        return millis >= 0L ? millis * 20L / 1000L : -1L;
    }

    public long getFedPositionMillis() {
        if (!playbackStarted) {
            return -1L;
        }
        return Math.round(totalFramesFedToOpenAL * 1536L * 1000.0D / 48000.0D);
    }

    public long getOutputDelayMillis() {
        if (!playbackStarted) {
            return 0L;
        }
        OpenALSpatialAudio sa = spatialAudio;
        long delaySamples = sa != null ? sa.getOutputDelaySamples() : 0L;
        return delaySamples > 0L ? Math.round(delaySamples * 1000.0D / 48000.0D) : 0L;
    }

    public void cleanup() {
        hardStopOutput();
        closed = true;
        rawQueue.clear();
        processedQueue.clear();
        playbackStarted = false;
        lastFrameFeedNanos = 0L;
        frameBudget = 0.0;
        LifecycleClose.interruptAndJoin(worker);
        if (spatialAudio != null) {
            spatialAudio.cleanup();
            spatialAudio = null;
        }
        initialized = false;
        LOGGER.debug("DolbyAudioHandler closed ({} frames)", frameCount);
    }

    public void hardStopOutput() {
        playbackStarted = false;
        lastFrameFeedNanos = 0L;
        frameBudget = 0.0D;
        totalFramesFedToOpenAL = 0L;
        rawQueue.clear();
        processedQueue.clear();
        OpenALSpatialAudio sa = spatialAudio;
        if (sa != null) {
            sa.hardStopOutput();
        }
        for (SpeakerAudioRelay relay : relays) {
            relay.hardStopOutput();
        }
    }

    private void workerLoop() {
        Eac3NativeDecoder decoder = new Eac3NativeDecoder();
        LOGGER.debug("DolbyDecodeWorker 启动");
        while (!closed) {
            try {
                byte[] raw = rawQueue.poll(500, TimeUnit.MILLISECONDS);
                if (raw == null)
                    continue;

                ProcessedFrame pf;
                try {
                    pf = decodeOneFrame(decoder, raw);
                } catch (Exception e) {
                    LOGGER.warn("DolbyDecodeWorker 解码异常", e);
                    continue;
                }

                if (pf != null) {
                    processedQueue.put(pf);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                LOGGER.error("DolbyDecodeWorker 未预期的异常 (线程继续运行)", t);
            }
        }
        decoder.close();
        LOGGER.debug("DolbyDecodeWorker 退出 (解码 {} 帧, nullPcm={})", frameCount, nullPcmCount);
    }

    private ProcessedFrame decodeOneFrame(Eac3NativeDecoder decoder, byte[] raw) {
        frameCount++;
        float[][] pcm = decoder.decodeFrame(raw);
        if (pcm == null) {
            nullPcmCount++;
            if (!didFirstDiag) {
                didFirstDiag = true;
                LOGGER.warn("Dolby decode fail ({}bytes)", raw.length);
            }
            return null;
        }
        PcmStats stats = conditionPcm(pcm);
        lastAudioLevel = clampGain(Math.max(stats.peak() * 0.7f, stats.rms() * 2.2f));
        lastAudioLevelNanos = System.nanoTime();
        int samples = pcm.length, channels = pcm[0].length;
        if (!didFirstDiag) {
            didFirstDiag = true;
            LOGGER.debug("Dolby PCM: {}s×{}ch range=[{},{}] rms={} gated={}", samples, channels, stats.min, stats.max,
                    stats.rms, stats.gated);
        }
        float[][] pcmCh = new float[channels][samples];
        for (int i = 0; i < samples; i++)
            for (int ch = 0; ch < channels; ch++)
                pcmCh[ch][i] = pcm[i][ch];
        List<byte[]> emdfBlocks = Eac3AtmosParser.findEmdfBlocks(raw);
        Eac3AtmosParser.OamdConfig oamd = null;
        Eac3JocDecoder.JocResult jocResult = null;
        for (byte[] emdf : emdfBlocks) {
            if (oamd == null) {
                byte[] op = Eac3AtmosParser.extractOamdPayload(emdf);
                if (op != null)
                    oamd = Eac3AtmosParser.parseOamd(op);
            }
            if (BiliConfig.dolbyJocEnabled && ENABLE_JOC_OBJECTS && jocResult == null) {
                byte[] jp = Eac3AtmosParser.extractJocPayload(emdf);
                if (jp != null) {
                    try {
                        jocResult = jocDecoder.decode(jp, QmfFilterBank.TIMESLOTS);
                    } catch (Exception e) {
                    }
                }
            }
        }
        int rawObjCount = 0;
        if (ENABLE_JOC_OBJECTS && jocResult != null) {
            int audioObjects = Math.max(0, jocResult.config.objectCount);
            int metadataObjects = (oamd != null && oamd.objectElement != null) ? oamd.objectElement.dynamicObjects
                    : audioObjects;
            rawObjCount = Math.min(Math.max(0, metadataObjects), audioObjects);
        }
        int objCount = BiliConfig.dolbyJocEnabled
                ? Math.max(0, Math.min(rawObjCount, BiliConfig.dolbyMaxObjectSources()))
                : 0;
        float[][] objPcmCh = null;
        if (BiliConfig.dolbyJocEnabled && ENABLE_JOC_OBJECTS && jocResult != null && objCount > 0) {
            try {
                objPcmCh = new float[objCount][samples];
                float gain = jocResult.config.gain;
                int jocChannels = jocResult.config.channelCount;
                for (int blk = 0; blk < 6; blk++) {
                    int off = blk * 256;
                    float[][] blockPcm = buildJocDownmixBlock(pcmCh, channels, jocChannels, off);
                    float[][][][] bedQmf = QmfFilterBank.forwardMulti(blockPcm);
                    float[][][][] objQmf = QmfFilterBank.applyMixingMatrix(
                            bedQmf, jocResult.interpolatedMatrices, jocChannels, objCount);
                    float[][] objBlockPcm = QmfFilterBank.inverseMulti(objQmf);
                    for (int obj = 0; obj < objCount; obj++) {
                        for (int i = 0; i < 256; i++) {
                            objPcmCh[obj][off + i] = softClip(objBlockPcm[obj][i] * gain * JOC_OBJECT_OUTPUT_GAIN);
                        }
                    }
                }
                lastJocSequence = jocResult.config.sequence;
                lastJocAudioObjects = objCount;
                lastJocObjectRms = rmsByObject(objPcmCh, objCount);
                lastJocObjectPeak = peakByObject(objPcmCh, objCount);
            } catch (RuntimeException e) {
                objCount = 0;
                objPcmCh = null;
                if (!didJocAudioFailDiag) {
                    didJocAudioFailDiag = true;
                    LOGGER.warn("Dolby JOC 对象音频重建失败，本帧降级为 5.1 bed", e);
                }
            }
        }
        if (!didFirstDiagSpatial) {
            didFirstDiagSpatial = true;
            Eac3AtmosParser.OamdConfig oamdDiag = oamd;
            Eac3JocDecoder.JocResult jocDiag = jocResult;
            boolean ho = oamdDiag != null && oamdDiag.objectElement != null;
            boolean hj = jocDiag != null;
            if (ho || hj) {
                StringBuilder sb = new StringBuilder("Dolby spatial: ");
                if (oamdDiag != null && oamdDiag.objectElement != null) {
                    sb.append(String.format("OAMD objs=%d updates=%d",
                            oamdDiag.objectElement.dynamicObjects,
                            oamdDiag.objectElement.validPositionUpdates));
                }
                if (jocDiag != null) {
                    if (ho)
                        sb.append(", ");
                    sb.append(String.format("JOC objs=%d/%d ch=%d gain=%.2f audio=%s",
                            Math.min(jocDiag.config.objectCount, BiliConfig.dolbyMaxObjectSources()),
                            jocDiag.config.objectCount,
                            jocDiag.config.channelCount,
                            jocDiag.config.gain,
                            BiliConfig.dolbyJocEnabled ? "on" : "off"));
                }
                LOGGER.debug(sb.toString());
            } else
                LOGGER.debug("Dolby spatial: none (5.1 bed only)");
        }
        return new ProcessedFrame(pcmCh, objPcmCh, oamd, channels, objCount);
    }

    private void feedOpenAL(ProcessedFrame pf) {
        if (closed)
            return;
        int chs = pf.channels, objs = Math.max(0, Math.min(pf.objCount, BiliConfig.dolbyMaxObjectSources()));
        int targetObjects = BiliConfig.dolbyJocEnabled ? objs : 0;
        if (!initialized || numBedChannels != chs || targetObjects > numObjects) {
            float[][] oldObjectPositions = objectPositions;
            int newObjectCapacity = initialized && numBedChannels == chs
                    ? Math.max(numObjects, targetObjects)
                    : targetObjects;
            OpenALSpatialAudio oldSA = spatialAudio;
            if (oldSA != null)
                oldSA.cleanup();
            OpenALSpatialAudio next = new OpenALSpatialAudio();
            if (!next.init(chs, newObjectCapacity)) {
                next.cleanup();
                spatialAudio = null;
                initialized = false;
                return;
            }
            spatialAudio = next;
            PlaybackLatencyBench.markAudioOpenAlInitialized(this, "dolby", 48000);
            numBedChannels = chs;
            numObjects = newObjectCapacity;
            bedPositions = computeBedPositions(chs);
            objectPositions = new float[newObjectCapacity][3];
            if (oldObjectPositions != null) {
                for (int i = 0; i < Math.min(oldObjectPositions.length, objectPositions.length); i++) {
                    System.arraycopy(oldObjectPositions[i], 0, objectPositions[i], 0, 3);
                }
            }
            initialized = true;
            LOGGER.debug("Dolby OpenAL 配置: beds={}, objectCapacity={} (frameObjects={})",
                    chs, newObjectCapacity, objs);
        }
        OpenALSpatialAudio sa = spatialAudio;
        if (sa == null)
            return;
        if (pf.oamd != null)
            updateObjectOffsets(pf.oamd);
        float[][] pc = pf.pcmCh;
        for (int blk = 0; blk < 6; blk++) {
            int off = blk * 256;
            sa.updateBedFrameBlock(pc, off);
            if (numObjects > 0) {
                sa.updateObjectFrameBlock(pf.objPcmCh, off);
            }
        }
        totalFramesFedToOpenAL++;
        PlaybackLatencyBench.markAudioFed(this, "dolby", 1, totalFramesFedToOpenAL * 1536L,
                processedQueue.size(), 48000);
        feedRelays(pf);
    }

    /**
     * 拆分声道转发；开启“融合未分配声道”的音响会额外接收未被其它音响领取的
     * bed 声道和 JOC 对象音频，避免 Atmos/JOC 对象在单独音响模式下丢失。
     */
    private void feedRelays(ProcessedFrame pf) {
        if (relays.isEmpty() || pf == null || pf.pcmCh == null || pf.pcmCh.length == 0) {
            return;
        }
        List<SpeakerAudioRelay> mixTargets = autoMixTargets(pf.pcmCh.length);
        int mixTargetCount = mixTargets.size();

        for (SpeakerAudioRelay relay : relays) {
            int ch = relay.getChannelIndex();
            if (ch < 0 || ch >= pf.pcmCh.length) {
                continue;
            }
            if (!relay.isAutoMixJoc() || mixTargetCount == 0) {
                relay.feedChannel(pf.pcmCh[ch]);
                continue;
            }
            float[] mixed = pf.pcmCh[ch].clone();
            mixUnassignedBedChannels(mixed, pf.pcmCh, relay, mixTargets, mixTargetCount);
            mixJocObjects(mixed, pf.objPcmCh, relay, mixTargets, mixTargetCount);
            relay.feedMono(mixed);
        }
    }

    private List<SpeakerAudioRelay> autoMixTargets(int channelCount) {
        List<SpeakerAudioRelay> targets = new ArrayList<>();
        for (SpeakerAudioRelay relay : relays) {
            int ch = relay.getChannelIndex();
            if (relay.isAutoMixJoc() && ch >= 0 && ch < channelCount) {
                targets.add(relay);
            }
        }
        return targets;
    }

    private void mixUnassignedBedChannels(float[] mixed, float[][] pcmCh,
            SpeakerAudioRelay relay, List<SpeakerAudioRelay> targets, int targetCount) {
        int relayChannel = relay.getChannelIndex();
        for (int ch = 0; ch < pcmCh.length; ch++) {
            if (ch == relayChannel || isBedChannelClaimedByAnyRelay(ch)) {
                continue;
            }
            int targetIndex = nearestRelayIndexForBedChannel(ch, targets);
            if (targetIndex < 0 || targets.get(targetIndex) != relay) {
                continue;
            }
            mixInto(mixed, pcmCh[ch], bedMixGain(ch, relayChannel, targetCount));
        }
    }

    private boolean isBedChannelClaimedByAnyRelay(int channelIndex) {
        for (SpeakerAudioRelay relay : relays) {
            if (relay.getChannelIndex() == channelIndex) {
                return true;
            }
        }
        return false;
    }

    private void mixJocObjects(float[] mixed, float[][] objPcmCh, SpeakerAudioRelay relay,
            List<SpeakerAudioRelay> targets, int targetCount) {
        if (objPcmCh == null || objPcmCh.length == 0) {
            return;
        }
        for (int obj = 0; obj < objPcmCh.length; obj++) {
            int targetIndex = nearestRelayIndexForObject(obj, targets);
            if (targetIndex < 0 || targets.get(targetIndex) != relay) {
                continue;
            }
            mixInto(mixed, objPcmCh[obj], objectMixGain(targetCount));
        }
    }

    private int nearestRelayIndexForBedChannel(int bedChannel, List<SpeakerAudioRelay> targets) {
        float[] source = channelPosition(bedChannel);
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < targets.size(); i++) {
            int relayChannel = targets.get(i).getChannelIndex();
            float score = positionDistanceSquared(source, channelPosition(relayChannel));
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private int nearestRelayIndexForObject(int obj, List<SpeakerAudioRelay> targets) {
        float[] source = objectPositions != null && obj >= 0 && obj < objectPositions.length
                ? objectPositions[obj]
                : null;
        if (source == null) {
            return targets.isEmpty() ? -1 : obj % targets.size();
        }
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < targets.size(); i++) {
            int relayChannel = targets.get(i).getChannelIndex();
            float score = positionDistanceSquared(source, channelPosition(relayChannel));
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private float[] channelPosition(int channelIndex) {
        if (bedPositions != null && channelIndex >= 0 && channelIndex < bedPositions.length) {
            return bedPositions[channelIndex];
        }
        return approximateChannelPosition(channelIndex);
    }

    private float bedMixGain(int sourceChannel, int targetChannel, int targetCount) {
        if (sourceChannel == 3) {
            return 0.45f / Math.max(1, targetCount); // LFE 低频无方向性，降低融合量防止轰头
        }
        if (targetChannel == 3) {
            return 0.35f; // 避免把全频内容过量塞进 LFE 音响
        }
        return 0.65f;
    }

    private float objectMixGain(int targetCount) {
        return 0.85f;
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

    private static float positionDistanceSquared(float[] a, float[] b) {
        if (a == null || b == null) {
            return Float.MAX_VALUE;
        }
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        float dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static float[] approximateChannelPosition(int channelIndex) {
        float[][] positions = {
                { -0.50f, 0.0f, 0.86f }, // L
                { 0.50f, 0.0f, 0.86f }, // R
                { 0.00f, 0.0f, 1.00f }, // C
                { 0.00f, 0.0f, 0.00f }, // LFE
                { -1.00f, 0.0f, 0.00f }, // Ls
                { 1.00f, 0.0f, 0.00f }, // Rs
                { -0.50f, 0.0f, -0.86f }, // Lrs/BL
                { 0.50f, 0.0f, -0.86f }, // Rrs/BR
                { -0.50f, 1.0f, 0.86f }, // Ltf
                { 0.50f, 1.0f, 0.86f }, // Rtf
                { -0.50f, 1.0f, -0.86f }, // Ltr
                { 0.50f, 1.0f, -0.86f } // Rtr
        };
        if (channelIndex >= 0 && channelIndex < positions.length) {
            return positions[channelIndex];
        }
        return positions[2];
    }

    private void updateFrameBudget() {
        long now = System.nanoTime();
        if (lastFrameFeedNanos == 0L) {
            lastFrameFeedNanos = now;
            return;
        }
        double elapsedSeconds = Math.max(0.0, (now - lastFrameFeedNanos) / 1_000_000_000.0);
        lastFrameFeedNanos = now;
        frameBudget = Math.min(MAX_FRAMES_PER_TICK, frameBudget + elapsedSeconds * EC3_FRAMES_PER_SECOND);
    }

    private static long ticksToEc3Frames(long ticks) {
        long samples = Math.max(0L, ticks) * 2400L;
        return Math.max(0L, samples / 1536L);
    }

    private static PcmStats conditionPcm(float[][] pcm) {
        float min = 0, max = 0, peak = 0;
        double sq = 0;
        int n = 0;
        for (float[] sf : pcm)
            for (float s : sf) {
                if (s > max)
                    max = s;
                if (s < min)
                    min = s;
                peak = Math.max(peak, Math.abs(s));
                sq += s * s;
                n++;
            }
        float rms = n > 0 ? (float) Math.sqrt(sq / n) : 0;
        boolean g = peak < SILENCE_GATE_PEAK && rms < SILENCE_GATE_RMS;
        for (float[] sf : pcm)
            for (int ch = 0; ch < sf.length; ch++) {
                float s = g ? 0f : sf[ch] * PCM_OUTPUT_GAIN;
                sf[ch] = Math.max(-1f, Math.min(1f, s));
            }
        return new PcmStats(min, max, peak, rms, g);
    }

    private void updateSpatialState(float[] mp, float[] lp, boolean followLocalPlayerFront) {
        if (closed || !initialized)
            return;
        OpenALSpatialAudio sa = spatialAudio;
        if (sa == null || bedPositions == null)
            return;
        float yaw = (float) Math.atan2(mp[0] - lp[0], mp[2] - lp[2]);
        float[] forward = frontSmoother.update(forwardToMachine(mp, lp), followLocalPlayerFront);
        if (!didFirstPositionDiag) {
            didFirstPositionDiag = true;
            int ci = centerChannelIndex(numBedChannels);
            float[] cp = ci < bedPositions.length ? bedPositions[ci] : new float[] { 0, 0, 0 };
            LOGGER.debug(
                    "Dolby spatial map active: world-follow front->machine yaw={}deg centerLocal=({}, {}, {}) listener=({}, {}, {}) objects={}",
                    String.format("%.1f", Math.toDegrees(yaw)), String.format("%.2f", cp[0]),
                    String.format("%.2f", cp[1]), String.format("%.2f", cp[2]), String.format("%.2f", lp[0]),
                    String.format("%.2f", lp[1]), String.format("%.2f", lp[2]), numObjects);
        }
        sa.updatePositions(bedPositions, objectPositions, lp, forward);
        float d = distance(lp, mp), g = spatialGainForDistance(d, userVolume);
        float gv = SpeakerRelayMutePolicy.shouldMuteMain(MUTE_MAIN_WHEN_RELAYS_CONNECTED, relays.size())
                ? 0f
                : g * gameVolume();
        for (int ch = 0; ch < numBedChannels; ch++)
            sa.setBedGain(ch, channelGain(ch, gv));
        for (int o = 0; o < numObjects; o++)
            sa.setObjectGain(o, channelGain(o + numBedChannels, gv));
    }

    /** 根据声道掩码计算单个声道增益：全声道 = gv，被静音 = 0 */
    private float channelGain(int channelIndex, float baseGain) {
        if (channelMask == 0)
            return baseGain; // 未知声道，直接透传
        // 7.1.4 声道索引 → 位映射
        int bit = channelBitForIndex(channelIndex);
        if (bit < 0)
            return baseGain; // 未知声道，直接透传
        return (channelMask & bit) != 0 ? baseGain : 0f;
    }

    /** 将声道索引映射到 7.1.4 位掩码位，-1 表示无映射 */
    private static int channelBitForIndex(int index) {
        // 7.1.4 声道顺序: L, R, C, LFE, Ls, Rs, Lrs, Rrs, Ltf, Rtf, Ltr, Rtr
        return switch (index) {
            case 0 -> 0x001; // L
            case 1 -> 0x002; // R
            case 2 -> 0x004; // C
            case 3 -> 0x008; // LFE
            case 4 -> 0x010; // Ls
            case 5 -> 0x020; // Rs
            case 6 -> 0x040; // Lrs
            case 7 -> 0x080; // Rrs
            case 8 -> 0x100; // Ltf
            case 9 -> 0x200; // Rtf
            case 10 -> 0x400; // Ltr
            case 11 -> 0x800; // Rtr
            default -> -1; // JOC 对象或未知声道
        };
    }

    private static float gameVolume() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.options == null)
            return 1.0f;
        return mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER)
                * mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.RECORDS);
    }

    private void updateObjectOffsets(Eac3AtmosParser.OamdConfig oamd) {
        if (objectPositions == null || numObjects == 0)
            return;
        // 音响强制静态模式：所有 JOC 对象固定在原点（machinePos）
        if (forceStaticJoc) {
            for (int o = 0; o < numObjects; o++) {
                objectPositions[o][0] = 0;
                objectPositions[o][1] = 0;
                objectPositions[o][2] = 0;
            }
            return;
        }
        if (oamd == null || oamd.objectElement == null || oamd.objectElement.firstPositions == null) {
            for (int o = 0; o < numObjects; o++) {
                objectPositions[o][0] = 0;
                objectPositions[o][1] = 0;
                objectPositions[o][2] = 0;
            }
            return;
        }
        for (Eac3AtmosParser.ObjectPosition op : oamd.objectElement.firstPositions)
            if (op.objectIndex >= 0 && op.objectIndex < numObjects) {
                objectPositions[op.objectIndex][0] = op.x * SPATIAL_RADIUS;
                objectPositions[op.objectIndex][1] = op.z * SPATIAL_RADIUS;
                objectPositions[op.objectIndex][2] = op.y * SPATIAL_RADIUS;
            }
    }

    private static float[][] buildJocDownmixBlock(float[][] pcmCh, int pcmChannels, int jocChannels, int offset) {
        int[] map = channelMapForJoc(pcmChannels, jocChannels);
        float[][] block = new float[map.length][256];
        for (int ch = 0; ch < map.length; ch++) {
            int src = map[ch];
            if (src >= 0 && src < pcmCh.length && offset < pcmCh[src].length) {
                System.arraycopy(pcmCh[src], offset, block[ch], 0, Math.min(256, pcmCh[src].length - offset));
            }
        }
        return block;
    }

    private static float[] rmsByObject(float[][] pcm, int count) {
        float[] result = new float[count];
        if (pcm == null)
            return result;
        for (int obj = 0; obj < count && obj < pcm.length; obj++) {
            double sum = 0.0;
            for (float s : pcm[obj])
                sum += s * s;
            result[obj] = pcm[obj].length == 0 ? 0f : (float) Math.sqrt(sum / pcm[obj].length);
        }
        return result;
    }

    private static float[] peakByObject(float[][] pcm, int count) {
        float[] result = new float[count];
        if (pcm == null)
            return result;
        for (int obj = 0; obj < count && obj < pcm.length; obj++) {
            float peak = 0f;
            for (float s : pcm[obj])
                peak = Math.max(peak, Math.abs(s));
            result[obj] = peak;
        }
        return result;
    }

    private static int countActiveObjects(float[] rms) {
        int active = 0;
        for (float value : rms)
            if (value > 1.0e-5f)
                active++;
        return active;
    }

    private static float max(float[] values) {
        float max = 0f;
        for (float value : values)
            max = Math.max(max, value);
        return max;
    }

    private static int[] channelMapForJoc(int pcmChannels, int jocChannels) {
        if (pcmChannels >= 6 && jocChannels == 5) {
            return new int[] { 0, 2, 1, 4, 5 }; // FL, FC, FR, SL, SR；跳过 LFE
        }
        if (pcmChannels >= 8 && jocChannels == 7) {
            return new int[] { 0, 2, 1, 6, 7, 4, 5 }; // FL, FC, FR, SL, SR, BL, BR；跳过 LFE
        }
        int n = Math.min(pcmChannels, jocChannels);
        int[] map = new int[n];
        for (int i = 0; i < n; i++)
            map[i] = i;
        return map;
    }

    private static float[][] computeBedPositions(int nc) {
        float[][] p = new float[nc][3];
        if (nc == 6) {
            float[][] p51 = compute5_1Positions();
            for (int i = 0; i < 6; i++)
                p[i] = p51[i];
            return p;
        }
        if (nc == 8) {
            float[][] p71 = compute7_1Positions();
            for (int i = 0; i < 8; i++)
                p[i] = p71[i];
            return p;
        }
        double step = 2 * Math.PI / nc, start = 0;
        if (nc == 2) {
            step = Math.PI / 3;
            start = -Math.PI / 6;
        }
        for (int ch = 0; ch < nc; ch++) {
            double a = start + ch * step;
            p[ch][0] = (float) (Math.sin(a) * SPATIAL_RADIUS);
            p[ch][1] = 0;
            p[ch][2] = (float) (Math.cos(a) * SPATIAL_RADIUS);
        }
        return p;
    }

    private static float[][] compute5_1Positions() {
        // FFmpeg E-AC-3 5.1 planar float 声道顺序: FL=0, FR=1, FC=2, LFE=3, SL=4, SR=5
        // LFE 放在 listener 中心，超低音无方向性
        float[][] p = new float[6][3];
        double[] a = { -Math.PI / 6, Math.PI / 6, 0, 0, -Math.PI * 11 / 18, Math.PI * 11 / 18 };
        for (int i = 0; i < 6; i++) {
            p[i][0] = (float) (Math.sin(a[i]) * SPATIAL_RADIUS);
            p[i][2] = (float) (Math.cos(a[i]) * SPATIAL_RADIUS);
        }
        p[3][0] = 0;
        p[3][1] = 0;
        p[3][2] = 0;
        return p;
    }

    private static float[][] compute7_1Positions() {
        // JOC 路径使用的 FFmpeg 7.1 planar 声道顺序：FL、FR、FC、LFE、BL、BR、SL、SR。
        float[][] p = new float[8][3];
        double[] a = {
                -Math.PI / 6,
                Math.PI / 6,
                0,
                0,
                -Math.PI * 5 / 6,
                Math.PI * 5 / 6,
                -Math.PI / 2,
                Math.PI / 2
        };
        for (int i = 0; i < p.length; i++) {
            p[i][0] = (float) (Math.sin(a[i]) * SPATIAL_RADIUS);
            p[i][2] = (float) (Math.cos(a[i]) * SPATIAL_RADIUS);
        }
        p[3][0] = 0;
        p[3][1] = 0;
        p[3][2] = 0;
        return p;
    }

    private static String[] bedChannelNames(int channels) {
        return switch (channels) {
            case 6 -> new String[] { "FL", "FR", "FC", "LFE", "SL", "SR" };
            case 8 -> new String[] { "FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR" };
            default -> null;
        };
    }

    private static int centerChannelIndex(int channels) {
        return channels == 6 || channels == 8 ? 2 : 0;
    }

    private static float spatialGainForDistance(float d, float volume) {
        return AudioUtils.spatialGainForDistance(d, volume);
    }

    private static float clampGain(float g) {
        return AudioUtils.clampGain(g);
    }

    private static float distance(float[] a, float[] b) {
        return AudioUtils.distance(a, b);
    }

    private float[] forwardToMachine(float[] mp, float[] lp) {
        forwardToMachine[0] = mp[0] - lp[0];
        forwardToMachine[1] = 0f;
        forwardToMachine[2] = mp[2] - lp[2];
        return forwardToMachine;
    }

    private static String fmtPos(float[] p) {
        return AudioUtils.fmtPos(p);
    }

    private static float softClip(float s) {
        return s / (1f + Math.abs(s));
    }

    private record PcmStats(float min, float max, float peak, float rms, boolean gated) {
    }

    private static class ProcessedFrame {
        final float[][] pcmCh, objPcmCh;
        final Eac3AtmosParser.OamdConfig oamd;
        final int channels, objCount;

        ProcessedFrame(float[][] pc, float[][] oc, Eac3AtmosParser.OamdConfig oa, int ch, int ob) {
            pcmCh = pc;
            objPcmCh = oc;
            oamd = oa;
            channels = ch;
            objCount = ob;
        }
    }

}
