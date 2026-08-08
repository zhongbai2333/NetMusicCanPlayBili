package com.zhongbai233.net_music_can_play_bili.media.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 仅保存标量状态的 OpenAL native source/buffer 删除诊断。 */
public final class AudioNativeCloseDiagnostics {
    private static final int DEFAULT_ACTIVE_LIMIT = 256;
    private static final int DEFAULT_HISTORY_LIMIT = 128;
    private static final AudioNativeCloseDiagnostics GLOBAL = new AudioNativeCloseDiagnostics(
            DEFAULT_ACTIVE_LIMIT, DEFAULT_HISTORY_LIMIT,
            Long.getLong("ncpb.close_diag.openal_soft_ms", 500L) * 1_000_000L,
            Long.getLong("ncpb.close_diag.openal_hard_ms", 3_000L) * 1_000_000L);

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
    private long totalSourcesRequested;
    private long totalBuffersRequested;

    public AudioNativeCloseDiagnostics(int activeLimit, int historyLimit, long softTimeoutNanos,
            long hardTimeoutNanos) {
        this.activeLimit = Math.max(1, activeLimit);
        this.historyLimit = Math.max(1, historyLimit);
        this.softTimeoutNanos = Math.max(1L, softTimeoutNanos);
        this.hardTimeoutNanos = Math.max(this.softTimeoutNanos, hardTimeoutNanos);
    }

    public static AudioNativeCloseDiagnostics global() {
        return GLOBAL;
    }

    public static List<String> tickGlobal() {
        return GLOBAL.tick(System.nanoTime());
    }

    public static String describeGlobal(int pendingNativeBatches) {
        Snapshot snapshot = GLOBAL.snapshot(System.nanoTime());
        return "openal closes active=" + snapshot.activeOperations()
                + " deferred=" + snapshot.deferredOperations()
                + " pendingBatches=" + Math.max(0, pendingNativeBatches)
                + " oldestMs=" + snapshot.oldestPendingNanos() / 1_000_000L
                + " softTimeouts=" + snapshot.softTimeouts()
                + " hardTimeouts=" + snapshot.hardTimeouts()
                + " late=" + snapshot.lateConvergences()
                + " dropped=" + snapshot.droppedOperations()
                + " requestedSources=" + snapshot.totalSourcesRequested()
                + " requestedBuffers=" + snapshot.totalBuffersRequested();
    }

    public synchronized long begin(int sourceCount, int bufferCount, long nowNanos) {
        if (active.size() >= activeLimit) {
            Long oldest = active.keySet().iterator().next();
            active.remove(oldest);
            droppedOperations++;
        }
        long id = sequence.incrementAndGet();
        int sources = Math.max(0, sourceCount);
        int buffers = Math.max(0, bufferCount);
        active.put(id, new Operation(id, nowNanos, sources, buffers));
        totalSourcesRequested += sources;
        totalBuffersRequested += buffers;
        return id;
    }

    public synchronized void deferred(long operationId) {
        Operation operation = active.get(operationId);
        if (operation != null) {
            operation.deferred = true;
        }
    }

    public synchronized void complete(long operationId, long nowNanos) {
        Operation operation = active.remove(operationId);
        if (operation == null) {
            return;
        }
        long duration = elapsed(operation.requestedNanos, nowNanos);
        if (operation.hardTimedOut) {
            lateConvergences++;
        }
        history.addLast(new CompletedOperation(duration, operation.deferred, operation.hardTimedOut));
        while (history.size() > historyLimit) {
            history.removeFirst();
        }
    }

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
        int deferred = 0;
        for (Operation operation : active.values()) {
            oldestAge = Math.max(oldestAge, elapsed(operation.requestedNanos, nowNanos));
            if (operation.deferred) {
                deferred++;
            }
        }
        return new Snapshot(active.size(), deferred, history.size(), softTimeouts, hardTimeouts,
                lateConvergences, droppedOperations, oldestAge, totalSourcesRequested, totalBuffersRequested);
    }

    private static String describeTimeout(Operation operation, long ageNanos, boolean hard) {
        return "OpenAL native delete " + (hard ? "HARD" : "soft") + " timeout: op=" + operation.id
                + " ageMs=" + ageNanos / 1_000_000L + " deferred=" + operation.deferred
                + " sources=" + operation.sourceCount + " buffers=" + operation.bufferCount;
    }

    private static long elapsed(long start, long now) {
        long value = now - start;
        return value < 0L ? Long.MAX_VALUE : value;
    }

    public record Snapshot(int activeOperations, int deferredOperations, int retainedCompleted,
            long softTimeouts, long hardTimeouts, long lateConvergences, long droppedOperations,
            long oldestPendingNanos, long totalSourcesRequested, long totalBuffersRequested) {
    }

    private static final class Operation {
        private final long id;
        private final long requestedNanos;
        private final int sourceCount;
        private final int bufferCount;
        private boolean deferred;
        private boolean softTimedOut;
        private boolean hardTimedOut;

        private Operation(long id, long requestedNanos, int sourceCount, int bufferCount) {
            this.id = id;
            this.requestedNanos = requestedNanos;
            this.sourceCount = sourceCount;
            this.bufferCount = bufferCount;
        }
    }

    private record CompletedOperation(long durationNanos, boolean deferred, boolean late) {
    }
}