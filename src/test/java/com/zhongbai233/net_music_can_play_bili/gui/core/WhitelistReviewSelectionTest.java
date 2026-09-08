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

    @Test
    void selectsTheRowThatMovesIntoADeletedSelectionWithoutJumpingTheScroll() {
        WhitelistReviewSelection.RefreshState state = WhitelistReviewSelection.resolveAfterRefresh(
                List.of("a", "b", "c", "d", "e", "g", "h", "i"), "f", 5, 3, 4);

        assertEquals(5, state.selectedIndex());
        assertEquals(3, state.scrollOffset());
    }

    @Test
    void deletingTheLastSelectionFallsBackToItsPredecessorAndClampsTheScroll() {
        WhitelistReviewSelection.RefreshState state = WhitelistReviewSelection.resolveAfterRefresh(
                List.of("a", "b", "c"), "d", 3, 2, 2);

        assertEquals(2, state.selectedIndex());
        assertEquals(1, state.scrollOffset());
    }

    @Test
    void previewDeletionReturnsToTheNextSiblingThenFallsBackToThePreviousSibling() {
        List<String> ids = List.of("a", "b", "c");

        assertEquals("c", WhitelistReviewSelection.adjacentIdAfterRemoval(ids, "b"));
        assertEquals("b", WhitelistReviewSelection.adjacentIdAfterRemoval(ids, "c"));
        assertEquals("", WhitelistReviewSelection.adjacentIdAfterRemoval(List.of("a"), "a"));
        assertEquals("", WhitelistReviewSelection.adjacentIdAfterRemoval(ids, "missing"));
    }

    @Test
    void deletingTheOnlyRowOnTheLastPageFallsBackToThePreviousPage() {
        assertEquals(64, WhitelistReviewSelection.clampPageOffset(64, 65, 64));
        assertEquals(0, WhitelistReviewSelection.clampPageOffset(64, 64, 64));
        assertEquals(64, WhitelistReviewSelection.clampPageOffset(128, 128, 64));
        assertEquals(0, WhitelistReviewSelection.clampPageOffset(64, 0, 64));

        List<String> previousPage = java.util.stream.IntStream.range(0, 64)
                .mapToObj(index -> "entry-" + index)
                .toList();
        WhitelistReviewSelection.RefreshState restored = WhitelistReviewSelection.resolveAfterRefresh(
                previousPage, "deleted-last-page-entry", previousPage.size() - 1, 0, 7);
        assertEquals(63, restored.selectedIndex());
        assertEquals(57, restored.scrollOffset());
    }
}
