package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.api.lyric.LyricParser;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import org.slf4j.Logger;

/**
 * 将 B站 CC 字幕转换为 NetMusic 可用歌词。
 */
public final class BiliSubtitleLyricService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private BiliSubtitleLyricService() {
    }

    public static LyricRecord tryBuildLyricRecord(String rawInput, String songName) {
        return tryBuildLyricRecord(rawInput, songName, false);
    }

    public static LyricRecord tryBuildLyricRecord(String rawInput, String songName, boolean allowAi) {
        BiliApiClient.VideoSelection selection = BiliApiClient.parseStoredVideoSelection(PlaybackSync.strip(rawInput));
        if (selection == null) {
            return null;
        }

        try {
            BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());

            // 尝试获取 CC 字幕
            String lyricJson = BiliApiClient.getBilingualSubtitleAsNetEaseLyric(info, allowAi);
            if (lyricJson != null && !lyricJson.isBlank()) {
                LyricRecord record = LyricParser.parseLyric(lyricJson, songName);
                if (record != null) {
                    return record;
                }
                LOGGER.warn("B站 CC 字幕解析失败：LyricParser 返回 null");
            }

            boolean hasAnySubtitle = false;
            try {
                java.util.List<BiliApiClient.SubtitleInfo> rawSubs = BiliApiClient.getAllSubtitles(info);
                hasAnySubtitle = rawSubs != null && !rawSubs.isEmpty();
            } catch (Exception ignored) {
            }

            String note;
            if (BiliApiClient.sessdata.isBlank()) {
                note = "字幕需登录B站账号";
            } else if (hasAnySubtitle) {
                note = "无可用CC字幕";
            } else {
                note = "无CC字幕";
            }

            String placeholderJson = BiliApiClient.buildPlaceholderNetEaseLyric(info, note);
            LyricRecord record = LyricParser.parseLyric(placeholderJson, songName);
            if (record != null) {
                LOGGER.debug(
                        "B站字幕摘要: title='{}' page={} allowAi={} result=placeholder reason={} hasAnySubtitle={} sessdata={}",
                        info.displayTitle(), info.page(), allowAi, note, hasAnySubtitle,
                        !BiliApiClient.sessdata.isBlank());
                return record;
            }
            return null;
        } catch (Exception e) {
            LOGGER.warn("B站 CC 字幕获取失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetches only a Bilibili AI-generated CC track. Absence returns {@code null}; transport/parse failures are
     * propagated so the session owner can distinguish safe unavailability from a failed request.
     */
    public static LyricRecord buildAiLyricRecord(String rawInput, String songName) throws Exception {
        String stored = PlaybackSync.strip(rawInput);
        BiliApiClient.VideoSelection selection = BiliApiClient.parseStoredVideoSelection(stored);
        if (selection == null) {
            return null;
        }
        BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
        String lyricJson = BiliApiClient.getBilingualSubtitleAsNetEaseLyric(
                info, BiliApiClient.SubtitlePreference.AI_ONLY);
        if (lyricJson == null || lyricJson.isBlank()) {
            return null;
        }
        LyricRecord record = LyricParser.parseLyric(lyricJson, songName);
        if (record == null) {
            throw new IllegalStateException("B站 AI CC 字幕解析失败：LyricParser 返回 null");
        }
        return record;
    }
}
