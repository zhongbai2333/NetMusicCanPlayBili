package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseProperties;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** 仅保存标量状态的视频资源关闭时间线，不持有 decoder、纹理、线程或 future。 */
public final class VideoCloseDiagnostics {
    public enum Phase {
        FRAME_QUEUE_CLEARED,
        DECODER_CLOSE_RETURNED,
        DECODE_THREAD_EXITED,
        NATIVE_TERMINATED,
        RENDER_RELEASE_RETURNED
    }

    private static final int DEFAULT_ACTIVE_LIMIT = 256;
    private static final int DEFAULT_HISTORY_LIMIT = 128;
    private static final MediaCloseProperties.Timeouts TIMEOUTS = MediaCloseProperties.videoTimeouts();
    private static final VideoCloseDiagnostics GLOBAL = new VideoCloseDiagnostics(DEFAULT_ACTIVE_LIMIT,
            DEFAULT_HISTORY_LIMIT,
            TIMEOUTS.softTimeoutNanos(), TIMEOUTS.hardTimeoutNanos());

    private final int activeLimit;
    private final int historyLimit;
    private final long softTimeoutNanos;
    private final long hardTimeoutNanos;
    private final AtomicLong sequence = new AtomicLong();
    private final LinkedHashMap<Long, Operation> active = new LinkedHashMap<>();
    private final ArrayDeque<CompletedOperation> history = new ArrayDeque<>();
    private long softTimeouts;
    private long hardTimeouts;
    private long lateConvergences;
    private long failedConvergences;
    private long droppedOperations;

    public VideoCloseDiagnostics(int activeLimit, int historyLimit, long softTimeoutNanos, long hardTimeoutNanos) {
        this.activeLimit = Math.max(1, activeLimit);
        this.historyLimit = Math.max(1, historyLimit);
        this.softTimeoutNanos = Math.max(1L, softTimeoutNanos);
        this.hardTimeoutNanos = Math.max(this.softTimeoutNanos, hardTimeoutNanos);
    }

    public static VideoCloseDiagnostics global() {
        return GLOBAL;
    }

    public static List<String> tickGlobal() {
        return GLOBAL.tick(System.nanoTime());
    }

    public static String describeGlobal() {
        Snapshot snapshot = GLOBAL.snapshot(System.nanoTime());
        return "video closes active=" + snapshot.activeOperations()
                + " retained=" + snapshot.retainedCompleted()
                + " oldestMs=" + snapshot.oldestPendingNanos() / 1_000_000L
                + " softTimeouts=" + snapshot.softTimeouts()
                + " hardTimeouts=" + snapshot.hardTimeouts()
                + " late=" + snapshot.lateConvergences()
                + " failed=" + snapshot.failedConvergences()
                + " dropped=" + snapshot.droppedOperations()
                + " latestMs=" + (snapshot.latestConvergenceNanos() >= 0L
                        ? snapshot.latestConvergenceNanos() / 1_000_000L : -1L);
    }

    public synchronized long begin(String sessionId, Set<Phase> required, long nowNanos) {
        return begin(PlaybackSessionId.parse(sessionId), required, nowNanos);
    }

    synchronized long begin(PlaybackSessionId sessionId, Set<Phase> required, long nowNanos) {
        return begin(Optional.of(sessionId), required, nowNanos);
    }

    private long begin(Optional<PlaybackSessionId> sessionId, Set<Phase> required, long nowNanos) {
        if (active.size() >= activeLimit) {
            Long oldest = active.keySet().iterator().next();
            active.remove(oldest);
            droppedOperations++;
        }
        long id = sequence.incrementAndGet();
        EnumSet<Phase> phases = required == null || required.isEmpty()
                ? EnumSet.noneOf(Phase.class) : EnumSet.copyOf(required);
        Operation operation = new Operation(id, sessionId, nowNanos, phases);
        active.put(id, operation);
        convergeIfComplete(operation, nowNanos);
        return id;
    }

    public synchronized void complete(long operationId, Phase phase, long nowNanos) {
        complete(operationId, phase, null, nowNanos);
    }

    public synchronized void complete(long operationId, Phase phase, Throwable failure, long nowNanos) {
        Operation operation = active.get(operationId);
        if (operation == null || phase == null || !operation.required.contains(phase)) {
            return;
        }
        operation.completed.add(phase);
        if (failure != null) {
            operation.failures.putIfAbsent(phase, describeFailure(failure));
        }
        convergeIfComplete(operation, nowNanos);
    }

    /**
     * Observes one declared asynchronous close phase without a check-then-register race.
     *
     * <p>{@link CompletableFuture#whenComplete(java.util.function.BiConsumer)} also invokes the observer when the
     * future completed immediately before registration. Callers must therefore register every phase that they put
     * in an operation's required set, rather than guarding this method with a second {@code isDone()} check.</p>
     */
    void observe(long operationId, Phase phase, CompletableFuture<Void> signal) {
        if (signal == null || phase == null) {
            return;
        }
        signal.whenComplete((ignored, failure) -> complete(operationId, phase, failure, System.nanoTime()));
    }

