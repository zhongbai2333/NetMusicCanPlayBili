package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoConsumerRegistryTest {
    @Test
    void replacementRemovesStaleProjectorsAndFiltersNullsAndDuplicates() {
        VideoConsumerRegistry<String> registry = new VideoConsumerRegistry<>();
        registry.replaceProjectors(List.of("stale"));
        ArrayList<String> replacements = new ArrayList<>();
        replacements.add("projector-1");
        replacements.add(null);
        replacements.add("projector-1");
        replacements.add("projector-2");

        registry.replaceProjectors(replacements);

        assertFalse(registry.containsProjector("stale"));
        assertEquals(List.of("projector-1", "projector-2"), registry.projectors());
        assertEquals(2, registry.projectorCount());
    }

    @Test
    void addRemoveAndContainsTrackProjectorDetach() {
        VideoConsumerRegistry<String> registry = new VideoConsumerRegistry<>();
        registry.addProjector("projector");
        registry.addProjector("projector");
        registry.addProjector(null);

        assertTrue(registry.containsProjector("projector"));
        assertEquals(1, registry.projectorCount());

        registry.removeProjector("projector");
        registry.removeProjector(null);
        assertFalse(registry.hasProjectors());
    }

    @Test
    void guiConsumerRemainsIndependentFromProjectors() {
        VideoConsumerRegistry<String> registry = new VideoConsumerRegistry<>();
        registry.setGuiConsumer(true);

        assertTrue(registry.hasGuiConsumer());
        assertTrue(registry.hasDirectConsumer());
        assertFalse(registry.hasProjectors());

        registry.addProjector("projector");
        registry.setGuiConsumer(false);
        assertFalse(registry.hasGuiConsumer());
        assertTrue(registry.hasDirectConsumer());

        registry.removeProjector("projector");
        assertFalse(registry.hasDirectConsumer());
    }

    @Test
    void projectorSnapshotsAreImmutableAndStableAcrossReplacement() {
        VideoConsumerRegistry<String> registry = new VideoConsumerRegistry<>();
        registry.replaceProjectors(List.of("first", "second"));
        List<String> snapshot = registry.projectors();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("third"));
        registry.replaceProjectors(List.of("replacement"));

        assertEquals(List.of("first", "second"), snapshot);
        assertEquals(List.of("replacement"), registry.projectors());
    }
}
