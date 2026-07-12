package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;

/** B站视频流公共解析器，供手持设备、投影仪和预览界面复用。 */
public final class BiliVideoStreamResolver {
    public static final int DEFAULT_FPS = 30;

    private BiliVideoStreamResolver() {
    }

    public static boolean isStoredVideoSelection(String rawUrl) {
        return BiliApiClient.parseStoredVideoSelection(PlaybackSync.strip(rawUrl)) != null;
    }

    public static BiliApiClient.VideoSelection selectionOrNull(String rawUrl) {
        return BiliApiClient.parseStoredVideoSelection(PlaybackSync.strip(rawUrl));
    }

    public static ResolvedVideoStream resolve(String rawUrl, int qualityCeiling) throws Exception {
        return resolve(rawUrl, qualityCeiling, DEFAULT_FPS, "", false, false);
    }

    public static ResolvedVideoStream resolve(String rawUrl, int qualityCeiling, int fallbackFps) throws Exception {
        return resolve(rawUrl, qualityCeiling, fallbackFps, "", false, false);
    }

    public static ResolvedVideoStream resolveWithSubtitle(String rawUrl, int qualityCeiling, String title,
            boolean allowAiSubtitle) throws Exception {
        return resolve(rawUrl, qualityCeiling, DEFAULT_FPS, title, allowAiSubtitle, true);
    }

    private static ResolvedVideoStream resolve(String rawUrl, int qualityCeiling, int fallbackFps,
            String fallbackTitle, boolean allowAiSubtitle, boolean includeSubtitle) throws Exception {
        BiliApiClient.VideoSelection selection = selectionOrNull(rawUrl);
        if (selection == null) {
            throw new IllegalArgumentException("不是 B 站视频选择: " + rawUrl);
        }
        BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
        BiliApiClient.VideoStream stream = BiliApiClient.getBestVideoStream(selection.videoId(), info.cid(),
                qualityCeiling);
        String title = info.displayTitle() != null && !info.displayTitle().isBlank() ? info.displayTitle()
                : fallbackTitle;
        LyricRecord subtitle = includeSubtitle
                ? BiliSubtitleLyricService.tryBuildLyricRecord(rawUrl, title, allowAiSubtitle)
                : null;
        return new ResolvedVideoStream(
                stream.baseUrl(),
                stream.codecId(),
                Math.max(1, stream.width()),
                Math.max(1, stream.height()),
                parseFrameRate(stream.frameRate(), fallbackFps),
                stream.quality(),
                title,
                subtitle);
    }

    public static int parseFrameRate(String raw, int fallbackFps) {
        int fallback = Math.max(1, fallbackFps);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim();
        try {
            if (normalized.contains("/")) {
                String[] parts = normalized.split("/", 2);
                double numerator = Double.parseDouble(parts[0].trim());
                double denominator = Double.parseDouble(parts[1].trim());
                return denominator > 0.0D ? Math.max(1, (int) Math.round(numerator / denominator)) : fallback;
            }
            return Math.max(1, (int) Math.round(Double.parseDouble(normalized)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public record ResolvedVideoStream(String url, int codecId, int sourceWidth, int sourceHeight, int fps,
            int quality, String title, LyricRecord subtitleRecord) {
    }
}
