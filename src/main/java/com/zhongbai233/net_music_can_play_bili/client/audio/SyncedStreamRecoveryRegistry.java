package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.net.URL;
import java.util.Objects;
import java.util.Optional;
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
    private static final System.Logger LOGGER = System.getLogger(SyncedStreamRecoveryRegistry.class.getName());
    private static final AudioStreamProperties.Recovery PROPERTIES = AudioStreamProperties.recovery();
    private static final int MAX_ATTEMPTS = PROPERTIES.maxAttempts();
    private static final long MIN_INTERVAL_MILLIS = PROPERTIES.minIntervalMillis();

    private static final ConcurrentHashMap<PlaybackSessionId, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final AtomicLong GENERATIONS = new AtomicLong();

    private SyncedStreamRecoveryRegistry() {
    }

    public static Registration register(String sessionId, RecoveryHandler handler) {
        return PlaybackSessionId.parse(sessionId)
                .map(parsed -> register(parsed, handler))
                .orElse(Registration.NONE);
    }

    public static Registration register(PlaybackSessionId sessionId, RecoveryHandler handler) {
        if (sessionId == null || handler == null) {
            return Registration.NONE;
        }
        long generation = GENERATIONS.incrementAndGet();
        ENTRIES.compute(sessionId, (ignored, existing) -> existing == null
                ? new Entry(handler, new AtomicInteger(), 0L, generation)
                : new Entry(handler, existing.attempts(), existing.lastAttemptMillis(), generation));
        return new Registration(sessionId, generation);
    }

    public static void unregister(String sessionId) {
        PlaybackSessionId.parse(sessionId).ifPresent(ENTRIES::remove);
    }

    public static void unregister(Registration registration) {
        if (registration == null || registration == Registration.NONE) {
            return;
        }
        registration.playbackSessionId().ifPresent(sessionId -> ENTRIES.computeIfPresent(sessionId,
                (ignored, entry) -> entry.generation() == registration.generation() ? null : entry));
    }

    public static void clear() {
        ENTRIES.clear();
    }

    public static boolean reportFailure(String sessionId, URL failedUrl, Throwable error) {
        return PlaybackSessionId.parse(sessionId)
                .map(parsed -> reportFailure(parsed, failedUrl, error))
                .orElse(false);
    }

    public static boolean reportFailure(PlaybackSessionId sessionId, URL failedUrl, Throwable error) {
        if (sessionId == null) {
            return false;
        }
        Entry entry = ENTRIES.get(sessionId);
        if (entry == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - entry.lastAttemptMillis() < MIN_INTERVAL_MILLIS) {
            LOGGER.log(System.Logger.Level.DEBUG, "忽略过密的媒体流恢复请求: session={0} reason={1}", sessionId,
                    error != null ? error.toString() : "unknown");
            return true;
        }
        int attempt = entry.attempts().incrementAndGet();
        if (attempt > Math.max(0, MAX_ATTEMPTS)) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "媒体流自动恢复次数耗尽: session={0} attempts={1} lastError={2}", sessionId, attempt - 1,
                    error != null ? error.toString() : "unknown");
            ENTRIES.remove(sessionId, entry);
            return false;
        }
        Entry attemptedEntry = entry.withLastAttemptMillis(now);
        if (!ENTRIES.replace(sessionId, entry, attemptedEntry)) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "媒体流恢复处理器已由新播放代接管: session={0} attempt={1}", sessionId, attempt);
            return true;
        }
        try {
            boolean scheduled = attemptedEntry.handler().recover(
                    new RecoveryRequest(sessionId, failedUrl, error, attempt));
            if (!scheduled) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "媒体流恢复处理器拒绝恢复: session={0} attempt={1} reason={2}", sessionId, attempt,
                        error != null ? error.toString() : "unknown");
            }
            return scheduled;
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "媒体流恢复处理器异常: session=" + sessionId + " attempt=" + attempt, e);
            return false;
        }
    }

    public record RecoveryRequest(PlaybackSessionId playbackSessionId, URL failedUrl, Throwable error, int attempt) {
        public RecoveryRequest {
            playbackSessionId = Objects.requireNonNull(playbackSessionId, "playbackSessionId");
        }

        public RecoveryRequest(String sessionId, URL failedUrl, Throwable error, int attempt) {
            this(PlaybackSessionId.of(sessionId), failedUrl, error, attempt);
        }

        public String sessionId() {
            return playbackSessionId.value();
        }
    }

    @FunctionalInterface
    public interface RecoveryHandler {
        boolean recover(RecoveryRequest request);
    }

    public record Registration(Optional<PlaybackSessionId> playbackSessionId, long generation) {
        public Registration {
            playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
        }

        public Registration(String sessionId, long generation) {
            this(PlaybackSessionId.parse(sessionId), generation);
        }

        public Registration(PlaybackSessionId sessionId, long generation) {
            this(Optional.ofNullable(sessionId), generation);
        }

        public String sessionId() {
            return playbackSessionId.map(PlaybackSessionId::value).orElse("");
        }

        private static final Registration NONE = new Registration(Optional.empty(), 0L);
    }

    private record Entry(RecoveryHandler handler, AtomicInteger attempts, long lastAttemptMillis, long generation) {
        Entry withLastAttemptMillis(long value) {
            return new Entry(handler, attempts, value, generation);
        }
    }
}
