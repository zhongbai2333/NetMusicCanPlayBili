package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
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
    private static final VideoCloseDiagnostics GLOBAL = new VideoCloseDiagnostics(DEFAULT_ACTIVE_LIMIT,
            DEFAULT_HISTORY_LIMIT,
            Long.getLong("ncpb.close_diag.video_soft_ms", 3_000L) * 1_000_000L,
            Long.getLong("ncpb.close_diag.video_hard_ms", 6_000L) * 1_000_000L);

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
                + " dropped=" + snapshot.droppedOperations()
                + " latestMs=" + (snapshot.latestConvergenceNanos() >= 0L
                        ? snapshot.latestConvergenceNanos() / 1_000_000L : -1L);
    }

    public synchronized long begin(String sessionId, Set<Phase> required, long nowNanos) {
        if (active.size() >= activeLimit) {
            Long oldest = active.keySet().iterator().next();
            active.remove(oldest);
            droppedOperations++;
        }
        long id = sequence.incrementAndGet();
        EnumSet<Phase> phases = required == null || required.isEmpty()
                ? EnumSet.noneOf(Phase.class) : EnumSet.copyOf(required);
        Operation operation = new Operation(id, safeSessionId(sessionId), nowNanos, phases);
        active.put(id, operation);
        convergeIfComplete(operation, nowNanos);
        return id;
    }

    public synchronized void complete(long operationId, Phase phase, long nowNanos) {
        Operation operation = active.get(operationId);
        if (operation == null || phase == null || !operation.required.contains(phase)) {
            return;
        }
        operation.completed.add(phase);
        convergeIfComplete(operation, nowNanos);
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
                droppedOperations, oldestAge,
                latest != null ? latest.durationNanos() : -1L,
                latest != null ? latest.sessionId() : "");
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
        history.addLast(new CompletedOperation(operation.sessionId, duration, operation.hardTimedOut));
        while (history.size() > historyLimit) {
            history.removeFirst();
        }
    }

    private static String describeTimeout(Operation operation, long ageNanos, boolean hard) {
        EnumSet<Phase> pending = EnumSet.copyOf(operation.required);
        pending.removeAll(operation.completed);
        return "video close " + (hard ? "HARD" : "soft") + " timeout: op=" + operation.id
                + " session=" + operation.sessionId + " ageMs=" + ageNanos / 1_000_000L
                + " pending=" + pending;
    }

    private static long elapsed(long start, long now) {
        long value = now - start;
        return value < 0L ? Long.MAX_VALUE : value;
    }

    private static String safeSessionId(String sessionId) {
        String value = sessionId == null || sessionId.isBlank() ? "<none>" : sessionId;
        return value.length() <= 48 ? value : value.substring(0, 48);
    }

    public record Snapshot(int activeOperations, int retainedCompleted, long softTimeouts, long hardTimeouts,
            long lateConvergences, long droppedOperations, long oldestPendingNanos,
            long latestConvergenceNanos, String latestSessionId) {
    }

    private static final class Operation {
        private final long id;
        private final String sessionId;
        private final long requestedNanos;
        private final EnumSet<Phase> required;
        private final EnumSet<Phase> completed = EnumSet.noneOf(Phase.class);
        private boolean softTimedOut;
        private boolean hardTimedOut;

        private Operation(long id, String sessionId, long requestedNanos, EnumSet<Phase> required) {
            this.id = id;
            this.sessionId = sessionId;
            this.requestedNanos = requestedNanos;
            this.required = required;
        }
    }

    private record CompletedOperation(String sessionId, long durationNanos, boolean late) {
    }
}