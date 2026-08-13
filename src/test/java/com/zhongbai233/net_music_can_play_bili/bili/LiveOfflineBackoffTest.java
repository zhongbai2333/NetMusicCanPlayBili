package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveOfflineBackoffTest {

    @Test
    void blocksRoomWithinBackoffWindowAndReleasesAfter() {
        String room = "backoff-room-1";
        LiveOfflineBackoff.clear(room);

        assertFalse(LiveOfflineBackoff.isBlocked(room, 1_000L));
        LiveOfflineBackoff.recordOffline(room, 1_000L);
        assertTrue(LiveOfflineBackoff.isBlocked(room, 1_000L + LiveOfflineBackoff.retryMillis() - 1L));
        assertFalse(LiveOfflineBackoff.isBlocked(room, 1_000L + LiveOfflineBackoff.retryMillis()));
        // 到期检查会顺带清除记录
        assertFalse(LiveOfflineBackoff.isBlocked(room, 1_000L));
    }

    @Test
    void clearRemovesBackoffImmediately() {
        String room = "backoff-room-2";
        LiveOfflineBackoff.recordOffline(room, 5_000L);
        assertTrue(LiveOfflineBackoff.isBlocked(room, 5_001L));
        LiveOfflineBackoff.clear(room);
        assertFalse(LiveOfflineBackoff.isBlocked(room, 5_001L));
    }

    @Test
    void blankRoomIdsAreNeverBlocked() {
        LiveOfflineBackoff.recordOffline(null, 1L);
        LiveOfflineBackoff.recordOffline("", 1L);
        assertFalse(LiveOfflineBackoff.isBlocked(null, 2L));
        assertFalse(LiveOfflineBackoff.isBlocked("", 2L));
    }

    @Test
    void deadlineSaturatesNearLongMaxValue() {
        String room = "backoff-room-overflow";
        long start = Long.MAX_VALUE - LiveOfflineBackoff.retryMillis() + 1L;
        LiveOfflineBackoff.clear(room);

        LiveOfflineBackoff.recordOffline(room, start);

        assertTrue(LiveOfflineBackoff.isBlocked(room, Long.MAX_VALUE - 1L));
        assertFalse(LiveOfflineBackoff.isBlocked(room, Long.MAX_VALUE));
    }
}
