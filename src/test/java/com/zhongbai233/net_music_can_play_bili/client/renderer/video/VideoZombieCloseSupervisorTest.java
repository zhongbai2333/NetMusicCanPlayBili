package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoZombieCloseSupervisorTest {
    private final VideoZombieCloseSupervisor supervisor = VideoZombieCloseSupervisor.global();

    @AfterEach
    void clear() {
        supervisor.clearForTest();
    }

    @Test
    void zombieRemainsUntilEveryCloseSignalConverges() {
        CompletableFuture<Void> close = new CompletableFuture<>();
        CompletableFuture<Void> nativeTermination = new CompletableFuture<>();
        CompletableFuture<Void> decodeExit = new CompletableFuture<>();
        supervisor.track("session", 7L, close, nativeTermination, decodeExit);

        close.complete(null);
        nativeTermination.complete(null);
        assertEquals(1, supervisor.snapshot().activeZombies());

        decodeExit.complete(null);
        assertEquals(0, supervisor.snapshot().activeZombies());
        assertEquals(1L, supervisor.snapshot().lateConvergences());
    }

    @Test
    void duplicateRegistrationOfTheSameGenerationIsIdempotent() {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        supervisor.track("session", 3L, pending, null, null);
        supervisor.track("session", 3L, pending, null, null);

        assertEquals(1, supervisor.snapshot().activeZombies());
    }

    @Test
    void differentTypedSessionIdentitiesKeepGenerationsIndependent() {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        supervisor.track("session-1", 3L, pending, null, null);
        supervisor.track("session-2", 3L, pending, null, null);

        assertEquals(2, supervisor.snapshot().activeZombies());
    }

    @Test
    void restartStateDistinguishesFailureFromAStoppedInstance() {
        assertTrue(VideoDecoderRestartState.CLOSING.pinsRegistryEntry());
        assertTrue(VideoDecoderRestartState.FAILED_CLOSE.pinsRegistryEntry());
        assertFalse(VideoDecoderRestartState.ACTIVE.pinsRegistryEntry());
        assertTrue(VideoDecoderRestartState.FAILED_CLOSE.isTerminalFailure());
        assertFalse(VideoDecoderRestartState.STOPPED.isTerminalFailure());
    }

    @Test
    void exceptionalPhysicalCloseRemainsAnActiveZombie() {
        VideoZombieCloseSupervisor supervisor = new VideoZombieCloseSupervisor();
        CompletableFuture<Void> nativeTermination = new CompletableFuture<>();
        nativeTermination.completeExceptionally(new IllegalStateException("native handle still open"));

        supervisor.track("session-exceptional", 2L,
                CompletableFuture.completedFuture(null), nativeTermination,
                CompletableFuture.completedFuture(null));

        assertEquals(1, supervisor.snapshot().activeZombies());
        assertEquals(0L, supervisor.snapshot().lateConvergences());
    }
}
