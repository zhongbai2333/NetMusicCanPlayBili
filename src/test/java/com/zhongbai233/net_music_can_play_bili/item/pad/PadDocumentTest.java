package com.zhongbai233.net_music_can_play_bili.item.pad;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadDocumentTest {
    @Test
    void togglesLockWithoutForkingContent() {
        List<String> mediaEntries = List.of("media");
        List<String> triggerPoints = List.of("trigger");

        PadDocumentLockPolicy.LockCopy<String, String> locked = PadDocumentLockPolicy.transition(
                false, true, 100L, 7L, mediaEntries, triggerPoints, true, 200L);
        assertTrue(locked.locked());
        assertEquals(8L, locked.sequence());
        assertEquals(200L, locked.updatedAtMillis());
        assertSame(mediaEntries, locked.mediaEntries());
        assertSame(triggerPoints, locked.triggerPoints());

        PadDocumentLockPolicy.LockCopy<String, String> unlocked = PadDocumentLockPolicy.transition(
                true, false, locked.updatedAtMillis(), locked.sequence(),
                locked.mediaEntries(), locked.triggerPoints(), true, 300L);
        assertFalse(unlocked.locked());
        assertTrue(unlocked.sequence() > locked.sequence());

        PadDocumentLockPolicy.LockCopy<String, String> mirroredDraft = PadDocumentLockPolicy.transition(
                true, false, locked.updatedAtMillis(), locked.sequence(),
                locked.mediaEntries(), locked.triggerPoints(), false, 0L);
        assertFalse(mirroredDraft.locked());
        assertEquals(locked.sequence(), mirroredDraft.sequence());
        assertEquals(locked.updatedAtMillis(), mirroredDraft.updatedAtMillis());
        assertSame(locked.mediaEntries(), mirroredDraft.mediaEntries());
        assertSame(locked.triggerPoints(), mirroredDraft.triggerPoints());
    }
}