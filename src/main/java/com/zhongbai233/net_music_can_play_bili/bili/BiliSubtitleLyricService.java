package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.api.lyric.LyricParser;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 将 B站 CC 字幕转换为 NetMusic 可用歌词。
 */
public final class BiliSubtitleLyricService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private BiliSubtitleLyricService() {
    }

    public static LyricRecord tryBuildLyricRecord(String rawInput, String songName) {
        BiliApiClient.VideoSelection selection = BiliApiClient.parseStoredVideoSelection(rawInput);
        if (selection == null) {
            return null;
        }

        try {
            BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());

            // 尝试获取 CC 字幕
            String lyricJson = BiliApiClient.getBilingualSubtitleAsNetEaseLyric(info);
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

            LOGGER.info("B站占位歌词: {} | hasAnySubtitle={} | sessdata={}",
                    info.displayTitle(), hasAnySubtitle, !BiliApiClient.sessdata.isBlank());
            String placeholderJson = BiliApiClient.buildPlaceholderNetEaseLyric(info, note);
            LyricRecord record = LyricParser.parseLyric(placeholderJson, songName);
            if (record != null) {
                return record;
            }
            return null;
        } catch (Exception e) {
            LOGGER.warn("B站 CC 字幕获取失败: {}", e.getMessage());
            return null;
        }
    }
}