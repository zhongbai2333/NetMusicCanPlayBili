package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;

/**
 * Owns one asynchronous AI-subtitle result per source/session and shares it between lightweight consumers.
 *
 * <p>The registry is deliberately independent of Minecraft and the concrete subtitle representation. Removing the
 * final consumer cancels the exact task and removes the entry immediately; completion callbacks verify entry and
 * task identity, so a late result cannot recreate or overwrite a replacement session.</p>
 */
final class AiSubtitleSessionRegistry<C, S, R> {
    enum Status {
        LOADING,
        READY,
        UNAVAILABLE,
        FAILED
    }

    record Snapshot<R>(Status status, R result, String failureReason) {
        Snapshot {
            Objects.requireNonNull(status, "status");
            failureReason = failureReason != null ? failureReason : "";
        }

        static <R> Snapshot<R> loading() {
            return new Snapshot<>(Status.LOADING, null, "");
        }

        static <R> Snapshot<R> unavailable() {
            return new Snapshot<>(Status.UNAVAILABLE, null, "");
        }
    }

    record Diagnostics(int sessions, int consumers, int loading, int ready, int unavailable, int failed) {
    }

    interface Task<R> {
        CompletableFuture<R> future();

        void cancel();
    }

    @FunctionalInterface
    interface Loader<R> {
        Task<R> load(String rawUrl, String title);
    }

    private final Map<C, SessionKey<S>> consumers = new HashMap<>();
    private final Map<SessionKey<S>, Entry<C, R>> sessions = new HashMap<>();
    private final Loader<R> loader;

    AiSubtitleSessionRegistry(Loader<R> loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    synchronized void acquire(C consumer, S source, PlaybackSessionId sessionId, String rawUrl, String title) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sessionId, "sessionId");
        String normalizedUrl = rawUrl != null ? rawUrl.trim() : "";
        String normalizedTitle = title != null ? title : "";
        SessionKey<S> desired = new SessionKey<>(source, sessionId);
        SessionKey<S> current = consumers.get(consumer);
        if (desired.equals(current)) {
            Entry<C, R> existing = sessions.get(desired);
            if (existing != null && existing.rawUrl.equals(normalizedUrl)) {
                return;
            }
        }
        releaseLocked(consumer);

        Entry<C, R> entry = sessions.get(desired);
        if (entry == null || !entry.rawUrl.equals(normalizedUrl)) {
            if (entry != null) {
                entry.task.cancel();
                for (C displaced : entry.consumers.keySet()) {
                    consumers.remove(displaced, desired);
                }
            }
            Task<R> task = Objects.requireNonNull(loader.load(normalizedUrl, normalizedTitle), "loader task");
            entry = new Entry<>(normalizedUrl, task);
            sessions.put(desired, entry);
            watch(desired, entry);
        }
        entry.consumers.put(consumer, Boolean.TRUE);
        consumers.put(consumer, desired);
    }

    synchronized Snapshot<R> snapshot(S source, PlaybackSessionId sessionId) {
        if (source == null || sessionId == null) {
            return Snapshot.unavailable();
        }
        Entry<C, R> entry = sessions.get(new SessionKey<>(source, sessionId));
        return entry != null ? entry.snapshot : Snapshot.unavailable();
    }

    synchronized void release(C consumer) {
        if (consumer != null) {
            releaseLocked(consumer);
        }
    }

    synchronized void clear() {
        for (Entry<C, R> entry : sessions.values()) {
            entry.task.cancel();
        }
        sessions.clear();
        consumers.clear();
    }

    synchronized void releaseMatching(Predicate<? super C> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        for (C consumer : new ArrayList<>(consumers.keySet())) {
            if (predicate.test(consumer)) {
                releaseLocked(consumer);
            }
        }
    }

    synchronized int activeSessions() {
        return sessions.size();
    }

    synchronized int activeConsumers() {
        return consumers.size();
    }

    synchronized Diagnostics diagnostics() {
        int loading = 0;
        int ready = 0;
        int unavailable = 0;
        int failed = 0;
        for (Entry<C, R> entry : sessions.values()) {
            switch (entry.snapshot.status()) {
                case LOADING -> loading++;
                case READY -> ready++;
                case UNAVAILABLE -> unavailable++;
                case FAILED -> failed++;
            }
        }
        return new Diagnostics(sessions.size(), consumers.size(), loading, ready, unavailable, failed);
    }

    private void watch(SessionKey<S> key, Entry<C, R> expected) {
        expected.task.future().whenComplete((result, error) -> {
            synchronized (AiSubtitleSessionRegistry.this) {
                if (sessions.get(key) != expected) {
                    return;
                }
                if (error == null) {
                    expected.snapshot = result != null
                            ? new Snapshot<>(Status.READY, result, "")
                            : Snapshot.unavailable();
                    return;
                }
                Throwable cause = unwrap(error);
                if (cause instanceof CancellationException) {
                    // Owner-driven cancellation removes the entry before the callback. A still-current cancelled task
                    // is an external failure and must not be reported as a usable empty subtitle.
                    expected.snapshot = new Snapshot<>(Status.FAILED, null, "cancelled");
                } else {
                    expected.snapshot = new Snapshot<>(Status.FAILED, null,
                            cause.getClass().getSimpleName() + ": " + safeMessage(cause));
                }
            }
        });
    }

    private void releaseLocked(C consumer) {
        SessionKey<S> key = consumers.remove(consumer);
        if (key == null) {
            return;
        }
        Entry<C, R> entry = sessions.get(key);
        if (entry == null) {
            return;
        }
        entry.consumers.remove(consumer);
        if (!entry.consumers.isEmpty() || !sessions.remove(key, entry)) {
            return;
        }
        entry.task.cancel();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message != null ? message : "";
    }

    private record SessionKey<S>(S source, PlaybackSessionId sessionId) {
        private SessionKey {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    private static final class Entry<C, R> {
        private final String rawUrl;
        private final Task<R> task;
        private final Map<C, Boolean> consumers = new HashMap<>();
        private Snapshot<R> snapshot = Snapshot.loading();

        private Entry(String rawUrl, Task<R> task) {
            this.rawUrl = rawUrl;
            this.task = task;
        }
    }
}
