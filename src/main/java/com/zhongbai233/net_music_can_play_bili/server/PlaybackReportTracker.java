package com.zhongbai233.net_music_can_play_bili.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Aggregates reports and decides when operators should be notified. */
final class PlaybackReportTracker<R> {
    private final long notificationCooldownTicks;
    private final int reminderLimit;
    private final ConcurrentHashMap<String, State<R>> reports = new ConcurrentHashMap<>();

    PlaybackReportTracker(long notificationCooldownTicks, int reminderLimit) {
        if (notificationCooldownTicks < 0L || reminderLimit <= 0) {
            throw new IllegalArgumentException("invalid report tracker policy");
        }
        this.notificationCooldownTicks = notificationCooldownTicks;
        this.reminderLimit = reminderLimit;
    }

    Decision<R> record(String sourceKey, R reporterId, long now) {
        State<R> state = reports.computeIfAbsent(sourceKey, ignored -> new State<>());
        synchronized (state) {
            state.totalReports++;
            state.lastReportGameTime = now;
            state.reporterIds.add(reporterId);
            boolean firstReport = state.totalReports == 1;
            boolean reachedLimit = state.totalReports >= reminderLimit;
            boolean shouldNotify = firstReport
                    || (!state.reminderLimitReached
                            && now - state.lastNotifiedGameTime >= notificationCooldownTicks)
                    || (reachedLimit && !state.reminderLimitReached);
            return new Decision<>(shouldNotify, !firstReport, reachedLimit, snapshot(state));
        }
    }

    Snapshot<R> markNotified(String sourceKey, long now, boolean limitReached) {
        State<R> state = reports.get(sourceKey);
        if (state == null) {
            return Snapshot.empty();
        }
        synchronized (state) {
            state.lastNotifiedGameTime = now;
            state.notifiedReportCount = state.totalReports;
            state.reminderLimitReached |= limitReached;
            return snapshot(state);
        }
    }

    List<Entry<R>> snapshots() {
        List<Entry<R>> result = new ArrayList<>();
        reports.forEach((key, state) -> {
            synchronized (state) {
                result.add(new Entry<>(key, snapshot(state)));
            }
        });
        return result;
    }

    void retainSources(Set<String> activeSourceKeys) {
        reports.keySet().removeIf(key -> !activeSourceKeys.contains(key));
    }

    private Snapshot<R> snapshot(State<R> state) {
        return new Snapshot<>(state.totalReports, state.reporterIds.size(), state.notifiedReportCount,
                state.lastNotifiedGameTime, state.lastReportGameTime, state.reminderLimitReached);
    }

    record Decision<R>(boolean shouldNotify, boolean merged, boolean reachedLimit, Snapshot<R> snapshot) {
    }

    record Entry<R>(String sourceKey, Snapshot<R> snapshot) {
    }

    record Snapshot<R>(int totalReports, int uniqueReporterCount, int notifiedReportCount,
            long lastNotifiedGameTime, long lastReportGameTime, boolean reminderLimitReached) {
        static <R> Snapshot<R> empty() {
            return new Snapshot<>(0, 0, 0, Long.MIN_VALUE, 0L, false);
        }

        int suppressedReportCount() {
            return Math.max(0, totalReports - notifiedReportCount - 1);
        }
    }

    private static final class State<R> {
        private final Set<R> reporterIds = ConcurrentHashMap.newKeySet();
        private int totalReports;
        private int notifiedReportCount;
        private long lastNotifiedGameTime = Long.MIN_VALUE;
        private long lastReportGameTime;
        private boolean reminderLimitReached;
    }
}