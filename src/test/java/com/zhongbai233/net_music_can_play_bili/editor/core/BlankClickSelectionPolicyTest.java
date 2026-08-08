package com.zhongbai233.net_music_can_play_bili.editor.core;

import com.zhongbai233.net_music_can_play_bili.editor.core.selection.BlankClickSelectionPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlankClickSelectionPolicyTest {
    @Test
    void blankLeftClickDeselectsButCameraDragKeepsSelection() {
        assertTrue(BlankClickSelectionPolicy.shouldDeselect(true, 0, 100.0D, 100.0D, 101.0D, 101.0D));
        assertFalse(BlankClickSelectionPolicy.shouldDeselect(true, 0, 100.0D, 100.0D, 120.0D, 100.0D));
    }

    @Test
    void nonBlankAndRightClickNeverDeselect() {
        assertFalse(BlankClickSelectionPolicy.shouldDeselect(false, 0, 100.0D, 100.0D, 100.0D, 100.0D));
        assertFalse(BlankClickSelectionPolicy.shouldDeselect(true, 1, 100.0D, 100.0D, 100.0D, 100.0D));
    }
}