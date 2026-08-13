package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.SubtitleLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubtitleLayoutTest {
    @Test
    void alignsSplitsAndMultipliesAlphaDeterministically() {
        assertEquals(0.0F, SubtitleLayout.x(ControlConsoleElement.Alignment.LEFT, 80));
        assertEquals(-40.0F, SubtitleLayout.x(ControlConsoleElement.Alignment.CENTER, 80));
        assertEquals(-80.0F, SubtitleLayout.x(ControlConsoleElement.Alignment.RIGHT, 80));
        assertEquals(Integer.MAX_VALUE, SubtitleLayout.splitWidth(120.0F, false));
        assertEquals(Integer.MAX_VALUE, SubtitleLayout.splitWidth(0.0F, true));
        assertEquals(120, SubtitleLayout.splitWidth(120.9F, true));
        assertEquals(0x20406080, SubtitleLayout.multiplyAlpha(0x40406080, 0.5F));
        assertEquals(0x00406080, SubtitleLayout.multiplyAlpha(0x40406080, Float.NaN));
        assertEquals(0.025F, SubtitleLayout.WORLD_TEXT_SCALE);
        assertEquals(1.0F, SubtitleLayout.scrollLineScale(0.0F));
        assertEquals(0.78F, SubtitleLayout.scrollLineScale(1.0F), 0.0001F);
        assertEquals(0.56F, SubtitleLayout.scrollLineScale(2.0F), 0.0001F);
        assertEquals(0.56F, SubtitleLayout.scrollLineScale(Float.NaN), 0.0001F);
        assertEquals(false, SubtitleLayout.isScrollingMode("LYRICS"));
        assertEquals(true, SubtitleLayout.isScrollingMode("SCROLL_MAIN"));
        assertEquals(true, SubtitleLayout.isScrollingMode("SCROLL_TRANSLATION"));
        assertEquals("SCROLL_MAIN", SubtitleLayout.nextDisplayMode("LYRICS"));
        assertEquals("FIXED", SubtitleLayout.nextDisplayMode("SCROLL_MAIN"));
        assertEquals("FIXED", SubtitleLayout.nextDisplayMode("SCROLL_TRANSLATION"));
        assertEquals("AI_SUBTITLE", SubtitleLayout.nextDisplayMode("FIXED"));
        assertEquals("LIVE_TITLE", SubtitleLayout.nextDisplayMode("AI_SUBTITLE"));
        assertEquals("LIVE_ROOM", SubtitleLayout.nextDisplayMode("LIVE_TITLE"));
        assertEquals("LIVE_STATUS", SubtitleLayout.nextDisplayMode("LIVE_ROOM"));
        assertEquals("LYRICS", SubtitleLayout.nextDisplayMode("LIVE_STATUS"));
        assertEquals("SCROLL_TRANSLATION", SubtitleLayout.toggleScrollingTrack("SCROLL_MAIN"));
        assertEquals("SCROLL_MAIN", SubtitleLayout.toggleScrollingTrack("SCROLL_TRANSLATION"));
        assertEquals("LYRICS", SubtitleLayout.toggleScrollingTrack("LYRICS"));
    }
}
