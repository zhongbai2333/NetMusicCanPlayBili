package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTFloat32;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.openal.SOFTSourceSpatialize;

import java.util.ArrayDeque;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/** Dolby Atmos 5.1+对象 的 OpenAL 空间渲染器。支持动态声道数和可变对象数 */
public class OpenALSpatialAudio {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每个 source 预排队 48 个 buffer，约 256ms */
    private static final int NUM_BUFFERS = 48;
    private static final int MAX_PENDING_BLOCKS = 2048;
    /** 每 buffer 256 samples = 1 个 E-AC-3 block */
    private static final int SAMPLES_PER_BUFFER = 256;
    private static final int SAMPLE_RATE = 48000;
    private static final float[] ZERO_LOCAL_POSITION = new float[] { 0f, 0f, 0f };
    private int actualSampleRate = SAMPLE_RATE;
    private static final boolean FORCE_HRTF = Boolean.getBoolean("netmusicbili.dolby.forceHrtf")
            && !Boolean.getBoolean("netmusicbili.dolby.disableHrtf");
    private static final boolean FORCE_HRTF_WITH_CHANNEL = Boolean
            .getBoolean("netmusicbili.dolby.forceHrtfWithChannel");

    /** 是否支持 AL_EXT_FLOAT32（浮点 PCM，无量化损失） */
    private static final ThreadLocal<Long> CAPABILITIES_CONTEXT = ThreadLocal.withInitial(() -> 0L);
    private static volatile boolean hrtfAttempted;

    /**
     * Minecraft OpenAL context/device 缓存。只在首次访问时通过反射获取一次，
     * 之后所有线程直接读缓存，零反射开销。
     */
    private static volatile OpenAlHandles CACHED_HANDLES;
    private static final Object CACHE_LOCK = new Object();

    private int[] bedSources; // 床声道 OpenAL 声源 ID
    private int[] objectSources; // 动态对象 OpenAL 声源 ID
    private int[][] bedBuffers; // [channel][bufferIdx] = AL buffer ID
    private int[][] objBuffers; // [object][bufferIdx] = AL buffer ID
    private ArrayDeque<float[]>[] bedPending;
    private ArrayDeque<float[]>[] objPending;
    private float[] bedGains;
    private float[] objectGains;
    private ByteBuffer uploadScratch;
    private final float[] lastFrontToMachine = new float[] { 0f, 0f, 1f };
    private boolean useFloat32;
    private int monoFormat = AL10.AL_FORMAT_MONO16;
    private int bytesPerSample = 2;
    private int numBeds;
    private int numObjects;
    private boolean initialized;
    /** 设备重置后所有 source/buffer 失效，标记为需重建 */
    private volatile boolean deviceLost;
    /** Primary source 已消费的真实媒体 buffer 数；补静音/启动静音不计入媒体时间线。 */
    private long mediaConsumedBuffers;
    /** Primary source 当前 OpenAL 队列中每个 buffer 是否承载真实媒体 PCM。 */
    private ArrayDeque<Boolean> primaryQueuedMediaFlags;

    public OpenALSpatialAudio() {
    }

    /**
     * 初始化 OpenAL source 和 buffer
     * 
     * @param numBedChannels    床声道数 (2/6/8)
     * @param numDynamicObjects 动态对象数
     */
    public void init(int numBedChannels, int numDynamicObjects) {
        init(numBedChannels, numDynamicObjects, SAMPLE_RATE);
    }

