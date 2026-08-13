package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingVideoSessionRegistryTest {
    @Test
    void repeatedLoadingRefreshPreservesTheOriginalStartTime() {
        AtomicLong clock = new AtomicLong(10L);
        PendingVideoSessionRegistry<String> registry = new PendingVideoSessionRegistry<>(clock::get);
        registry.beginLoading("session", List.of("projector-1"));
        clock.set(20L);
        registry.beginLoading("session", List.of("projector-2"));

        PendingVideoSessionRegistry.Snapshot<String> loading = registry.findByProjector(
                PendingVideoSessionRegistry.State.LOADING, "projector-2");
        assertEquals(PlaybackSessionId.of("session"), loading.playbackSessionId());
        assertEquals("session", loading.sessionId());
        assertEquals(10L, loading.startedNanoTime());
        assertEquals(List.of("projector-2"), loading.projectorPositions());
    }

    @Test
    void loadingAndFailureTransitionsRemainMutuallyExclusive() {
        AtomicLong clock = new AtomicLong(10L);
        PendingVideoSessionRegistry<String> registry = new PendingVideoSessionRegistry<>(clock::get);
        registry.beginLoading("session", List.of("projector"));
        clock.set(20L);
        registry.markFailure("session", List.of("projector"));

        assertEquals(0, registry.count(PendingVideoSessionRegistry.State.LOADING));
        assertEquals(1, registry.count(PendingVideoSessionRegistry.State.FAILURE));
        assertTrue(registry.hasFailure("session"));

        clock.set(30L);
        registry.beginLoading("session", List.of("projector"));
        PendingVideoSessionRegistry.Snapshot<String> loading = registry.findByProjector(
                PendingVideoSessionRegistry.State.LOADING, "projector");
        assertEquals(30L, loading.startedNanoTime());
        assertFalse(registry.hasFailure("session"));
    }

    @Test
    void clearLoadingPreservesFailureUntilTheWholeSessionIsCleared() {
        PendingVideoSessionRegistry<String> registry = new PendingVideoSessionRegistry<>(() -> 10L);
        registry.markFailure("session", List.of("projector"));

        registry.clearLoading("session");
        assertTrue(registry.hasFailure("session"));

        registry.clearSession("session");
        assertFalse(registry.hasFailure("session"));
        assertEquals(0, registry.count(PendingVideoSessionRegistry.State.FAILURE));
    }

    @Test
    void detachingAProjectorUpdatesBothStatesAndRemovesEmptySessions() {
        PendingVideoSessionRegistry<String> registry = new PendingVideoSessionRegistry<>(() -> 10L);
        registry.beginLoading("loading", List.of("shared", "retained"));
        registry.markFailure("failure", List.of("shared"));

        registry.detachProjector("shared");

        assertNull(registry.findByProjector(PendingVideoSessionRegistry.State.FAILURE, "shared"));
        assertEquals(0, registry.count(PendingVideoSessionRegistry.State.FAILURE));
        PendingVideoSessionRegistry.Snapshot<String> loading = registry.findByProjector(
                PendingVideoSessionRegistry.State.LOADING, "retained");
        assertEquals(List.of("retained"), loading.projectorPositions());
    }

    @Test
    void projectorUpdatesPreserveStateAndStartTimeEvenWhenTheListBecomesEmpty() {
        PendingVideoSessionRegistry<String> registry = new PendingVideoSessionRegistry<>(() -> 10L);
        registry.markFailure("session", List.of("projector"));

        registry.updateProjectors("session", List.of());

        assertTrue(registry.hasFailure("session"));
        assertEquals(1, registry.count(PendingVideoSessionRegistry.State.FAILURE));
        assertNull(registry.findByProjector(PendingVideoSessionRegistry.State.FAILURE, "projector"));
    }
}
