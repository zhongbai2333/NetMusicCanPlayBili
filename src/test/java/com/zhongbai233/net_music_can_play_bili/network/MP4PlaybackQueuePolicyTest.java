package com.zhongbai233.net_music_can_play_bili.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MP4PlaybackQueuePolicyTest {
    @Test
    void reconciliationKeepsTheCurrentTrackAtTheSameIndex() {
        MP4PlaybackQueuePolicy.Reconciliation result = MP4PlaybackQueuePolicy.reconcile(
                1, "second", List.of("first", "second", "third"));

        assertEquals(MP4PlaybackQueuePolicy.ReconcileAction.KEEP, result.action());
        assertEquals(1, result.selectedIndex());
    }

    @Test
    void reconciliationRemapsTheCurrentTrackToItsFirstNewIndex() {
        MP4PlaybackQueuePolicy.Reconciliation result = MP4PlaybackQueuePolicy.reconcile(
                2, "current", List.of("current", "other", "current"));

        assertEquals(MP4PlaybackQueuePolicy.ReconcileAction.REMAP, result.action());
        assertEquals(0, result.selectedIndex());
    }

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
    void singleTrackRepeatReplaysTheClampedCurrentIndex() {
        MP4PlaybackQueuePolicy.Completion completion = MP4PlaybackQueuePolicy.completion(8, 3, 1);

        assertTrue(completion.shouldAdvance());
        assertEquals(2, completion.nextIndex());
    }

    @Test
    void normalCompletionAdvancesToTheNextTrack() {
        MP4PlaybackQueuePolicy.Completion completion = MP4PlaybackQueuePolicy.completion(1, 3, 0);

        assertTrue(completion.shouldAdvance());
        assertEquals(2, completion.nextIndex());
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
}
