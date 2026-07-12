package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.lyric.LyricParser;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliAudioResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSubtitleLyricService;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import org.slf4j.Logger;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客户端媒体播放准备器。
 *
 * <p>
 * 现代化唱片机是稳定播放主线路；B站直链解析采用 MP4 侧的新线路，统一提供给
 * 现代化唱片机、MP4 和普通 NetMusic 接管播放使用，避免两边各修一份 CDN/歌词逻辑。
 * </p>
 */
public final class ClientMediaPreparer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern NET_EASE_MP3_URL = Pattern.compile("^.*?\\?id=(\\d+)\\.mp3$");

    private ClientMediaPreparer() {
    }

    public static PreparedMedia prepare(String rawUrl, String playUrl, String songName, boolean allowDolby,
            boolean enableLyrics) {
        String preparedUrl = resolvePlayableUrl(rawUrl, playUrl, songName, allowDolby);
        LyricRecord lyricRecord = enableLyrics ? buildLyric(rawUrl, songName) : null;
        return new PreparedMedia(preparedUrl, lyricRecord);
    }

    public static PreparedMedia prepareAudioOnly(String rawUrl, String playUrl, String songName, boolean allowDolby) {
        return new PreparedMedia(resolvePlayableUrl(rawUrl, playUrl, songName, allowDolby), null);
    }

    public static CompletableFuture<LyricRecord> buildLyricAsync(String rawUrl, String songName) {
        return CompletableFuture.supplyAsync(() -> buildLyric(rawUrl, songName));
    }

    public static boolean hasStoredBiliSelection(String rawUrl, String playUrl) {
        return storedBiliSelection(rawUrl, playUrl) != null;
    }

    public static String resolvePlayableUrl(String rawUrl, String playUrl, String songName, boolean allowDolby) {
        String storedSelection = storedBiliSelection(rawUrl, playUrl);
        if (storedSelection == null) {
            return playUrl;
        }

        try {
            String resolvedUrl = BiliAudioResolver.resolvePlayableUrl(storedSelection, allowDolby);
            String syncedUrl = PlaybackSync.transferSync(playUrl, resolvedUrl);
            LOGGER.debug("客户端刷新 B站 直链: song='{}' host={} stored={} allowDolby={}", songName,
                    hostOf(syncedUrl), storedSelection, allowDolby);
            return syncedUrl;
        } catch (Exception e) {
            NetMusic.LOGGER.error("B站客户端本地解析播放直链失败: {}", storedSelection, e);
            return playUrl;
        }
    }

    public static LyricRecord buildLyric(String rawUrl, String songName) {
        LyricRecord lyricRecord = tryBuildNetEaseLyric(rawUrl, songName);
        return lyricRecord != null ? lyricRecord : BiliSubtitleLyricService.tryBuildLyricRecord(rawUrl, songName);
    }

    public static String hostOf(String value) {
        try {
            String stripped = PlaybackSync.strip(value);
            String host = URI.create(stripped != null ? stripped : value).getHost();
            return host != null ? host : "unknown";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String storedBiliSelection(String rawUrl, String playUrl) {
        String cleanRawUrl = PlaybackSync.strip(rawUrl);
        String cleanPlayUrl = PlaybackSync.strip(playUrl);
        if (BiliApiClient.isStoredVideoSelection(cleanRawUrl)) {
            return cleanRawUrl;
        }
        if (BiliApiClient.isStoredVideoSelection(cleanPlayUrl)) {
            return cleanPlayUrl;
        }
        return null;
    }

    private static LyricRecord tryBuildNetEaseLyric(String rawUrl, String songName) {
        if (rawUrl == null || !rawUrl.startsWith("https://music.163.com/")) {
            return null;
        }
        Matcher matcher = NET_EASE_MP3_URL.matcher(rawUrl);
        if (!matcher.find()) {
            return null;
        }
        try {
            long songId = Long.parseLong(matcher.group(1));
            String lyricJson = NetMusic.NET_EASE_WEB_API.lyric(songId);
            return LyricParser.parseLyric(lyricJson, songName);
        } catch (Exception e) {
            NetMusic.LOGGER.error(e);
            return null;
        }
    }

    public record PreparedMedia(String playUrl, LyricRecord lyricRecord) {
    }
}
