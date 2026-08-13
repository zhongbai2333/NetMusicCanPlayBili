package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPreviewSessionStateTest {
    @Test
    void beginPublishesOneNormalizedImmutableSnapshot() {
        LegacyPreviewSessionState<String, String> state = new LegacyPreviewSessionState<>();
        ArrayList<String> projectors = new ArrayList<>();
        projectors.add("projector-1");
        projectors.add(null);
        projectors.add("projector-1");
        projectors.add("projector-2");

        state.begin("session", projectors, "request");
        LegacyPreviewSessionState.Snapshot<String, String> snapshot = state.snapshot();

        assertEquals(PlaybackSessionId.of("session"), snapshot.playbackSessionId().orElseThrow());
        assertEquals("session", snapshot.sessionId());
        assertEquals(List.of("projector-1", "projector-2"), snapshot.projectors());
        assertEquals("projector-1", snapshot.primaryProjector());
        assertTrue(snapshot.requiresProjector());
        assertEquals("request", snapshot.request());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.projectors().add("projector-3"));
    }

    @Test
    void replacementRemovesStaleBindingsAndKeepsPriorSnapshotsStable() {
        LegacyPreviewSessionState<String, String> state = new LegacyPreviewSessionState<>();
        state.begin("session", List.of("stale", "retained"), "request");
        LegacyPreviewSessionState.Snapshot<String, String> previous = state.snapshot();

        state.replaceProjectors(List.of("replacement"));

        assertEquals(List.of("stale", "retained"), previous.projectors());
        assertEquals(List.of("replacement"), state.snapshot().projectors());
        assertEquals("replacement", state.snapshot().primaryProjector());
        assertEquals("request", state.snapshot().request());
    }

    @Test
    void detachingPrimaryPromotesTheNextProjectorAndLastDetachRemovesRequirement() {
        LegacyPreviewSessionState<String, String> state = new LegacyPreviewSessionState<>();
        state.begin("session", List.of("first", "second"), "request");

        state.detachProjector("first");
        assertEquals(List.of("second"), state.snapshot().projectors());
        assertEquals("second", state.snapshot().primaryProjector());
        assertTrue(state.snapshot().requiresProjector());

        state.detachProjector("second");
        assertTrue(state.snapshot().projectors().isEmpty());
        assertNull(state.snapshot().primaryProjector());
        assertFalse(state.snapshot().requiresProjector());
    }

    @Test
    void pruningInvalidProjectorsRepairsThePrimaryBinding() {
        LegacyPreviewSessionState<String, String> state = new LegacyPreviewSessionState<>();
        state.begin("session", List.of("invalid", "valid"), "request");

        state.removeProjectorsIf("invalid"::equals);

        assertEquals(List.of("valid"), state.snapshot().projectors());
        assertEquals("valid", state.snapshot().primaryProjector());
    }

    @Test
    void replacementClearRetainsRetryIdentityWhileFullClearRemovesIt() {
        LegacyPreviewSessionState<String, String> state = new LegacyPreviewSessionState<>();
        state.begin("session", List.of("projector"), "request");

        state.clearForReplacement();
        assertTrue(state.matchesSession("session"));
        assertEquals("request", state.snapshot().request());
        assertTrue(state.snapshot().projectors().isEmpty());
        assertFalse(state.snapshot().requiresProjector());

        state.clear();
        assertTrue(state.matchesSession(null));
        assertNull(state.snapshot().request());
        assertNull(state.snapshot().primaryProjector());
    }
}