    public void init(int numBedChannels, int numDynamicObjects, int sampleRate) {
        if (!ensureOpenAlContext("init")) {
            return;
        }
        cleanup();
        detectAudioFormat();
        ensureHrtfEnabled();
        this.actualSampleRate = sampleRate;
        this.numBeds = numBedChannels;
        this.numObjects = numDynamicObjects;
        this.deviceLost = false;
        this.mediaConsumedBuffers = 0L;
        this.primaryQueuedMediaFlags = new ArrayDeque<>(NUM_BUFFERS * 2);
        this.uploadScratch = BufferUtils.createByteBuffer(SAMPLES_PER_BUFFER * bytesPerSample)
                .order(ByteOrder.LITTLE_ENDIAN);

        // 分配床声道声源
        bedSources = new int[numBeds];
        bedBuffers = new int[numBeds][NUM_BUFFERS];
        bedPending = newPendingQueues(numBeds);
        bedGains = filledGains(numBeds);
        if (!initSourceGroup("bed", numBeds, bedSources, bedBuffers))
            return;

        // 分配对象声源
        objectSources = new int[numObjects];
        objBuffers = new int[numObjects][NUM_BUFFERS];
        objPending = newPendingQueues(numObjects);
        objectGains = filledGains(numObjects);
        if (!initSourceGroup("object", numObjects, objectSources, objBuffers))
            return;

        for (int b = 0; b < NUM_BUFFERS; b++) {
            primaryQueuedMediaFlags.offerLast(Boolean.FALSE);
        }

        // 启动所有声源播放
        for (int ch = 0; ch < numBeds; ch++) {
            AL10.alSourcePlay(bedSources[ch]);
        }
        for (int obj = 0; obj < numObjects; obj++) {
            AL10.alSourcePlay(objectSources[obj]);
        }

        initialized = true;
        LOGGER.debug(
                "OpenAL 空间声初始化摘要: beds={} objects={} format={} sampleRate={}Hz sourceMode=world-follow spatialize=force hrtf={} preloadBuffers={} preload={}ms",
                numBeds, numObjects, useFloat32 ? "float32" : "int16", actualSampleRate,
                FORCE_HRTF ? "force" : "vanilla", NUM_BUFFERS,
                Math.round(NUM_BUFFERS * SAMPLES_PER_BUFFER * 1000.0 / actualSampleRate));
    }

    /** 送入一帧内单个 256-sample block 的床声道 PCM。每帧调用 6 次（6 个 block） */
    public void updateBedBlock(float[][] pcmBlock) {
        if (!initialized)
            return;
        for (int ch = 0; ch < Math.min(numBeds, pcmBlock.length); ch++) {
            enqueuePending(bedPending[ch], pcmBlock[ch]);
        }
    }

    /** 从完整帧 [channel][1536] PCM 中取 offset 处的 256-sample block */
    public void updateBedFrameBlock(float[][] pcmByChannel, int offset) {
        if (!initialized)
            return;
        for (int ch = 0; ch < Math.min(numBeds, pcmByChannel.length); ch++) {
            enqueuePending(bedPending[ch], pcmByChannel[ch], offset);
        }
    }

    /** 送入一个 256-sample block 的对象 PCM */
    public void updateObjectBlock(float[][] objBlock) {
        if (!initialized)
            return;
        for (int obj = 0; obj < numObjects; obj++) {
            float[] pcm = (objBlock != null && obj < objBlock.length) ? objBlock[obj] : null;
            enqueuePending(objPending[obj], pcm);
        }
    }

    /** 从完整帧 [object][1536] PCM 中取 offset 处的 256-sample block */
    public void updateObjectFrameBlock(float[][] objByChannel, int offset) {
        if (!initialized)
            return;
        for (int obj = 0; obj < numObjects; obj++) {
            float[] pcm = (objByChannel != null && obj < objByChannel.length) ? objByChannel[obj] : null;
            enqueuePending(objPending[obj], pcm, offset);
        }
    }

    /**
     * 将排队的 PCM block 推入 OpenAL buffer
     * 在整帧 6 个 block 都 enqueue 后调用
     */
    public void pumpQueuedAudio() {
        if (!initialized || deviceLost)
            return;
        if (!ensureOpenAlContext("pumpQueuedAudio"))
            return;
        for (int ch = 0; ch < numBeds; ch++) {
            pumpSource(bedSources[ch], bedPending[ch], 1.0f);
        }
        for (int obj = 0; obj < numObjects; obj++) {
            pumpSource(objectSources[obj], objPending[obj], 1.0f);
        }
    }

    /** 更新所有声源的 3D 位置及 listener */
    public void updatePositions(float[][] bedPositions, float[][] objectPositions,
            float[] listenerPos, float[] listenerForward) {
        if (!initialized || listenerPos == null || listenerPos.length < 3)
            return;
        if (!ensureOpenAlContext("updatePositions"))
            return;

        float[] front = frontToMachine(listenerForward);
        for (int ch = 0; ch < numBeds; ch++) {
            float[] pos = (bedPositions != null && ch < bedPositions.length)
                    ? bedPositions[ch]
                    : ZERO_LOCAL_POSITION;
            updateWorldSourcePosition(bedSources[ch], listenerPos, front, pos);
        }

        for (int obj = 0; obj < numObjects; obj++) {
            float[] pos = (objectPositions != null && obj < objectPositions.length)
                    ? objectPositions[obj]
                    : ZERO_LOCAL_POSITION;
            updateWorldSourcePosition(objectSources[obj], listenerPos, front, pos);
        }
    }