    /** 返回本 tick 首次跨越 soft/hard deadline 的告警文本。 */
    public synchronized List<String> tick(long nowNanos) {
        List<String> warnings = new ArrayList<>();
        for (Operation operation : active.values()) {
            long age = elapsed(operation.requestedNanos, nowNanos);
            if (!operation.softTimedOut && age >= softTimeoutNanos) {
                operation.softTimedOut = true;
                softTimeouts++;
                warnings.add(describeTimeout(operation, age, false));
            }
            if (!operation.hardTimedOut && age >= hardTimeoutNanos) {
                operation.hardTimedOut = true;
                hardTimeouts++;
                warnings.add(describeTimeout(operation, age, true));
            }
        }
        return List.copyOf(warnings);
    }

    public synchronized Snapshot snapshot(long nowNanos) {
        long oldestAge = 0L;
        for (Operation operation : active.values()) {
            oldestAge = Math.max(oldestAge, elapsed(operation.requestedNanos, nowNanos));
        }
        CompletedOperation latest = history.peekLast();
        return new Snapshot(active.size(), history.size(), softTimeouts, hardTimeouts, lateConvergences,
                failedConvergences,
                droppedOperations, oldestAge,
                latest != null ? latest.durationNanos() : -1L,
                latest != null ? describeSession(latest.playbackSessionId()) : "",
                latest != null ? latest.failure() : "");
    }

    /** Scalar-only active-operation view for diagnostics and deterministic resource benches. */
    public synchronized List<String> activeDescriptions(long nowNanos) {
        List<String> descriptions = new ArrayList<>(active.size());
        for (Operation operation : active.values()) {
            EnumSet<Phase> pending = EnumSet.copyOf(operation.required);
            pending.removeAll(operation.completed);
            descriptions.add("op=" + operation.id
                    + " session=" + describeSession(operation.playbackSessionId)
                    + " ageMs=" + elapsed(operation.requestedNanos, nowNanos) / 1_000_000L
                    + " pending=" + pending
                    + " failures=" + operation.failures);
        }
        return List.copyOf(descriptions);
    }

    private void convergeIfComplete(Operation operation, long nowNanos) {
        if (!operation.completed.containsAll(operation.required)) {
            return;
        }
        active.remove(operation.id);
        long duration = elapsed(operation.requestedNanos, nowNanos);
        if (operation.hardTimedOut) {
            lateConvergences++;
        }
        String failure = operation.failures.isEmpty() ? "" : operation.failures.toString();
        if (!failure.isEmpty()) {
            failedConvergences++;
        }
        history.addLast(new CompletedOperation(operation.playbackSessionId, duration, operation.hardTimedOut,
                failure));
        while (history.size() > historyLimit) {
            history.removeFirst();
        }
    }

    private static String describeTimeout(Operation operation, long ageNanos, boolean hard) {
        EnumSet<Phase> pending = EnumSet.copyOf(operation.required);
        pending.removeAll(operation.completed);
        return "video close " + (hard ? "HARD" : "soft") + " timeout: op=" + operation.id
                + " session=" + describeSession(operation.playbackSessionId)
                + " ageMs=" + ageNanos / 1_000_000L
                + " pending=" + pending;
    }

    private static long elapsed(long start, long now) {
        long value = now - start;
        return value < 0L ? Long.MAX_VALUE : value;
    }

    private static String describeFailure(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String value = root.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return value.length() <= 240 ? value : value.substring(0, 240);
    }

    private static String describeSession(Optional<PlaybackSessionId> sessionId) {
        String value = sessionId.map(session -> session.value()).orElse("<none>");
        return value.length() <= 48 ? value : value.substring(0, 48);
    }

    public record Snapshot(int activeOperations, int retainedCompleted, long softTimeouts, long hardTimeouts,
            long lateConvergences, long failedConvergences, long droppedOperations, long oldestPendingNanos,
            long latestConvergenceNanos, String latestSessionId, String latestFailure) {
    }

    private static final class Operation {
        private final long id;
        private final Optional<PlaybackSessionId> playbackSessionId;
        private final long requestedNanos;
        private final EnumSet<Phase> required;
        private final EnumSet<Phase> completed = EnumSet.noneOf(Phase.class);
        private final java.util.EnumMap<Phase, String> failures = new java.util.EnumMap<>(Phase.class);
        private boolean softTimedOut;
        private boolean hardTimedOut;

        private Operation(long id, Optional<PlaybackSessionId> playbackSessionId, long requestedNanos,
                EnumSet<Phase> required) {
            this.id = id;
            this.playbackSessionId = playbackSessionId;
            this.requestedNanos = requestedNanos;
            this.required = required;
        }
    }

    private record CompletedOperation(Optional<PlaybackSessionId> playbackSessionId, long durationNanos,
            boolean late, String failure) {
    }
}
