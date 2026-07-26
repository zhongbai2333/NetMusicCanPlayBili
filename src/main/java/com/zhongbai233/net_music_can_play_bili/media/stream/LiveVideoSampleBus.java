package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直播音视频共用连接的视频样本总线。
 *
 * <p>
 * 写端是直播音频会话（同一条 FLV 连接解出的视频 tag），读端是视频解码器线程。
 * 样本的 pts 已经换算到音频输出时间域：以本连接第一个被送入 OpenAL 的音频 tag 为锚，
 * {@code pts = anchorFedMillis + (dts + cts - anchorFlvMillis)}。视频播放时钟取
 * OpenAL 可听位置，两者天然对齐，即为唇音同步。
 * </p>
 *
 * <p>
 * 直播只保留最近的样本：写端超出容量时从队头丢到下一个关键帧；读端加入（或断线重连后）
 * 从关键帧开始消费。
 * </p>
 */
public final class LiveVideoSampleBus {
    /** {@code openDecoder} 用来识别直播总线的伪 URL 前缀。 */
    public static final String BUS_URL_PREFIX = "ncpb-live-bus:";

    private static final int DEFAULT_CAPACITY = Math.max(32,
            Integer.getInteger("ncpb.bili.live.video.bus_capacity", 256));
    private static final ConcurrentHashMap<String, LiveVideoSampleBus> REGISTRY = new ConcurrentHashMap<>();

    private final String key;
    private final int capacity;
    private final ArrayDeque<VideoSample> samples = new ArrayDeque<>();
    private final Object lock = new Object();

    private volatile byte[] avcConfig;
    private boolean anchorSet;
    private long anchorFlvMillis;
    private long anchorFedMillis;
    private boolean consumerNeedsKeyframe = true;
    private volatile boolean closed;
    private long droppedSamples;

    /** 一个 AVCC 长度前缀格式的 H.264 样本；pts 已换算到音频输出时间域。 */
    public record VideoSample(byte[] data, long ptsNanos, boolean keyframe, byte[] avcConfig) {
    }

    private LiveVideoSampleBus(String key, int capacity) {
        this.key = key;
        this.capacity = Math.max(8, capacity);
    }

    public static String busUrl(String key) {
        return BUS_URL_PREFIX + key;
    }

    public static boolean isBusUrl(String url) {
        return url != null && url.startsWith(BUS_URL_PREFIX);
    }

    public static String keyFromBusUrl(String url) {
        return isBusUrl(url) ? url.substring(BUS_URL_PREFIX.length()) : "";
    }

    /** 写端注册；同 key 的旧总线会被关闭替换。 */
    public static LiveVideoSampleBus register(String key) {
        LiveVideoSampleBus bus = new LiveVideoSampleBus(key, DEFAULT_CAPACITY);
        LiveVideoSampleBus previous = REGISTRY.put(key, bus);
        if (previous != null) {
            previous.close();
        }
        return bus;
    }

    public static LiveVideoSampleBus find(String key) {
        return key == null || key.isBlank() ? null : REGISTRY.get(key);
    }

    public String key() {
        return key;
    }

    public boolean isClosed() {
        return closed;
    }

    public long droppedSamples() {
        synchronized (lock) {
            return droppedSamples;
        }
    }

    /** 新的 FLV 连接开始：时间戳基准可能跳变，锚点作废，消费端需重新等关键帧。 */
    public void beginConnection() {
        synchronized (lock) {
            anchorSet = false;
            consumerNeedsKeyframe = true;
            samples.clear();
            lock.notifyAll();
        }
    }

    public void publishConfig(byte[] avcC) {
        if (avcC != null && avcC.length > 0) {
            this.avcConfig = avcC.clone();
        }
    }

    /**
     * 以当前连接第一个送入 OpenAL 的音频 tag 建立时间域映射。
     *
     * @param flvTimestampMillis 该音频 tag 的 FLV 时间戳
     * @param fedMillis          送入该 tag 前 OpenAL 已累计的 PCM 毫秒数
     */
    public void setAudioAnchor(long flvTimestampMillis, long fedMillis) {
        synchronized (lock) {
            if (!anchorSet) {
                anchorSet = true;
                anchorFlvMillis = flvTimestampMillis;
                anchorFedMillis = Math.max(0L, fedMillis);
            }
        }
    }

    public boolean hasAudioAnchor() {
        synchronized (lock) {
            return anchorSet;
        }
    }

    /** 写入一个视频样本；锚点未建立（音频还没喂进 OpenAL）时丢弃。 */
    public void pushSample(byte[] avccSample, long dtsMillis, int compositionTimeMillis, boolean keyframe) {
        byte[] config = avcConfig;
        if (avccSample == null || avccSample.length == 0 || config == null || closed) {
            return;
        }
        synchronized (lock) {
            if (!anchorSet) {
                droppedSamples++;
                return;
            }
            long ptsMillis = anchorFedMillis + (dtsMillis + compositionTimeMillis - anchorFlvMillis);
            if (ptsMillis < 0L) {
                droppedSamples++;
                return;
            }
            if (samples.size() >= capacity) {
                dropToNextKeyframe();
            }
            samples.addLast(new VideoSample(avccSample, ptsMillis * 1_000_000L, keyframe, config));
            lock.notifyAll();
        }
    }

    /**
     * 读端取样本；返回 null 表示超时（总线可能已关闭，用 {@link #isClosed()} 区分）。
     * 加入或重连后自动丢弃到第一个关键帧。
     */
    public VideoSample poll(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + Math.max(0L, timeoutMillis) * 1_000_000L;
        synchronized (lock) {
            while (true) {
                if (consumerNeedsKeyframe) {
                    while (!samples.isEmpty() && !samples.peekFirst().keyframe()) {
                        samples.pollFirst();
                        droppedSamples++;
                    }
                    if (!samples.isEmpty()) {
                        consumerNeedsKeyframe = false;
                    }
                }
                if (!samples.isEmpty() && !consumerNeedsKeyframe) {
                    return samples.pollFirst();
                }
                if (closed) {
                    return null;
                }
                long waitNanos = deadline - System.nanoTime();
                if (waitNanos <= 0L) {
                    return null;
                }
                lock.wait(Math.max(1L, waitNanos / 1_000_000L));
            }
        }
    }

    /** 写端会话结束；读端随后以流结束退出。 */
    public void close() {
        closed = true;
        synchronized (lock) {
            samples.clear();
            lock.notifyAll();
        }
        REGISTRY.remove(key, this);
    }

    /** 丢队头样本直到下一个关键帧成为队头；没有关键帧则清空。 */
    private void dropToNextKeyframe() {
        if (!samples.isEmpty()) {
            samples.pollFirst();
            droppedSamples++;
        }
        while (!samples.isEmpty() && !samples.peekFirst().keyframe()) {
            samples.pollFirst();
            droppedSamples++;
        }
        if (samples.isEmpty()) {
            consumerNeedsKeyframe = true;
        }
    }
}