    /** 设置指定 bed 声道的增益 */
    public void setBedGain(int channel, float gain) {
        if (channel < numBeds) {
            if (!ensureOpenAlContext("setBedGain"))
                return;
            float clamped = clampGain(gain);
            if (bedGains != null && channel < bedGains.length)
                bedGains[channel] = clamped;
            AL10.alSourcef(bedSources[channel], AL10.AL_GAIN, clamped);
        }
    }

    /** 设置指定对象的增益 */
    public void setObjectGain(int obj, float gain) {
        if (obj < numObjects) {
            if (!ensureOpenAlContext("setObjectGain"))
                return;
            float clamped = clampGain(gain);
            if (objectGains != null && obj < objectGains.length)
                objectGains[obj] = clamped;
            AL10.alSourcef(objectSources[obj], AL10.AL_GAIN, clamped);
        }
    }

    public int getNumBeds() {
        return numBeds;
    }

    public int getNumObjects() {
        return numObjects;
    }

    public long getConsumedSamples() {
        long baseSamples = mediaConsumedBuffers * (long) SAMPLES_PER_BUFFER;
        if (!initialized || deviceLost || !ensureOpenAlContext("getConsumedSamples")) {
            return baseSamples;
        }
        int source = primarySource();
        if (source == 0) {
            return baseSamples;
        }
        int byteOffset;
        try {
            byteOffset = AL10.alGetSourcei(source, AL11.AL_BYTE_OFFSET);
        } catch (Throwable ignored) {
            return baseSamples;
        }
        if (checkDeviceLost("getConsumedSamples:alGetSourcei")) {
            return baseSamples;
        }
        if (primaryQueuedMediaFlags == null || !Boolean.TRUE.equals(primaryQueuedMediaFlags.peekFirst())) {
            return baseSamples;
        }
        long sampleOffset = Math.max(0L, byteOffset / Math.max(1, bytesPerSample));
        return baseSamples + Math.min(SAMPLES_PER_BUFFER - 1L, sampleOffset);
    }

    /**
     * 当前 primary source 中尚未播完的非媒体预滚/启动静音样本数。
     *
     * <p>
     * OpenAL source 初始化时会先排入 {@link #NUM_BUFFERS} 个静音 buffer 来让 source 立即播放，
     * 真正的媒体 PCM 只能在这些 buffer 被处理后逐步替换进去。媒体消费计数故意不把这些静音算进媒体时间，
     * 但对视频/歌词同步来说，它们仍然是实际可听声音前面的输出延迟。seek 后这段延迟通常约 256ms，
     * 需要从对外暴露的“可听媒体时间”里扣除，避免视频先跑。
     * </p>
     */
    public long getOutputDelaySamples() {
        if (!initialized || deviceLost || !ensureOpenAlContext("getOutputDelaySamples")) {
            return 0L;
        }
        int source = primarySource();
        if (source == 0 || primaryQueuedMediaFlags == null || primaryQueuedMediaFlags.isEmpty()
                || Boolean.TRUE.equals(primaryQueuedMediaFlags.peekFirst())) {
            return 0L;
        }
        int byteOffset;
        try {
            byteOffset = AL10.alGetSourcei(source, AL11.AL_BYTE_OFFSET);
        } catch (Throwable ignored) {
            return 0L;
        }
        if (checkDeviceLost("getOutputDelaySamples:alGetSourcei")) {
            return 0L;
        }
        long sampleOffset = Math.max(0L, byteOffset / Math.max(1, bytesPerSample));
        long delayBuffers = 0L;
        for (Boolean media : primaryQueuedMediaFlags) {
            if (Boolean.TRUE.equals(media)) {
                break;
            }
            delayBuffers++;
        }
        if (delayBuffers <= 0L) {
            return 0L;
        }
        long delaySamples = delayBuffers * (long) SAMPLES_PER_BUFFER;
        return Math.max(0L, delaySamples - Math.min(SAMPLES_PER_BUFFER - 1L, sampleOffset));
    }

    /**
     * 清空所有已排入 OpenAL 但尚未真正播放的媒体 buffer，并重新排入静音预滚
     * 
     * @return flush 后 primary source 已真实消费的媒体 sample 数
     */
    public long flushQueuedAudio() {
        long consumedSamples = getConsumedSamples();
        if (!initialized || deviceLost || !ensureOpenAlContext("flushQueuedAudio")) {
            return consumedSamples;
        }
        clearPendingQueues(bedPending);
        clearPendingQueues(objPending);
        flushSourceGroup(bedSources, bedBuffers);
        flushSourceGroup(objectSources, objBuffers);
        if (primaryQueuedMediaFlags != null) {
            primaryQueuedMediaFlags.clear();
            for (int b = 0; b < NUM_BUFFERS; b++) {
                primaryQueuedMediaFlags.offerLast(Boolean.FALSE);
            }
        }
        return consumedSamples;
    }

