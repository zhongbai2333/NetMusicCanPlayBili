package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.LiveSubtitleMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveSubtitleMetadataTest {
    @Test
    void resolvesSharedTitleRoomAndStatusText() {
        LiveSubtitleMetadata.Metadata metadata = LiveSubtitleMetadata.resolve("7734200", "赛事直播",
                "赛事", "游戏赛事", 2, true, false);

        assertEquals("赛事直播", LiveSubtitleMetadata.text("LIVE_TITLE", metadata));
        assertEquals("房间 7734200 · 赛事 / 游戏赛事",
                LiveSubtitleMetadata.text("LIVE_ROOM", metadata));
        assertEquals("轮播中", LiveSubtitleMetadata.text("LIVE_STATUS", metadata));
        assertTrue(LiveSubtitleMetadata.isLiveMode("LIVE_STATUS"));
    }

    @Test
    void providesStableFallbacksBeforeMetadataArrives() {
        LiveSubtitleMetadata.Metadata waiting = LiveSubtitleMetadata.resolve("6", "", "", "",
                -1, false, true);

        assertEquals("B站直播 6", LiveSubtitleMetadata.text("LIVE_TITLE", waiting));
        assertEquals("房间 6", LiveSubtitleMetadata.text("LIVE_ROOM", waiting));
        assertEquals("等待开播", LiveSubtitleMetadata.text("LIVE_STATUS", waiting));
        assertEquals("", LiveSubtitleMetadata.text("LYRICS", waiting));
    }
}
