package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveRoomMetadataRegistryTest {
    private static final LiveRoomMetadataRegistry.SourceKey SOURCE =
            new LiveRoomMetadataRegistry.SourceKey(1, 2, 3);

    @AfterEach
    void clear() {
        LiveRoomMetadataRegistry.clear();
    }

    @Test
    void replacementIsSessionOwnedAndOldCleanupCannotEraseNewMetadata() {
        PlaybackSessionId oldSession = PlaybackSessionId.of("live-old");
        PlaybackSessionId newSession = PlaybackSessionId.of("live-new");
        LiveRoomMetadataRegistry.publish(SOURCE, oldSession, "6", "旧标题", "赛事", "游戏赛事", 1);
        LiveRoomMetadataRegistry.publish(SOURCE, newSession, "6", "新标题", "赛事", "游戏赛事", 2);

        assertFalse(LiveRoomMetadataRegistry.remove(SOURCE, oldSession));
        assertEquals("新标题", LiveRoomMetadataRegistry.snapshot(SOURCE, "6").orElseThrow().title());
        assertTrue(LiveRoomMetadataRegistry.remove(SOURCE, newSession));
        assertEquals(0, LiveRoomMetadataRegistry.size());
    }

    @Test
    void roomReplacementRejectsStaleRoomSnapshot() {
        LiveRoomMetadataRegistry.publish(SOURCE, PlaybackSessionId.of("live-room"), "7734200",
                "标题", "赛事", "游戏赛事", 1);

        assertTrue(LiveRoomMetadataRegistry.snapshot(SOURCE, "7734200").isPresent());
        assertTrue(LiveRoomMetadataRegistry.snapshot(SOURCE, "999").isEmpty());
    }
}