    /**
     * 立即停止所有 OpenAL source 并丢弃待播放队列，用于同一唱片机切换到新播放 session 时
     * 先把旧音频从实际输出端硬切掉，再异步释放 native 资源。
     */
    public void hardStopOutput() {
        if (!initialized || deviceLost || !ensureOpenAlContext("hardStopOutput")) {
            return;
        }
        clearPendingQueues(bedPending);
        clearPendingQueues(objPending);
        stopAndClearSourceGroup(bedSources);
        stopAndClearSourceGroup(objectSources);
        if (primaryQueuedMediaFlags != null) {
            primaryQueuedMediaFlags.clear();
        }
        mediaConsumedBuffers = 0L;
    }

    public void cleanup() {
        initialized = false;
        int[] bedSourcesToDelete = bedSources;
        int[] objectSourcesToDelete = objectSources;
        int[][] bedBuffersToDelete = bedBuffers;
        int[][] objBuffersToDelete = objBuffers;
        clearLocalReferences();
        Thread cleanupThread = new Thread(
                () -> safeCleanupNative(bedSourcesToDelete, objectSourcesToDelete, bedBuffersToDelete,
                        objBuffersToDelete),
                "OpenALSpatialCleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    private void safeCleanupNative(int[] bedSourcesToDelete, int[] objectSourcesToDelete, int[][] bedBuffersToDelete,
            int[][] objBuffersToDelete) {
        if (!ensureOpenAlContext("cleanup")) {
            return;
        }
        if (bedSourcesToDelete != null) {
            for (int src : bedSourcesToDelete) {
                try {
                    stopAndDelete(src);
                } catch (Throwable ignored) {
                }
            }
        }
        if (objectSourcesToDelete != null) {
            for (int src : objectSourcesToDelete) {
                try {
                    stopAndDelete(src);
                } catch (Throwable ignored) {
                }
            }
        }
        if (bedBuffersToDelete != null) {
            for (int[] chBufs : bedBuffersToDelete) {
                for (int buf : chBufs) {
                    try {
                        AL10.alDeleteBuffers(buf);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (objBuffersToDelete != null) {
            for (int[] objBufs : objBuffersToDelete) {
                for (int buf : objBufs) {
                    try {
                        AL10.alDeleteBuffers(buf);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void detectAudioFormat() {
        boolean hasFloat = AL10.alIsExtensionPresent("AL_EXT_FLOAT32");
        useFloat32 = hasFloat;
        monoFormat = hasFloat ? EXTFloat32.AL_FORMAT_MONO_FLOAT32 : AL10.AL_FORMAT_MONO16;
        bytesPerSample = hasFloat ? 4 : 2;
    }

    private void clearLocalReferences() {
        bedSources = null;
        objectSources = null;
        bedBuffers = null;
        objBuffers = null;
        bedPending = null;
        objPending = null;
        bedGains = null;
        objectGains = null;
        uploadScratch = null;
        deviceLost = false;
        primaryQueuedMediaFlags = null;
        mediaConsumedBuffers = 0L;
    }

    private int primarySource() {
        if (bedSources != null && bedSources.length > 0) {
            return bedSources[0];
        }
        if (objectSources != null && objectSources.length > 0) {
            return objectSources[0];
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<float[]>[] newPendingQueues(int count) {
        ArrayDeque<float[]>[] queues = new ArrayDeque[count];
        for (int i = 0; i < count; i++) {
            queues[i] = new ArrayDeque<>();
        }
        return queues;
    }

    private static float[] filledGains(int count) {
        float[] gains = new float[count];
        for (int i = 0; i < count; i++)
            gains[i] = 1.0f;
        return gains;
    }

    /** 初始化一组 source（bed 或 object），失败时调用 cleanup() 并返回 false */
    private boolean initSourceGroup(String label, int count, int[] sources, int[][] buffers) {
        for (int i = 0; i < count; i++) {
            sources[i] = genSource(label, i);
            if (sources[i] == 0) {
                cleanup();
                return false;
            }
            for (int b = 0; b < NUM_BUFFERS; b++) {
                buffers[i][b] = genBuffer(label, i, b);
                if (buffers[i][b] == 0) {
                    cleanup();
                    return false;
                }
            }
            ByteBuffer silence = BufferUtils.createByteBuffer(SAMPLES_PER_BUFFER * bytesPerSample)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (int b = 0; b < NUM_BUFFERS; b++) {
                silence.clear();
                AL10.alBufferData(buffers[i][b], monoFormat, silence, actualSampleRate);
                AL10.alSourceQueueBuffers(sources[i], buffers[i][b]);
            }
            AL10.alSourcei(sources[i], AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            forceSourceSpatialize(sources[i]);
            AL10.alSourcef(sources[i], AL10.AL_REFERENCE_DISTANCE, 3.0f);
            AL10.alSourcef(sources[i], AL10.AL_MAX_DISTANCE, 48.0f);
            AL10.alSourcef(sources[i], AL10.AL_ROLLOFF_FACTOR, 0.0f);
        }
        return true;
    }

    private void enqueuePending(ArrayDeque<float[]> queue, float[] pcm) {
        if (queue == null)
            return;
        if (queue.size() >= MAX_PENDING_BLOCKS)
            return;
        float[] copy = new float[SAMPLES_PER_BUFFER];
        if (pcm != null) {
            System.arraycopy(pcm, 0, copy, 0, Math.min(SAMPLES_PER_BUFFER, pcm.length));
        }
        queue.offerLast(copy);
    }

    private void enqueuePending(ArrayDeque<float[]> queue, float[] pcm, int offset) {
        if (queue == null)
            return;
        // 满了不丢旧块（丢旧块会导致音频跳跃+空洞），直接跳过
        if (queue.size() >= MAX_PENDING_BLOCKS)
            return;
        float[] copy = new float[SAMPLES_PER_BUFFER];
        if (pcm != null && offset < pcm.length) {
            System.arraycopy(pcm, offset, copy, 0, Math.min(SAMPLES_PER_BUFFER, pcm.length - offset));
        }
        queue.offerLast(copy);
    }

    private static void clearPendingQueues(ArrayDeque<float[]>[] queues) {
        if (queues == null) {
            return;
        }
        for (ArrayDeque<float[]> queue : queues) {
            if (queue != null) {
                queue.clear();
            }
        }
    }

    private void flushSourceGroup(int[] sources, int[][] buffers) {
        if (sources == null || buffers == null) {
            return;
        }
        for (int i = 0; i < Math.min(sources.length, buffers.length); i++) {
            int source = sources[i];
            if (source == 0) {
                continue;
            }
            try {
                AL10.alSourceStop(source);
                int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
                while (queued-- > 0) {
                    AL10.alSourceUnqueueBuffers(source);
                    if (checkDeviceLost("flushQueuedAudio:alSourceUnqueueBuffers")) {
                        return;
                    }
                }
                for (int buffer : buffers[i]) {
                    fillBuffer(buffer, null, 1.0f);
                    AL10.alSourceQueueBuffers(source, buffer);
                    if (checkDeviceLost("flushQueuedAudio:alSourceQueueBuffers")) {
                        return;
                    }
                }
                AL10.alSourcePlay(source);
            } catch (Throwable error) {
                if (checkDeviceLost("flushQueuedAudio")) {
                    return;
                }
                LOGGER.debug("OpenAL source flush failed: {}", error.toString());
            }
        }
    }

    private void stopAndClearSourceGroup(int[] sources) {
        if (sources == null) {
            return;
        }
        for (int source : sources) {
            if (source == 0) {
                continue;
            }
            try {
                AL10.alSourcef(source, AL10.AL_GAIN, 0.0f);
                AL10.alSourceStop(source);
                int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
                while (queued-- > 0) {
                    AL10.alSourceUnqueueBuffers(source);
                    if (checkDeviceLost("hardStopOutput:alSourceUnqueueBuffers")) {
                        return;
                    }
                }
            } catch (Throwable error) {
                if (checkDeviceLost("hardStopOutput")) {
                    return;
                }
                LOGGER.debug("OpenAL source hard-stop failed: {}", error.toString());
            }
        }
    }

    private void pumpSource(int sourceId, ArrayDeque<float[]> pending, float gain) {
        int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
        if (checkDeviceLost("pumpSource:alGetSourcei")) {
            return;
        }

        while (processed-- > 0) {
            int buf = AL10.alSourceUnqueueBuffers(sourceId);
            if (buf == 0 && checkDeviceLost("pumpSource:alSourceUnqueueBuffers")) {
                return;
            }
            float[] pcm = pending != null ? pending.pollFirst() : null;
            if (sourceId == primarySource()) {
                boolean consumedMedia = primaryQueuedMediaFlags != null
                        && Boolean.TRUE.equals(primaryQueuedMediaFlags.pollFirst());
                if (consumedMedia) {
                    mediaConsumedBuffers++;
                }
            }
            fillBuffer(buf, pcm, gain);
            AL10.alSourceQueueBuffers(sourceId, buf);
            if (checkDeviceLost("pumpSource:alSourceQueueBuffers")) {
                return;
            }
            if (sourceId == primarySource() && primaryQueuedMediaFlags != null) {
                primaryQueuedMediaFlags.offerLast(pcm != null);
            }
        }
        if (AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING
                && AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED) > 0) {
            AL10.alSourcePlay(sourceId);
        }
    }

    /**
     * 检测 OpenAL 设备是否已被重置（其他模组调用 alcResetDeviceSOFT 等）
     * 设备重置后所有已分配的 source/buffer 句柄失效，继续使用会持续报 AL_INVALID_NAME
     * 一旦检测到，标记 deviceLost，上层应重建整个 OpenAL 管线
     */
    private boolean checkDeviceLost(String context) {
        int err = AL10.alGetError();
        if (err == AL10.AL_NO_ERROR) {
            return false;
        }
        if (err == AL10.AL_INVALID_NAME) {
            if (!deviceLost) {
                deviceLost = true;
                CACHED_HANDLES = null; // 设备失效后缓存作废，下次调用时重建
                LOGGER.warn("OpenAL device lost detected ({}): source/buffer handles invalidated. "
                        + "This can happen when another mod resets the OpenAL device (e.g. Sound Physics). "
                        + "Spatial audio will be reinitialized on next tick.",
                        context);
            }
            return true;
        }
        // 其他错误码（如 AL_INVALID_OPERATION）记录但不停止
        LOGGER.debug("OpenAL error in {}: 0x{}", context, Integer.toHexString(err).toUpperCase());
        return false;
    }

    /** 查询设备是否已丢失，供上层（DolbyAudioHandler/StereoOpenALHandler）重建 */
    public boolean isDeviceLost() {
        return deviceLost;
    }

    private void fillBuffer(int bufferId, float[] pcm, float gain) {
        int len = SAMPLES_PER_BUFFER;
        ByteBuffer buf = uploadScratch;
        if (buf == null || buf.capacity() < len * bytesPerSample) {
            buf = BufferUtils.createByteBuffer(len * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN);
            uploadScratch = buf;
        }
        buf.clear();
        if (useFloat32) {
            for (int i = 0; i < len; i++) {
                float sample = (pcm != null && i < pcm.length) ? pcm[i] * gain : 0f;
                buf.putFloat(Math.max(-1.0f, Math.min(1.0f, sample)));
            }
        } else {
            for (int i = 0; i < len; i++) {
                float sample = (pcm != null && i < pcm.length) ? pcm[i] * gain : 0f;
                int intSample = Math.round(sample * 32767.0f);
                intSample = Math.max(-32768, Math.min(32767, intSample));
                buf.putShort((short) intSample);
            }
        }
        buf.flip();
        AL10.alBufferData(bufferId, monoFormat, buf, actualSampleRate);
    }

    private float[] frontToMachine(float[] listenerForward) {
        if (listenerForward != null && listenerForward.length >= 3) {
            float fx = listenerForward[0];
            float fz = listenerForward[2];
            float len = (float) Math.sqrt(fx * fx + fz * fz);
            if (len > 0.15f) {
                lastFrontToMachine[0] = fx / len;
                lastFrontToMachine[1] = 0f;
                lastFrontToMachine[2] = fz / len;
            }
        }
        return lastFrontToMachine;
    }

    private static float clampGain(float gain) {
        return Math.max(0.0f, Math.min(1.0f, gain));
    }

    private static void updateWorldSourcePosition(int sourceId, float[] listenerPos, float[] front, float[] local) {
        float lx = local != null && local.length > 0 ? local[0] : 0f;
        float ly = local != null && local.length > 1 ? local[1] : 0f;
        float lz = local != null && local.length > 2 ? local[2] : 0f;

        float rightX = -front[2];
        float rightZ = front[0];
        float worldX = listenerPos[0] + rightX * lx + front[0] * lz;
        float worldY = listenerPos[1] + ly;
        float worldZ = listenerPos[2] + rightZ * lx + front[2] * lz;
        AL10.alSource3f(sourceId, AL10.AL_POSITION, worldX, worldY, worldZ);
    }

    private static void forceSourceSpatialize(int sourceId) {
        if (AL10.alIsExtensionPresent("AL_SOFT_source_spatialize")) {
            AL10.alSourcei(sourceId, SOFTSourceSpatialize.AL_SOURCE_SPATIALIZE_SOFT, AL10.AL_TRUE);
        }
    }

    private static boolean ensureOpenAlContext(String operation) {
        long context = ALC10.alcGetCurrentContext();
        long device = 0L;
        if (context == 0L) {
            OpenAlHandles handles = minecraftOpenAlHandles();
            context = handles.context();
            device = handles.device();
            if (context == 0L) {
                LOGGER.debug("OpenAL spatial {} skipped: no current Minecraft OpenAL context", operation);
                return false;
            }
            if (!ALC10.alcMakeContextCurrent(context)) {
                LOGGER.warn("OpenAL spatial {} skipped: failed to make Minecraft OpenAL context current", operation);
                return false;
            }
        }

        if (device == 0L) {
            device = ALC10.alcGetContextsDevice(context);
        }
        if (device == 0L) {
            LOGGER.warn("OpenAL spatial {} skipped: no OpenAL device for context", operation);
            return false;
        }

        if (CAPABILITIES_CONTEXT.get() != context) {
            try {
                AL.createCapabilities(ALC.createCapabilities(device));
                CAPABILITIES_CONTEXT.set(context);
            } catch (IllegalStateException e) {
                CACHED_HANDLES = null;
                LOGGER.warn("OpenAL spatial {} skipped: context lost between check and capability init", operation);
                return false;
            }
        }
        return true;
    }

    private static OpenAlHandles minecraftOpenAlHandles() {
        OpenAlHandles cached = CACHED_HANDLES;
        if (cached != null) {
            return cached;
        }
        synchronized (CACHE_LOCK) {
            cached = CACHED_HANDLES;
            if (cached != null) {
                return cached;
            }
            cached = resolveMinecraftOpenAlHandles();
            CACHED_HANDLES = cached;
            return cached;
        }
    }

    /** 反射解析失败时只警告一次，避免刷屏 */
    private static volatile boolean reflectionWarningLogged;

    private static OpenAlHandles resolveMinecraftOpenAlHandles() {
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
            if (minecraft == null) {
                return OpenAlHandles.EMPTY;
            }
            Object soundManager = minecraftClass.getMethod("getSoundManager").invoke(minecraft);
            if (soundManager == null) {
                return OpenAlHandles.EMPTY;
            }
            Object soundEngine = readField(soundManager, "soundEngine");
            if (soundEngine == null) {
                return OpenAlHandles.EMPTY;
            }
            Object loaded = readField(soundEngine, "loaded");
            if (loaded instanceof Boolean isLoaded && !isLoaded) {
                return OpenAlHandles.EMPTY;
            }
            Object library = readField(soundEngine, "library");
            if (library == null) {
                return OpenAlHandles.EMPTY;
            }
            long context = readLongField(library, "context");
            long device = readLongField(library, "currentDevice");
            if (context == 0L) {
                return OpenAlHandles.EMPTY;
            }
            LOGGER.debug("Cached Minecraft OpenAL handles: context=0x{} device=0x{}",
                    Long.toHexString(context), Long.toHexString(device));
            return new OpenAlHandles(context, device);
        } catch (Throwable t) {
            if (!reflectionWarningLogged) {
                reflectionWarningLogged = true;
                LOGGER.warn(
                        "[NetMusicCanPlayBili] Dolby 空间音频不可用：无法通过反射获取 Minecraft SoundEngine 的 OpenAL 句柄。"
                                + "这通常是因为当前 Minecraft/NeoForge 版本与模组不兼容（SoundEngine 内部字段名已变更）。"
                                + "音频将自动降级为 FLAC/AAC 立体声。"
                                + "具体异常: {}",
                        t.toString());
            } else {
                LOGGER.debug("OpenAL handle resolution retry failed: {}", t.toString());
            }
            return OpenAlHandles.EMPTY;
        }
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static long readLongField(Object target, String name) throws ReflectiveOperationException {
        Object value = readField(target, name);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static synchronized void ensureHrtfEnabled() {
        if (hrtfAttempted || !FORCE_HRTF)
            return;
        if (isChannelLoaded() && !FORCE_HRTF_WITH_CHANNEL) {
            LOGGER.warn(
                    "OpenAL HRTF: Channel mod detected; skip alcResetDeviceSOFT to avoid disrupting voice EFX sources. Set -Dnetmusicbili.dolby.forceHrtfWithChannel=true to override.");
            hrtfAttempted = true;
            return;
        }
        hrtfAttempted = true;
        long context = ALC10.alcGetCurrentContext();
        if (context == 0L) {
            LOGGER.warn("OpenAL HRTF: 当前没有 OpenAL context，跳过强制启用");
            return;
        }
        long device = ALC10.alcGetContextsDevice(context);
        if (device == 0L) {
            LOGGER.warn("OpenAL HRTF: 无法获取 OpenAL device，跳过强制启用");
            return;
        }
        if (!ALC10.alcIsExtensionPresent(device, "ALC_SOFT_HRTF")) {
            LOGGER.warn("OpenAL HRTF: 当前设备不支持 ALC_SOFT_HRTF");
            return;
        }
        IntBuffer attrs = BufferUtils.createIntBuffer(3);
        attrs.put(SOFTHRTF.ALC_HRTF_SOFT).put(AL10.AL_TRUE).put(0).flip();
        boolean ok;
        try {
            ok = SOFTHRTF.alcResetDeviceSOFT(device, attrs);
        } catch (Throwable t) {
            LOGGER.warn("OpenAL HRTF: alcResetDeviceSOFT 调用失败", t);
            return;
        }
        int status = ALC10.alcGetInteger(device, SOFTHRTF.ALC_HRTF_STATUS_SOFT);
        LOGGER.debug("OpenAL HRTF: reset={}, status={}", ok, hrtfStatusName(status));
    }

    private static String hrtfStatusName(int status) {
        return switch (status) {
            case SOFTHRTF.ALC_HRTF_DISABLED_SOFT -> "disabled";
            case SOFTHRTF.ALC_HRTF_ENABLED_SOFT -> "enabled";
            case SOFTHRTF.ALC_HRTF_DENIED_SOFT -> "denied";
            case SOFTHRTF.ALC_HRTF_REQUIRED_SOFT -> "required";
            case SOFTHRTF.ALC_HRTF_HEADPHONES_DETECTED_SOFT -> "headphones-detected";
            case SOFTHRTF.ALC_HRTF_UNSUPPORTED_FORMAT_SOFT -> "unsupported-format";
            default -> "unknown(" + status + ")";
        };
    }

    private static boolean isChannelLoaded() {
        try {
            return ModList.get().isLoaded("channel");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void stopAndDelete(int sourceId) {
        AL10.alSourceStop(sourceId);
        // 取消所有缓冲区的排队
        int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
        for (int i = 0; i < queued; i++) {
            AL10.alSourceUnqueueBuffers(sourceId);
        }
        AL10.alDeleteSources(sourceId);
    }

    private record OpenAlHandles(long context, long device) {
        private static final OpenAlHandles EMPTY = new OpenAlHandles(0L, 0L);
    }

    // ── Source / Buffer 分配（含失败检测） ──

    /**
     * 生成一个 OpenAL source。失败时返回 0，上层应立即 {@link #cleanup()}
     */
    private static int genSource(String type, int index) {
        int source = AL10.alGenSources();
        int err = AL10.alGetError();
        if (err != AL10.AL_NO_ERROR) {
            LOGGER.error("OpenAL genSource({}[{}]) 失败: AL error 0x{}", type, index,
                    Integer.toHexString(err).toUpperCase());
            return 0;
        }
        if (source == 0) {
            LOGGER.error("OpenAL genSource({}[{}]) 返回 0: 设备 source 已耗尽或未初始化", type, index);
            return 0;
        }
        return source;
    }

    /**
     * 生成一个 OpenAL buffer。失败时返回 0，上层应立即 {@link #cleanup()}
     */
    private static int genBuffer(String type, int channelOrObj, int bufferIdx) {
        int buffer = AL10.alGenBuffers();
        int err = AL10.alGetError();
        if (err != AL10.AL_NO_ERROR) {
            LOGGER.error("OpenAL genBuffer({}[{}][{}]) 失败: AL error 0x{}", type, channelOrObj, bufferIdx,
                    Integer.toHexString(err).toUpperCase());
            return 0;
        }
        if (buffer == 0) {
            LOGGER.error("OpenAL genBuffer({}[{}][{}]) 返回 0: 设备 buffer 资源已耗尽或未初始化", type, channelOrObj,
                    bufferIdx);
            return 0;
        }
        return buffer;
    }
}
