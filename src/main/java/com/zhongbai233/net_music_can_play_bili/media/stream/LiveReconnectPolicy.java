package com.zhongbai233.net_music_can_play_bili.media.stream;

/**
 * 直播长连接的重连节奏。
 *
 * <p>
 * 直播 CDN 会主动切断长时间连接，因此「播了很久之后断开」属于正常现象，应当立即重连；
 * 只有短时间内反复失败才说明房间下播或地址失效，需要退避并最终放弃。
 * </p>
 */
public final class LiveReconnectPolicy {
    /** {@link #onStreamEnded(long)} 返回该值表示不应再重连。 */
    public static final long GIVE_UP = -1L;

    private final int maxConsecutiveFailures;
    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final long healthyStreamMillis;

    private int consecutiveFailures;

    public LiveReconnectPolicy() {
        this(5, 1_000L, 15_000L, 20_000L);
    }

    public LiveReconnectPolicy(int maxConsecutiveFailures, long baseDelayMillis, long maxDelayMillis,
            long healthyStreamMillis) {
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
        this.baseDelayMillis = Math.max(0L, baseDelayMillis);
        this.maxDelayMillis = Math.max(this.baseDelayMillis, maxDelayMillis);
        this.healthyStreamMillis = Math.max(0L, healthyStreamMillis);
    }

    /**
     * 记录一次连接结束。
     *
     * @param streamDurationMillis 本次连接实际持续的时间
     * @return 下次重连前的等待毫秒数，或 {@link #GIVE_UP}
     */
    public long onStreamEnded(long streamDurationMillis) {
        if (streamDurationMillis >= healthyStreamMillis) {
            consecutiveFailures = 0;
            return baseDelayMillis;
        }
        consecutiveFailures++;
        if (consecutiveFailures > maxConsecutiveFailures) {
            return GIVE_UP;
        }
        long shift = Math.min(consecutiveFailures - 1, 30);
        long delay = baseDelayMillis << shift;
        return delay <= 0L || delay > maxDelayMillis ? maxDelayMillis : delay;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public void reset() {
        consecutiveFailures = 0;
    }
}
