package com.zhongbai233.net_music_can_play_bili.network;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Queue progression, duplicate occurrence identity and shuffle selection. */
class MP4PlaybackQueuePolicyTest {

    @Test
    void reconciliationStopsAndClampsSelectionWhenTheTrackWasRemoved() {
        MP4PlaybackQueuePolicy.Reconciliation result = MP4PlaybackQueuePolicy.reconcile(
                8, "removed", List.of("first", "second", "third"));
        MP4PlaybackQueuePolicy.Reconciliation empty = MP4PlaybackQueuePolicy.reconcile(
                8, "removed", null);

        assertEquals(MP4PlaybackQueuePolicy.ReconcileAction.STOP, result.action());
        assertEquals(2, result.selectedIndex());
        assertEquals(MP4PlaybackQueuePolicy.ReconcileAction.STOP, empty.action());
        assertEquals(0, empty.selectedIndex());
    }

    @Test
    void listRepeatWrapsButNormalAndEmptyQueuesStop() {
        MP4PlaybackQueuePolicy.Completion wrapped = MP4PlaybackQueuePolicy.completion(2, 3, 2);
        MP4PlaybackQueuePolicy.Completion stopped = MP4PlaybackQueuePolicy.completion(2, 3, 0);
        MP4PlaybackQueuePolicy.Completion empty = MP4PlaybackQueuePolicy.completion(0, 0, 2);

        assertTrue(wrapped.shouldAdvance());
        assertEquals(0, wrapped.nextIndex());
        assertFalse(stopped.shouldAdvance());
        assertEquals(-1, stopped.nextIndex());
        assertFalse(empty.shouldAdvance());
    }

    @Test void appendPreservesSecondOccurrenceAndCompletionGoesToAppendedTrack() {
        var result = MP4PlaybackQueuePolicy.reconcile(2, "A", List.of("A", "B", "A", "C"), -1);
        assertEquals(MP4PlaybackQueuePolicy.ReconcileAction.KEEP, result.action());
        assertEquals(2, result.selectedIndex());
        assertEquals(3, MP4PlaybackQueuePolicy.completion(result.selectedIndex(), 4, 0).nextIndex());
    }

    @Test void shuffleUsesEveryOtherOccurrenceAndContinuesAtTheEnd() {
        for (int current = 0; current < 4; current++) {
            var selected = new java.util.HashSet<Integer>();
            for (int draw = 0; draw < 3; draw++) {
                var completion = MP4PlaybackQueuePolicy.completion(current, 4, 0, true, draw);
                assertTrue(completion.shouldAdvance());
                assertNotEquals(current, completion.nextIndex());
                selected.add(completion.nextIndex());
                assertEquals(completion.nextIndex(), MP4PlaybackQueuePolicy.manualMove(current, 4, 1, true, draw));
            }
            assertEquals(3, selected.size());
        }
    }
    @Test void oneTrackShuffleLoopsAndEmptyShuffleStops() {
        assertEquals(0, MP4PlaybackQueuePolicy.completion(0, 1, 0, true, -1).nextIndex());
        assertFalse(MP4PlaybackQueuePolicy.completion(0, 0, 0, true, 1).shouldAdvance());
    }
    @Test void singleRepeatWinsOverPreviouslyEnabledShuffle() {
        assertEquals(2, MP4PlaybackQueuePolicy.completion(2,4,1,true,0).nextIndex());
    }
}
