package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端同步媒体流断链恢复注册表。
 *
 * <p>
 * HTTP/fMP4 管线只知道 session 断了，不应该知道具体是现代化唱片机还是 MP4。
 * 两条播放线路在起播时按 session 注册恢复动作；底层流在播放中遇到非主动关闭的 I/O 失败时回调这里。
 * </p>
 */
public final class SyncedStreamRecoveryRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_ATTEMPTS = Integer.getInteger("ncpb.bili.media.stream_recovery.max_attempts", 3);
    private static final long MIN_INTERVAL_MILLIS = Long.getLong("ncpb.bili.media.stream_recovery.min_interval_ms",
            1_000L);

    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final AtomicLong GENERATIONS = new AtomicLong();

    private SyncedStreamRecoveryRegistry() {
    }

    public static Registration register(String sessionId, RecoveryHandler handler) {
        if (sessionId == null || sessionId.isBlank() || handler == null) {
            return Registration.NONE;
        }
        long generation = GENERATIONS.incrementAndGet();
        ENTRIES.compute(sessionId, (ignored, existing) -> existing == null
                ? new Entry(handler, new AtomicInteger(), 0L, generation)
                : new Entry(handler, existing.attempts(), existing.lastAttemptMillis(), generation));
        return new Registration(sessionId, generation);
    }

    public static void unregister(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            ENTRIES.remove(sessionId);
        }
    }

    public static void unregister(Registration registration) {
        if (registration == null || registration == Registration.NONE) {
            return;
        }
        ENTRIES.computeIfPresent(registration.sessionId(),
                (ignored, entry) -> entry.generation() == registration.generation() ? null : entry);
    }

    public static void clear() {
        ENTRIES.clear();
    }

    public static boolean reportFailure(String sessionId, URL failedUrl, Throwable error) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Entry entry = ENTRIES.get(sessionId);
        if (entry == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - entry.lastAttemptMillis() < MIN_INTERVAL_MILLIS) {
            LOGGER.debug("忽略过密的媒体流恢复请求: session={} reason={}", sessionId,
                    error != null ? error.toString() : "unknown");
            return true;
        }
        int attempt = entry.attempts().incrementAndGet();
        if (attempt > Math.max(0, MAX_ATTEMPTS)) {
            LOGGER.warn("媒体流自动恢复次数耗尽: session={} attempts={} lastError={}", sessionId, attempt - 1,
                    error != null ? error.toString() : "unknown");
            ENTRIES.remove(sessionId, entry);
            return false;
        }
        Entry attemptedEntry = entry.withLastAttemptMillis(now);
        if (!ENTRIES.replace(sessionId, entry, attemptedEntry)) {
            LOGGER.debug("媒体流恢复处理器已由新播放代接管: session={} attempt={}", sessionId, attempt);
            return true;
        }
        try {
            boolean scheduled = attemptedEntry.handler().recover(
                    new RecoveryRequest(sessionId, failedUrl, error, attempt));
            if (!scheduled) {
                LOGGER.warn("媒体流恢复处理器拒绝恢复: session={} attempt={} reason={}", sessionId, attempt,
                        error != null ? error.toString() : "unknown");
            }
            return scheduled;
        } catch (RuntimeException e) {
            LOGGER.warn("媒体流恢复处理器异常: session={} attempt={}", sessionId, attempt, e);
            return false;
        }
    }

    public record RecoveryRequest(String sessionId, URL failedUrl, Throwable error, int attempt) {
    }

    @FunctionalInterface
    public interface RecoveryHandler {
        boolean recover(RecoveryRequest request);
    }

    public record Registration(String sessionId, long generation) {
        private static final Registration NONE = new Registration("", 0L);
    }

    private record Entry(RecoveryHandler handler, AtomicInteger attempts, long lastAttemptMillis, long generation) {
        Entry withLastAttemptMillis(long value) {
            return new Entry(handler, attempts, value, generation);
        }
    }
}
