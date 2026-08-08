package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded scalar-only diagnostics for media HTTP request cancellation and body convergence. */
public final class HttpRequestCloseDiagnostics {
    private static final int DEFAULT_ACTIVE_LIMIT = 512;
    private static final int DEFAULT_HISTORY_LIMIT = 256;
    private static final HttpRequestCloseDiagnostics GLOBAL = new HttpRequestCloseDiagnostics(
            DEFAULT_ACTIVE_LIMIT, DEFAULT_HISTORY_LIMIT);

    private final int activeLimit;
    private final int historyLimit;
    private final AtomicLong sequence = new AtomicLong();
    private final LinkedHashMap<Long, Operation> active = new LinkedHashMap<>();
    private final ArrayDeque<CompletedOperation> history = new ArrayDeque<>();
    private long started;
    private long cancelled;
    private long completed;
    private long failed;
    private long dropped;

    public HttpRequestCloseDiagnostics(int activeLimit, int historyLimit) {
        this.activeLimit = Math.max(1, activeLimit);
        this.historyLimit = Math.max(1, historyLimit);
    }

    public static HttpRequestCloseDiagnostics global() {
        return GLOBAL;
    }

    public synchronized long begin(String kind, String host, long rangeStart, long rangeEnd, long nowNanos) {
        if (active.size() >= activeLimit) {
            active.remove(active.keySet().iterator().next());
            dropped++;
        }
        long id = sequence.incrementAndGet();
        active.put(id, new Operation(id, safe(kind), safe(host), rangeStart, rangeEnd, nowNanos));
        started++;
        return id;
    }

    public synchronized void headers(long id, int statusCode) {
        Operation operation = active.get(id);
        if (operation != null) operation.statusCode = statusCode;
    }

    public synchronized void bodyPublished(long id) {
        Operation operation = active.get(id);
        if (operation != null) operation.bodyPublished = true;
    }

    public synchronized void cancelRequested(long id) {
        Operation operation = active.get(id);
        if (operation != null && !operation.cancelRequested) {
            operation.cancelRequested = true;
            cancelled++;
        }
    }

    public synchronized void terminal(long id, boolean success, long bytes, long nowNanos) {
        Operation operation = active.remove(id);
        if (operation == null) return;
        operation.bytes = Math.max(0L, bytes);
        if (success) completed++; else failed++;
        history.addLast(new CompletedOperation(operation.id, operation.kind, operation.host,
            operation.rangeStart, operation.rangeEnd, operation.statusCode,
                operation.cancelRequested, operation.bodyPublished, operation.bytes,
                elapsed(operation.startedNanos, nowNanos)));
        while (history.size() > historyLimit) history.removeFirst();
    }

    public synchronized Snapshot snapshot(long nowNanos) {
        long oldest = 0L;
        for (Operation operation : active.values()) {
            oldest = Math.max(oldest, elapsed(operation.startedNanos, nowNanos));
        }
        CompletedOperation latest = history.peekLast();
        return new Snapshot(active.size(), history.size(), started, cancelled, completed, failed, dropped,
                oldest, latest != null ? latest.durationNanos : -1L);
    }

    private static long elapsed(long start, long now) {
        long elapsed = now - start;
        return elapsed < 0L ? Long.MAX_VALUE : elapsed;
    }

    private static String safe(String value) {
        String normalized = value == null || value.isBlank() ? "<none>" : value;
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    public record Snapshot(int activeRequests, int retainedCompleted, long startedRequests,
            long cancelRequests, long completedRequests, long failedRequests, long droppedRequests,
            long oldestActiveNanos, long latestConvergenceNanos) {
    }

    private static final class Operation {
        private final long id;
        private final String kind;
        private final String host;
        private final long rangeStart;
        private final long rangeEnd;
        private final long startedNanos;
        private int statusCode = -1;
        private boolean cancelRequested;
        private boolean bodyPublished;
        private long bytes;

        private Operation(long id, String kind, String host, long rangeStart, long rangeEnd, long startedNanos) {
            this.id = id;
            this.kind = kind;
            this.host = host;
            this.rangeStart = rangeStart;
            this.rangeEnd = rangeEnd;
            this.startedNanos = startedNanos;
        }
    }

        private record CompletedOperation(long id, String kind, String host, long rangeStart, long rangeEnd,
            int statusCode, boolean cancelled, boolean bodyPublished, long bytes, long durationNanos) {
    }
}