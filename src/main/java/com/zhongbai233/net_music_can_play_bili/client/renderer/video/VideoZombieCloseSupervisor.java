package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** 跟踪已脱离业务实例、但物理资源尚未全部收敛的旧 decoder generation。 */
public final class VideoZombieCloseSupervisor {
    private static final VideoZombieCloseSupervisor GLOBAL = new VideoZombieCloseSupervisor();

    private final ConcurrentMap<Key, CompletableFuture<Void>> zombies = new ConcurrentHashMap<>();
    private final AtomicLong lateConvergences = new AtomicLong();

    public static VideoZombieCloseSupervisor global() {
        return GLOBAL;
    }

    public void track(String sessionId, long generation, CompletableFuture<Void> closeCompletion,
            CompletableFuture<Void> nativeTermination, CompletableFuture<Void> decodeExit) {
        Key key = new Key(PlaybackSessionId.parse(sessionId), generation);
        CompletableFuture<Void> convergence = CompletableFuture.allOf(
                nonNull(closeCompletion), nonNull(nativeTermination), nonNull(decodeExit));
        if (completedNormally(convergence)) {
            lateConvergences.incrementAndGet();
            return;
        }
        if (zombies.putIfAbsent(key, convergence) != null) {
            return;
        }
        convergence.whenComplete((ignored, error) -> {
            // Exceptional completion is a physical close failure, not resource
            // convergence. Retain it as an active zombie for diagnostics.
            if (error == null && zombies.remove(key, convergence)) {
                lateConvergences.incrementAndGet();
            }
        });
    }

    Snapshot snapshot() {
        return new Snapshot(zombies.size(), lateConvergences.get());
    }

    void clearForTest() {
        zombies.clear();
        lateConvergences.set(0L);
    }

    private static CompletableFuture<Void> nonNull(CompletableFuture<Void> future) {
        return future != null ? future : CompletableFuture.completedFuture(null);
    }

    private static boolean completedNormally(CompletableFuture<Void> future) {
        if (!future.isDone() || future.isCancelled() || future.isCompletedExceptionally()) {
            return false;
        }
        try {
            future.join();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    record Snapshot(int activeZombies, long lateConvergences) {
    }

    private record Key(Optional<PlaybackSessionId> playbackSessionId, long generation) {
    }
}
