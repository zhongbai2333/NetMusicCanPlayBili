package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.bili.codec.*;

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
    /** 手动反比衰减参考距离：只做一层衰减，避免 OpenAL 和 JOC 对象叠加变小。 */
    private static final float DISTANCE_REFERENCE = 8.0f;
    private static final float SILENCE_GATE_PEAK = 1.0e-4f;
    private static final float SILENCE_GATE_RMS = 2.0e-5f;
    private static final float PCM_OUTPUT_GAIN = 1.0f;
    private static final float JOC_OBJECT_OUTPUT_GAIN = 0.20f;
    private static final boolean ENABLE_JOC_OBJECTS = true;
    private static final int MAX_OBJECT_SOURCES = 32;
    private static final double EC3_FRAMES_PER_SECOND = 48000.0 / 1536.0;
    private static final int MAX_FRAMES_PER_TICK = 8;
    private static final int PREBUFFER_FRAMES = 96;
    private static final int RAW_QUEUE_CAPACITY = 512;
    private static final int PROCESSED_QUEUE_CAPACITY = 512;

    private final BlockingQueue<byte[]> rawQueue = new LinkedBlockingQueue<>(RAW_QUEUE_CAPACITY);
    private final BlockingQueue<ProcessedFrame> processedQueue = new LinkedBlockingQueue<>(PROCESSED_QUEUE_CAPACITY);
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

    private int frameCount;
    private int nullPcmCount;
    private boolean didFirstDiag;
    private boolean didFirstDiagSpatial;
    private boolean didFirstPositionDiag;
    private boolean didJocAudioFailDiag;

    public DolbyAudioHandler() {
        // 预加载 FFmpeg native 库（~1s），在 mdat 数据到达前完成，
        // 避免 worker 线程被初始化阻塞导致 rawQueue 溢出丢帧
        Eac3NativeDecoder.preload();
        worker = new Thread(this::workerLoop, "DolbyDecodeWorker");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean enqueueFrame(byte[] ec3Frame) {
        if (ec3Frame == null || ec3Frame.length == 0 || closed)
            return false;
        try {
            while (!closed) {
                if (rawQueue.offer(ec3Frame, 250, TimeUnit.MILLISECONDS)) {
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
        if (closed)
            return;
        if (!playbackStarted) {
            if (processedQueue.size() < PREBUFFER_FRAMES)
                return;
            playbackStarted = true;
            lastFrameFeedNanos = System.nanoTime();
            frameBudget = MAX_FRAMES_PER_TICK;
            LOGGER.debug("Dolby OpenAL 预缓冲完成: {} 帧", processedQueue.size());
        }
        updateFrameBudget();
        int processed = 0;
        ProcessedFrame pf;
        int allowedFrames = Math.min((int) frameBudget, MAX_FRAMES_PER_TICK);
        while (processed < allowedFrames && (pf = processedQueue.poll()) != null) {
            feedOpenAL(pf);
            processed++;
        }
        frameBudget = Math.max(0.0, frameBudget - processed);

        if (initialized && spatialAudio != null) {
            spatialAudio.pumpQueuedAudio();
            updateSpatialState(machinePos, listenerPos);
        }
    }

    public int queuedFrames() {
        return rawQueue.size();
    }

    public List<String> describeSources(float[] machinePos, float[] listenerPos) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("Dolby: initialized=%s beds=%d objects=%d JOC音频=%s rawQ=%d procQ=%d frames=%d",
                initialized, numBedChannels, numObjects, BiliConfig.dolbyJocEnabled ? "on" : "off",
                rawQueue.size(), processedQueue.size(), frameCount));
        if (machinePos != null && listenerPos != null) {
            float yaw = (float) Math.atan2(machinePos[0] - listenerPos[0], machinePos[2] - listenerPos[2]);
            float distance = distance(listenerPos, machinePos);
            float gain = gainForDistance(distance);
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

    public void cleanup() {
        closed = true;
        rawQueue.clear();
        processedQueue.clear();
        playbackStarted = false;
        lastFrameFeedNanos = 0L;
        frameBudget = 0.0;
        worker.interrupt();
        if (spatialAudio != null) {
            spatialAudio.cleanup();
            spatialAudio = null;
        }
        initialized = false;
        LOGGER.debug("DolbyAudioHandler closed ({} frames)", frameCount);
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
            if (ENABLE_JOC_OBJECTS && jocResult == null) {
                byte[] jp = Eac3AtmosParser.extractJocPayload(emdf);
                if (jp != null) {
                    try {
                        jocResult = new Eac3JocDecoder().decode(jp, QmfFilterBank.TIMESLOTS);
                    } catch (Exception e) {
                    }
                }
            }
        }
        int rawObjCount = 0;
        if (ENABLE_JOC_OBJECTS && jocResult != null) {
            rawObjCount = (oamd != null && oamd.objectElement != null) ? oamd.objectElement.dynamicObjects
                    : jocResult.config.objectCount;
        }
        int objCount = BiliConfig.dolbyJocEnabled ? Math.max(0, Math.min(rawObjCount, MAX_OBJECT_SOURCES)) : 0;
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
                            Math.min(jocDiag.config.objectCount, MAX_OBJECT_SOURCES),
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
        int chs = pf.channels, objs = Math.max(0, Math.min(pf.objCount, MAX_OBJECT_SOURCES));
        int targetObjects = BiliConfig.dolbyJocEnabled ? MAX_OBJECT_SOURCES : objs;
        if (!initialized || numBedChannels != chs || targetObjects > numObjects) {
            float[][] oldObjectPositions = objectPositions;
            int newObjectCapacity = initialized && numBedChannels == chs
                    ? Math.max(numObjects, targetObjects)
                    : targetObjects;
            OpenALSpatialAudio oldSA = spatialAudio;
            if (oldSA != null)
                oldSA.cleanup();
            spatialAudio = new OpenALSpatialAudio();
            spatialAudio.init(chs, newObjectCapacity);
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
        return new PcmStats(min, max, rms, g);
    }

    private void updateSpatialState(float[] mp, float[] lp) {
        if (closed || !initialized)
            return;
        OpenALSpatialAudio sa = spatialAudio;
        if (sa == null || bedPositions == null)
            return;
        float yaw = (float) Math.atan2(mp[0] - lp[0], mp[2] - lp[2]);
        float[] forward = forwardToMachine(mp, lp, yaw);
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
        float d = distance(lp, mp), g = gainForDistance(d);
        for (int ch = 0; ch < numBedChannels; ch++)
            sa.setBedGain(ch, g);
        for (int o = 0; o < numObjects; o++)
            sa.setObjectGain(o, g);
    }

    private void updateObjectOffsets(Eac3AtmosParser.OamdConfig oamd) {
        if (objectPositions == null || numObjects == 0)
            return;
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
        // FFmpeg 7.1 planar order used by the JOC path: FL, FR, FC, LFE, BL, BR, SL, SR.
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

    private static float gainForDistance(float d) {
        // 最小有效距离 = 音响半径，避免进入音响圈内部时增益异常
        float clamped = Math.max(SPATIAL_RADIUS, d);
        return clampGain(DISTANCE_REFERENCE / (DISTANCE_REFERENCE + clamped));
    }

    private static float clampGain(float g) {
        return g < 0 ? 0 : Math.min(g, 1);
    }

    private static float distance(float[] a, float[] b) {
        float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float[] forwardToMachine(float[] mp, float[] lp, float yaw) {
        return new float[] { mp[0] - lp[0], 0f, mp[2] - lp[2] };
    }

    private static String fmtPos(float[] p) {
        return p == null ? "(null)" : String.format("(%.2f, %.2f, %.2f)", p[0], p[1], p[2]);
    }

    private static float softClip(float s) {
        return s / (1f + Math.abs(s));
    }

    private record PcmStats(float min, float max, float rms, boolean gated) {
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
