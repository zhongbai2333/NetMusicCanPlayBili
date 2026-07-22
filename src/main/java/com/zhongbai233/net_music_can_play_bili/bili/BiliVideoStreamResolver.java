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
        BiliApiClient.VideoStreamPlan plan = BiliApiClient.getVideoStreamPlan(selection.videoId(), info.cid(),
                qualityCeiling);
        BiliApiClient.VideoStream stream = plan.preferred();
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
                subtitle,
                stream.codecId() == BiliApiClient.CODEC_AV1 ? DecodeMode.HARDWARE_REQUIRED : DecodeMode.AUTO,
                buildCandidates(plan, fallbackFps));
    }

    private static java.util.List<VideoCandidate> buildCandidates(BiliApiClient.VideoStreamPlan plan,
            int fallbackFps) {
        java.util.List<VideoCandidate> candidates = new java.util.ArrayList<>();
        plan.av1Candidates().forEach(stream -> candidates.add(toCandidate(stream, fallbackFps,
                DecodeMode.HARDWARE_REQUIRED)));
        plan.h264Candidates().forEach(stream -> candidates.add(toCandidate(stream, fallbackFps, DecodeMode.AUTO)));
        plan.softwareAv1Candidates().forEach(stream -> candidates.add(toCandidate(stream, fallbackFps,
                DecodeMode.SOFTWARE_ONLY)));
        return java.util.List.copyOf(candidates);
    }

    private static VideoCandidate toCandidate(BiliApiClient.VideoStream stream, int fallbackFps, DecodeMode mode) {
        return new VideoCandidate(stream.baseUrl(), stream.codecId(), Math.max(1, stream.width()),
                Math.max(1, stream.height()), parseFrameRate(stream.frameRate(), fallbackFps), stream.quality(), mode);
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

    public enum DecodeMode {
        HARDWARE_REQUIRED,
        AUTO,
        SOFTWARE_ONLY
    }

    public record VideoCandidate(String url, int codecId, int sourceWidth, int sourceHeight, int fps, int quality,
            DecodeMode decodeMode) {
        public VideoCandidate(String url, int codecId, int sourceWidth, int sourceHeight, int fps, int quality) {
            this(url, codecId, sourceWidth, sourceHeight, fps, quality, DecodeMode.AUTO);
        }
    }

    public record ResolvedVideoStream(String url, int codecId, int sourceWidth, int sourceHeight, int fps,
            int quality, String title, LyricRecord subtitleRecord, DecodeMode decodeMode,
            java.util.List<VideoCandidate> candidates) {
        public ResolvedVideoStream {
            candidates = candidates == null || candidates.isEmpty()
                    ? java.util.List.of(new VideoCandidate(url, codecId, sourceWidth, sourceHeight, fps, quality))
                    : java.util.List.copyOf(candidates);
        }

        public ResolvedVideoStream withCandidate(VideoCandidate candidate) {
            return new ResolvedVideoStream(candidate.url(), candidate.codecId(), candidate.sourceWidth(),
                    candidate.sourceHeight(), candidate.fps(), candidate.quality(), title, subtitleRecord,
                    candidate.decodeMode(), candidates);
        }
    }
}
