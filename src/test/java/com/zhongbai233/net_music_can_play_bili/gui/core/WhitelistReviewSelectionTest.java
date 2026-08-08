package com.zhongbai233.net_music_can_play_bili.gui.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhitelistReviewSelectionTest {
    @Test
    void restoresExactSelectedIdAfterReturningFromPreview() {
        assertEquals(2, WhitelistReviewSelection.indexOf(
                List.of("bili:BV1", "url:https://one", "bili:BV3"), "bili:BV3"));
        assertEquals(-1, WhitelistReviewSelection.indexOf(List.of("bili:BV1"), "bili:missing"));
    }

    @Test
    void matchesStoredUrlAndBiliPagePreviewForms() {
        assertTrue(WhitelistReviewSelection.matchesPreview("url:https://example.test/a", "https://example.test/a"));
        assertTrue(WhitelistReviewSelection.matchesPreview("bili:BV1ABC", "BV1ABC|p=3"));
        assertTrue(WhitelistReviewSelection.matchesPreview("bili:BV1ABC", "BV1ABC"));
        assertFalse(WhitelistReviewSelection.matchesPreview("bili:BV1ABC", "BV1OTHER|p=3"));
    }
}