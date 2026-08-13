package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

import java.util.concurrent.TimeUnit;

/** JVM property boundary for asynchronous media close and native-close diagnostics. */
public final class MediaCloseProperties {
    static final String EXECUTOR_THREADS = "ncpb.media.close.threads";
    static final String EXECUTOR_QUEUE_CAPACITY = "ncpb.media.close.queue";
    static final String OPENAL_SOFT_TIMEOUT_MILLIS = "ncpb.close_diag.openal_soft_ms";
    static final String OPENAL_HARD_TIMEOUT_MILLIS = "ncpb.close_diag.openal_hard_ms";
    static final String OPENAL_RETRY_MILLIS = "ncpb.close_diag.openal_retry_ms";
    static final String VIDEO_SOFT_TIMEOUT_MILLIS = "ncpb.close_diag.video_soft_ms";
    static final String VIDEO_HARD_TIMEOUT_MILLIS = "ncpb.close_diag.video_hard_ms";

    private MediaCloseProperties() {
    }

    static ExecutorConfig executor() {
        return new ExecutorConfig(
                NcpbSystemProperties.intValue(EXECUTOR_THREADS, 2),
                NcpbSystemProperties.intValue(EXECUTOR_QUEUE_CAPACITY, 32));
    }

    public static Timeouts openAlTimeouts() {
        return new Timeouts(
                positiveMillisToNanos(NcpbSystemProperties.longValue(OPENAL_SOFT_TIMEOUT_MILLIS, 500L)),
                positiveMillisToNanos(NcpbSystemProperties.longValue(OPENAL_HARD_TIMEOUT_MILLIS, 3_000L)));
    }

    public static long openAlRetryNanos() {
        return positiveMillisToNanos(NcpbSystemProperties.longValue(OPENAL_RETRY_MILLIS, 500L));
    }

    public static Timeouts videoTimeouts() {
        return new Timeouts(
                positiveMillisToNanos(NcpbSystemProperties.longValue(VIDEO_SOFT_TIMEOUT_MILLIS, 3_000L)),
                positiveMillisToNanos(NcpbSystemProperties.longValue(VIDEO_HARD_TIMEOUT_MILLIS, 6_000L)));
    }

    private static long positiveMillisToNanos(long millis) {
        return Math.max(1L, TimeUnit.MILLISECONDS.toNanos(Math.max(0L, millis)));
    }

    record ExecutorConfig(int threads, int queueCapacity) {
        ExecutorConfig {
            threads = Math.max(1, threads);
            queueCapacity = Math.max(1, queueCapacity);
        }
    }

    public record Timeouts(long softTimeoutNanos, long hardTimeoutNanos) {
        public Timeouts {
            softTimeoutNanos = Math.max(1L, softTimeoutNanos);
            hardTimeoutNanos = Math.max(softTimeoutNanos, hardTimeoutNanos);
        }
    }
}
