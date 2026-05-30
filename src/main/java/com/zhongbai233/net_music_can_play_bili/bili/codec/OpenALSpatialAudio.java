package com.zhongbai233.net_music_can_play_bili.bili.codec;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
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
    private int actualSampleRate = SAMPLE_RATE;
    private static final boolean FORCE_HRTF = Boolean.getBoolean("netmusicbili.dolby.forceHrtf")
            && !Boolean.getBoolean("netmusicbili.dolby.disableHrtf");

    /** 是否支持 AL_EXT_FLOAT32（浮点 PCM，无量化损失） */
    private static final boolean USE_FLOAT32;
    private static final int MONO_FORMAT;
    private static final int BYTES_PER_SAMPLE;
    private static volatile boolean hrtfAttempted;

    static {
        boolean hasFloat = AL10.alIsExtensionPresent("AL_EXT_FLOAT32");
        USE_FLOAT32 = hasFloat;
        if (hasFloat) {
            MONO_FORMAT = EXTFloat32.AL_FORMAT_MONO_FLOAT32;
            BYTES_PER_SAMPLE = 4;
        } else {
            MONO_FORMAT = AL10.AL_FORMAT_MONO16;
            BYTES_PER_SAMPLE = 2;
        }
    }

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
    private int numBeds;
    private int numObjects;
    private boolean initialized;

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
        cleanup();
        ensureHrtfEnabled();
        this.actualSampleRate = sampleRate;
        this.numBeds = numBedChannels;
        this.numObjects = numDynamicObjects;
        this.uploadScratch = BufferUtils.createByteBuffer(SAMPLES_PER_BUFFER * BYTES_PER_SAMPLE)
                .order(ByteOrder.LITTLE_ENDIAN);

        // 分配床声道声源
        bedSources = new int[numBeds];
        bedBuffers = new int[numBeds][NUM_BUFFERS];
        bedPending = newPendingQueues(numBeds);
        bedGains = filledGains(numBeds);
        for (int ch = 0; ch < numBeds; ch++) {
            bedSources[ch] = AL10.alGenSources();
            for (int b = 0; b < NUM_BUFFERS; b++) {
                bedBuffers[ch][b] = AL10.alGenBuffers();
            }
            // 排队初始静音缓冲区
            ByteBuffer silence = BufferUtils.createByteBuffer(SAMPLES_PER_BUFFER * BYTES_PER_SAMPLE)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (int b = 0; b < NUM_BUFFERS; b++) {
                silence.clear();
                AL10.alBufferData(bedBuffers[ch][b], MONO_FORMAT,
                        silence, actualSampleRate);
                AL10.alSourceQueueBuffers(bedSources[ch], bedBuffers[ch][b]);
            }
            AL10.alSourcei(bedSources[ch], AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            forceSourceSpatialize(bedSources[ch]);
            AL10.alSourcef(bedSources[ch], AL10.AL_REFERENCE_DISTANCE, 3.0f);
            AL10.alSourcef(bedSources[ch], AL10.AL_MAX_DISTANCE, 48.0f);
            AL10.alSourcef(bedSources[ch], AL10.AL_ROLLOFF_FACTOR, 0.0f);
        }

        // 分配对象声源
        objectSources = new int[numObjects];
        objBuffers = new int[numObjects][NUM_BUFFERS];
        objPending = newPendingQueues(numObjects);
        objectGains = filledGains(numObjects);
        for (int obj = 0; obj < numObjects; obj++) {
            objectSources[obj] = AL10.alGenSources();
            for (int b = 0; b < NUM_BUFFERS; b++) {
                objBuffers[obj][b] = AL10.alGenBuffers();
            }
            ByteBuffer silence = BufferUtils.createByteBuffer(SAMPLES_PER_BUFFER * BYTES_PER_SAMPLE)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (int b = 0; b < NUM_BUFFERS; b++) {
                silence.clear();
                AL10.alBufferData(objBuffers[obj][b], MONO_FORMAT,
                        silence, actualSampleRate);
                AL10.alSourceQueueBuffers(objectSources[obj], objBuffers[obj][b]);
            }
            AL10.alSourcei(objectSources[obj], AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            forceSourceSpatialize(objectSources[obj]);
            AL10.alSourcef(objectSources[obj], AL10.AL_REFERENCE_DISTANCE, 3.0f);
            AL10.alSourcef(objectSources[obj], AL10.AL_MAX_DISTANCE, 48.0f);
            AL10.alSourcef(objectSources[obj], AL10.AL_ROLLOFF_FACTOR, 0.0f);
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
                "OpenAL spatial init: beds={}, objects={}, format={}, sourceRelative=false(world-follow), spatialize=force, hrtf={}, preloadBuffers={} (~{}ms)",
                numBeds, numObjects, USE_FLOAT32 ? "float32" : "int16", FORCE_HRTF ? "force" : "vanilla", NUM_BUFFERS,
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
        if (!initialized)
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

        float[] front = frontToMachine(listenerForward);
        for (int ch = 0; ch < numBeds; ch++) {
            float[] pos = (bedPositions != null && ch < bedPositions.length)
                    ? bedPositions[ch]
                    : new float[] { 0, 0, 0 };
            updateWorldSourcePosition(bedSources[ch], listenerPos, front, pos);
        }

        for (int obj = 0; obj < numObjects; obj++) {
            float[] pos = (objectPositions != null && obj < objectPositions.length)
                    ? objectPositions[obj]
                    : new float[] { 0, 0, 0 };
            updateWorldSourcePosition(objectSources[obj], listenerPos, front, pos);
        }
    }

    /** 设置指定 bed 声道的增益 */
    public void setBedGain(int channel, float gain) {
        if (channel < numBeds) {
            float clamped = clampGain(gain);
            if (bedGains != null && channel < bedGains.length)
                bedGains[channel] = clamped;
            AL10.alSourcef(bedSources[channel], AL10.AL_GAIN, clamped);
        }
    }

    /** 设置指定对象的增益 */
    public void setObjectGain(int obj, float gain) {
        if (obj < numObjects) {
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

    /** 释放所有 OpenAL 资源 */
    public void cleanup() {
        initialized = false;
        if (bedSources != null) {
            for (int src : bedSources) {
                stopAndDelete(src);
            }
        }
        if (objectSources != null) {
            for (int src : objectSources) {
                stopAndDelete(src);
            }
        }
        if (bedBuffers != null) {
            for (int[] chBufs : bedBuffers) {
                for (int buf : chBufs) {
                    AL10.alDeleteBuffers(buf);
                }
            }
        }
        if (objBuffers != null) {
            for (int[] objBufs : objBuffers) {
                for (int buf : objBufs) {
                    AL10.alDeleteBuffers(buf);
                }
            }
        }
        bedSources = null;
        objectSources = null;
        bedBuffers = null;
        objBuffers = null;
        bedPending = null;
        objPending = null;
        bedGains = null;
        objectGains = null;
        uploadScratch = null;
    }

    // ── 内部 ──

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

    private void pumpSource(int sourceId, ArrayDeque<float[]> pending, float gain) {
        int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);

        while (processed-- > 0) {
            int buf = AL10.alSourceUnqueueBuffers(sourceId);
            float[] pcm = pending != null ? pending.pollFirst() : null;
            fillBuffer(buf, pcm, gain);
            AL10.alSourceQueueBuffers(sourceId, buf);
        }
        if (AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING
                && AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED) > 0) {
            AL10.alSourcePlay(sourceId);
        }
    }

    private void fillBuffer(int bufferId, float[] pcm, float gain) {
        int len = SAMPLES_PER_BUFFER;
        ByteBuffer buf = uploadScratch;
        if (buf == null || buf.capacity() < len * BYTES_PER_SAMPLE) {
            buf = BufferUtils.createByteBuffer(len * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN);
            uploadScratch = buf;
        }
        buf.clear();
        if (USE_FLOAT32) {
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
        AL10.alBufferData(bufferId, MONO_FORMAT, buf, actualSampleRate);
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

    private static synchronized void ensureHrtfEnabled() {
        if (hrtfAttempted || !FORCE_HRTF)
            return;
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

    private static void stopAndDelete(int sourceId) {
        AL10.alSourceStop(sourceId);
        // 取消所有缓冲区的排队
        int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
        for (int i = 0; i < queued; i++) {
            AL10.alSourceUnqueueBuffers(sourceId);
        }
        AL10.alDeleteSources(sourceId);
    }
}
