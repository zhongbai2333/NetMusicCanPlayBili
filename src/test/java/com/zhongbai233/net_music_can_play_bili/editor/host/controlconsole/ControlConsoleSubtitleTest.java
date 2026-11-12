package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.AiSubtitleText;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.TimedTextResolver;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Subtitle source fallback, timeline interpolation, live metadata and layout. */
class ControlConsoleSubtitleTest {
    @Test
    void resolvesAiTracksOnTheAuthoritativeMediaTick() {
        AiSubtitleText.LineLookup primary = track(20, "第一句", 60, "第二句");
        AiSubtitleText.LineLookup translation = track(20, "First", 60, "Second");

        AiSubtitleText.Lines beforeSecond = AiSubtitleText.resolve(
                primary, translation, "人工字幕", "Human", 59, true, "固定文本");
        assertEquals("第一句", beforeSecond.primary());
        assertEquals("First", beforeSecond.translation());
        assertFalse(beforeSecond.usedFallback());

        AiSubtitleText.Lines atSecond = AiSubtitleText.resolve(
                primary, translation, "人工字幕", "Human", 60, true, "固定文本");
        assertEquals("第二句", atSecond.primary());
        assertEquals("Second", atSecond.translation());
        assertFalse(atSecond.usedFallback());
    }

    @Test
    void translationCanBeDisabledWithoutHidingThePrimaryTrack() {
        AiSubtitleText.Lines lines = AiSubtitleText.resolve(
                track(0, "AI 主轨"), track(0, "AI translation"),
                "人工字幕", "Human", 40, false, "固定文本");

        assertEquals("AI 主轨", lines.primary());
        assertEquals("", lines.translation());
        assertFalse(lines.usedFallback());
    }

    @Test
    void unavailableAiFallsBackToHumanLyricsThenFixedText() {
        AiSubtitleText.Lines human = AiSubtitleText.resolve(
                null, null, "人工字幕", "Human translation", 40, true, "固定文本");
        assertEquals("人工字幕", human.primary());
        assertEquals("Human translation", human.translation());
        assertTrue(human.usedFallback());

        AiSubtitleText.Lines fixed = AiSubtitleText.resolve(
                null, null, "", "", 40, true, "固定文本");
        assertEquals("固定文本", fixed.primary());
        assertEquals("", fixed.translation());
        assertTrue(fixed.usedFallback());
    }

    @Test
    void missingTimelineNeverReadsAiButKeepsSafeFallbacks() {
        AiSubtitleText.Lines lines = AiSubtitleText.resolve(
                track(0, "不得提前显示"), track(0, "Must not render"),
                "人工字幕", "Human", -1, true, "固定文本");

        assertEquals("人工字幕", lines.primary());
        assertEquals("Human", lines.translation());
        assertTrue(lines.usedFallback());
    }

    private static AiSubtitleText.LineLookup track(Object... values) {
        NavigableMap<Integer, String> result = new TreeMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put((Integer) values[i], (String) values[i + 1]);
        }
        return tick -> {
            var entry = result.floorEntry(tick);
            return entry != null ? entry.getValue() : result.firstEntry().getValue();
        };
    }

    @Test
    void resolvesLatestLineAtOrBeforePlaybackTick() {
        int[] ticks = {20, 60};

        assertEquals(20, TimedTextResolver.keyAt(ticks, 20));
        assertEquals(20, TimedTextResolver.keyAt(ticks, 59));
        assertEquals(60, TimedTextResolver.keyAt(ticks, 60));
        assertEquals(60, TimedTextResolver.keyAt(ticks, 200));
    }

    @Test
    void handlesUnavailableTimelineAndIntroBeforeFirstTimestamp() {
        int[] ticks = {20};

        assertEquals(Integer.MIN_VALUE, TimedTextResolver.keyAt(ticks, -1));
        assertEquals(20, TimedTextResolver.keyAt(ticks, 0));
        assertEquals(Integer.MIN_VALUE, TimedTextResolver.keyAt(null, 20));
    }

    @Test
    void scrollProgressIsCompleteBeforeFirstLineAndAtLastLine() {
        assertEquals(1.0F, TimedTextResolver.scrollProgress(20, 60, 10));
        assertEquals(0.0F, TimedTextResolver.scrollProgress(20, 60, 20));
        assertEquals(1.0F, TimedTextResolver.scrollProgress(20, 60, 60));
        assertEquals(1.0F, TimedTextResolver.scrollProgress(60, -1, 80));
    }

    @Test
    void continuousTickProducesSmoothSubTickProgress() {
        float atStart = TimedTextResolver.scrollProgress(20, 60, 20.0F);
        float afterTenMillis = TimedTextResolver.scrollProgress(20, 60, 20.2F);
        float afterTwentyMillis = TimedTextResolver.scrollProgress(20, 60, 20.4F);

        assertTrue(afterTenMillis > atStart);
        assertTrue(afterTwentyMillis > afterTenMillis);
    }

}
