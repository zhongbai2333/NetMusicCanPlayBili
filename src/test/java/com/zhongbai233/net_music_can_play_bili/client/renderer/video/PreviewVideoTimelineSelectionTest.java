package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewVideoTimelineSelectionTest {
    @Test
    void registryTimelineIsUsedOnlyForMatchingPreviewWithoutAudio() {
        PlaybackSessionId expected = PlaybackSessionId.of("preview-without-audio");

        assertTrue(PreviewVideoTimelineSelection.useRegistryTimeline(
                expected, false, Optional.of(expected), 1_000L));
        assertFalse(PreviewVideoTimelineSelection.useRegistryTimeline(
                expected, true, Optional.of(expected), 1_000L));
        assertFalse(PreviewVideoTimelineSelection.useRegistryTimeline(
                expected, false, Optional.of(PlaybackSessionId.of("other")), 1_000L));
        assertFalse(PreviewVideoTimelineSelection.useRegistryTimeline(
                expected, false, Optional.of(expected), -1L));
    }
}
