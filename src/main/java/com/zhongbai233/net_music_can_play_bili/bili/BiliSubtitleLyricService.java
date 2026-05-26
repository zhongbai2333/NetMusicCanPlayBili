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
            String lyricJson = BiliApiClient.getBilingualSubtitleAsNetEaseLyric(info);
            if (lyricJson == null || lyricJson.isBlank()) {
                return null;
            }
            LyricRecord record = LyricParser.parseLyric(lyricJson, songName);
            if (record == null) {
                LOGGER.warn("B站 CC 字幕解析失败：LyricParser 返回 null");
                return null;
            }
            return record;
        } catch (Exception e) {
            LOGGER.warn("B站 CC 字幕获取失败: {}", e.getMessage());
            return null;
        }
    }
}