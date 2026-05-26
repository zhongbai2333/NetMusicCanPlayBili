package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站 API 客户端：BV/AV 解析、视频信息、DASH 音频流地址
 */
public final class BiliApiClient {

    private static final Pattern BV_FULL_RE = Pattern.compile("^[Bb][Vv][0-9A-Za-z]{10}$");
    private static final Pattern AV_FULL_RE = Pattern.compile("^av(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STORED_SELECTION_RE = Pattern.compile(
            "^((?:[Bb][Vv][0-9A-Za-z]{10}|av\\d+))(?:\\|p=(\\d+))?$",
            Pattern.CASE_INSENSITIVE);
    private static final int[] QUALITY_ORDER = { 30251, 30250, 30280, 30232, 30216 };
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    /**
     * B站 SESSDATA Cookie，用于获取字幕等需要登录的功能。
     * 可在浏览器开发者工具 → Application → Cookies 中复制。
     * 不设置则字幕功能不可用（不影响音频播放）。
     */
    public static volatile String sessdata = System.getProperty("bili.sessdata", "");

    private BiliApiClient() {
    }

    // == 视频 ID 解析 ==

    public static boolean isBiliVideoId(String input) {
        if (input == null || input.isBlank())
            return false;

        String raw = input.trim();
        return BV_FULL_RE.matcher(raw).matches() || AV_FULL_RE.matcher(raw).matches();
    }

    // 从输入中提取视频 ID

    public static VideoId extractVideoId(String raw) {
        if (raw == null || raw.isBlank())
            return null;

        raw = raw.trim();

        Matcher fullBv = BV_FULL_RE.matcher(raw);
        if (fullBv.matches())
            return VideoId.bvid("BV" + raw.substring(2));

        Matcher fullAv = AV_FULL_RE.matcher(raw);
        if (fullAv.matches())
            return VideoId.aid(fullAv.group(1));

        return null;
    }

    public static boolean isStoredVideoSelection(String raw) {
        return parseStoredVideoSelection(raw) != null;
    }

    public static VideoSelection parseStoredVideoSelection(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        Matcher matcher = STORED_SELECTION_RE.matcher(raw.trim());
        if (!matcher.matches()) {
            return null;
        }

        VideoId videoId = extractVideoId(matcher.group(1));
        if (videoId == null) {
            return null;
        }

        int page = 1;
        if (matcher.group(2) != null && !matcher.group(2).isBlank()) {
            page = Math.max(1, Integer.parseInt(matcher.group(2)));
        }
        return new VideoSelection(videoId, page);
    }

    public static String formatStoredVideoSelection(VideoId videoId, int page) {
        return videoId.asInputText() + "|p=" + Math.max(1, page);
    }

    // == API ==

    public record VideoInfo(long aid, String title, long cid, int duration, int page, int totalPages, String part,
            VideoId videoId) {
        public String displayTitle() {
            if (totalPages <= 1) {
                return title;
            }
            String cleanPart = part == null ? "" : part.trim();
            if (cleanPart.isEmpty()) {
                return title + " - P" + page;
            }
            return title + " - P" + page + " " + cleanPart;
        }
    }

    public record VideoSelection(VideoId videoId, int page) {
    }

    public record SubtitleInfo(String lan, String url) {
        public boolean isAiGenerated() {
            return lan != null && lan.toLowerCase().startsWith("ai-");
        }

        public boolean isJsonSubtitle() {
            return url != null && url.contains(".json");
        }

        public String normalizedUrl() {
            if (url == null) {
                return "";
            }
            return url.startsWith("//") ? "https:" + url : url;
        }
    }

    // B站视频 ID：BV 或 AV(aid)
    public record VideoId(String kind, String value) {
        public static VideoId bvid(String value) {
            return new VideoId("bvid", value);
        }

        public static VideoId aid(String value) {
            return new VideoId("aid", value);
        }

        public boolean isBvid() {
            return "bvid".equals(kind);
        }

        public String asInputText() {
            return isBvid() ? value : "av" + value;
        }

        public void putViewParam(Map<String, String> params) {
            params.put(kind, value);
        }

        public void putPlayUrlParam(Map<String, String> params) {
            params.put(isBvid() ? "bvid" : "avid", value);
        }
    }

    // 获取视频信息
    public static VideoInfo getVideoInfo(VideoId id) throws Exception {
        return getVideoInfo(id, 1);
    }

    public static VideoInfo getVideoInfo(VideoId id, int requestedPage) throws Exception {
        Map<String, String> params = new HashMap<>();
        id.putViewParam(params);
        Map<String, String> signed = BiliWbiSigner.signParams(params);

        String url = "https://api.bilibili.com/x/web-interface/view?"
                + BiliWbiSigner.buildQuery(signed);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com/")
                .timeout(Duration.ofSeconds(15))
                .GET().build();

        HttpResponse<String> resp = BiliWbiSigner.HTTP.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();

        int code = body.get("code").getAsInt();
        if (code != 0) {
            String msg = body.has("message") ? body.get("message").getAsString() : "unknown";
            throw new RuntimeException("B站 view API 返回 code=" + code + ": " + msg);
        }

        JsonObject data = body.getAsJsonObject("data");
        long aid = data.get("aid").getAsLong();
        String title = data.get("title").getAsString();

        int duration = data.get("duration").getAsInt();
        JsonArray pages = data.getAsJsonArray("pages");
        int totalPages = pages != null && !pages.isEmpty() ? pages.size() : 1;
        int actualPage = 1;
        String part = "";
        long cid;

        if (pages != null && !pages.isEmpty()) {
            if (requestedPage < 1 || requestedPage > pages.size()) {
                throw new IllegalArgumentException("该视频只有 " + pages.size() + " 个分P");
            }
            actualPage = requestedPage;
            JsonObject page = pages.get(requestedPage - 1).getAsJsonObject();
            cid = page.get("cid").getAsLong();
            if (page.has("duration") && !page.get("duration").isJsonNull()) {
                duration = page.get("duration").getAsInt();
            }
            if (page.has("part") && !page.get("part").isJsonNull()) {
                part = page.get("part").getAsString();
            }
        } else {
            cid = data.get("cid").getAsLong();
        }

        return new VideoInfo(aid, title, cid, duration, actualPage, totalPages, part, id);
    }

    // 获取 DASH 音频流直链
    public static String getBestAudioUrl(VideoId id, long cid) throws Exception {
        Map<String, String> params = new HashMap<>();
        id.putPlayUrlParam(params);
        params.put("cid", String.valueOf(cid));
        params.put("fnval", "4048"); // 请求 DASH + FLAC + Dolby
        params.put("fnver", "0");
        params.put("fourk", "1");
        params.put("platform", "pc");
        Map<String, String> signed = BiliWbiSigner.signParams(params);

        String url = "https://api.bilibili.com/x/player/wbi/playurl?"
                + BiliWbiSigner.buildQuery(signed);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com/")
                .timeout(Duration.ofSeconds(15))
                .GET().build();

        HttpResponse<String> resp = BiliWbiSigner.HTTP.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();

        int code = body.get("code").getAsInt();
        if (code != 0) {
            String msg = body.has("message") ? body.get("message").getAsString() : "unknown";
            throw new RuntimeException("B站 playurl API 返回 code=" + code + ": " + msg);
        }

        JsonObject dash = body.getAsJsonObject("data").getAsJsonObject("dash");

        Map<Integer, String> streams = new HashMap<>();

        // 标准 DASH
        JsonArray audioArr = dash.has("audio") && !dash.get("audio").isJsonNull()
                ? dash.getAsJsonArray("audio")
                : null;
        if (audioArr != null) {
            for (JsonElement e : audioArr) {
                JsonObject a = e.getAsJsonObject();
                streams.put(a.get("id").getAsInt(), a.get("baseUrl").getAsString());
            }
        }

        // Hi-Res
        if (dash.has("flac") && !dash.get("flac").isJsonNull()) {
            JsonObject flac = dash.getAsJsonObject("flac");
            if (flac.has("audio") && !flac.get("audio").isJsonNull()) {
                JsonObject a = flac.getAsJsonObject("audio");
                streams.put(a.get("id").getAsInt(), a.get("baseUrl").getAsString());
            }
        }

        // 杜比
        if (dash.has("dolby") && !dash.get("dolby").isJsonNull()) {
            JsonObject dolby = dash.getAsJsonObject("dolby");
            if (dolby.has("audio")) {
                JsonElement ae = dolby.get("audio");
                if (ae.isJsonArray()) {
                    for (JsonElement e : ae.getAsJsonArray()) {
                        JsonObject a = e.getAsJsonObject();
                        streams.put(a.get("id").getAsInt(), a.get("baseUrl").getAsString());
                    }
                }
            }
        }

        if (streams.isEmpty()) {
            throw new RuntimeException("该视频没有可用的 DASH 音频流");
        }

        for (int qid : QUALITY_ORDER) {
            String baseUrl = streams.get(qid);
            if (baseUrl != null && !baseUrl.isEmpty())
                return baseUrl;
        }

        return streams.values().iterator().next();
    }

    /** 获取双语字幕（中文 + 英文），返回 NetEase 歌词 JSON 格式 */
    public static String getBilingualSubtitleAsNetEaseLyric(VideoInfo info) throws Exception {
        List<SubtitleInfo> all = getAllSubtitles(info);
        if (all.isEmpty()) {
            return null;
        }

        // 过滤：必须有可下载 URL 且非 AI 生成
        List<SubtitleInfo> candidates = new ArrayList<>();
        for (SubtitleInfo s : all) {
            if (!s.normalizedUrl().isBlank() && !s.isAiGenerated()) {
                candidates.add(s);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        // 找中文 + 英文
        SubtitleInfo zh = null;
        SubtitleInfo en = null;
        for (SubtitleInfo s : candidates) {
            if (zh == null && isChineseSubtitle(s.lan())) {
                zh = s;
            } else if (en == null && isEnglishSubtitle(s.lan())) {
                en = s;
            }
        }
        // 如果缺一个，用第一个非中非英的补
        SubtitleInfo fallback = null;
        for (SubtitleInfo s : candidates) {
            if (s != zh && s != en && fallback == null) {
                fallback = s;
            }
        }
        if (zh == null && fallback != null) {
            zh = fallback;
            fallback = null;
        }
        if (en == null && fallback != null) {
            en = fallback;
        }

        if (zh == null) {
            return null;
        }

        String zhLrc = convertSubtitleJsonToLrc(getText(zh.normalizedUrl()));
        String enLrc = en != null ? convertSubtitleJsonToLrc(getText(en.normalizedUrl())) : null;
        return buildNetEaseLyricJson(zhLrc, enLrc);
    }

    private static List<SubtitleInfo> getAllSubtitles(VideoInfo info) throws Exception {
        List<SubtitleInfo> subtitles = getSubtitlesFromPlayerApi(info);
        if (subtitles.isEmpty()) {
            subtitles = getSubtitlesFromViewApi(info);
        }
        return subtitles;
    }

    private static List<SubtitleInfo> getSubtitlesFromPlayerApi(VideoInfo info) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("aid", String.valueOf(info.aid()));
        params.put("cid", String.valueOf(info.cid()));
        info.videoId().putViewParam(params);
        Map<String, String> signed = BiliWbiSigner.signParams(params);

        String url = "https://api.bilibili.com/x/player/wbi/v2?" + BiliWbiSigner.buildQuery(signed);
        JsonObject root = getJson(url);
        List<SubtitleInfo> subtitles = new ArrayList<>();
        if (!root.has("data") || root.get("data").isJsonNull()) {
            return subtitles;
        }

        JsonObject data = root.getAsJsonObject("data");
        if (!data.has("subtitle") || data.get("subtitle").isJsonNull()) {
            return subtitles;
        }

        JsonObject subtitle = data.getAsJsonObject("subtitle");
        JsonArray array = subtitle.has("subtitles") && !subtitle.get("subtitles").isJsonNull()
                ? subtitle.getAsJsonArray("subtitles")
                : null;
        if (array == null) {
            return subtitles;
        }

        for (JsonElement element : array) {
            JsonObject item = element.getAsJsonObject();
            String lan = item.has("lan") ? item.get("lan").getAsString() : "unknown";
            String subtitleUrl = item.has("subtitle_url") ? item.get("subtitle_url").getAsString() : "";
            if (!subtitleUrl.isBlank()) {
                subtitles.add(new SubtitleInfo(lan, subtitleUrl));
            }
        }
        return subtitles;
    }

    private static List<SubtitleInfo> getSubtitlesFromViewApi(VideoInfo info) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("aid", String.valueOf(info.aid()));
        params.put("cid", String.valueOf(info.cid()));
        info.videoId().putViewParam(params);
        Map<String, String> signed = BiliWbiSigner.signParams(params);

        String url = "https://api.bilibili.com/x/web-interface/view?" + BiliWbiSigner.buildQuery(signed);
        JsonObject root = getJson(url);
        List<SubtitleInfo> subtitles = new ArrayList<>();
        if (!root.has("data") || root.get("data").isJsonNull()) {
            return subtitles;
        }

        JsonObject data = root.getAsJsonObject("data");
        if (!data.has("subtitle") || data.get("subtitle").isJsonNull()) {
            return subtitles;
        }

        JsonObject subtitle = data.getAsJsonObject("subtitle");
        JsonArray array = subtitle.has("list") && !subtitle.get("list").isJsonNull()
                ? subtitle.getAsJsonArray("list")
                : null;
        if (array == null) {
            return subtitles;
        }

        for (JsonElement element : array) {
            JsonObject item = element.getAsJsonObject();
            String lan = item.has("lan") ? item.get("lan").getAsString() : "unknown";
            String subtitleUrl = item.has("subtitle_url") ? item.get("subtitle_url").getAsString() : "";
            if (!subtitleUrl.isBlank()) {
                subtitles.add(new SubtitleInfo(lan, subtitleUrl));
            }
        }
        return subtitles;
    }

    private static JsonObject getJson(String url) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com/")
                .timeout(Duration.ofSeconds(15))
                .GET();
        if (!sessdata.isBlank()) {
            builder.header("Cookie", "SESSDATA=" + sessdata);
        }
        HttpResponse<String> response = BiliWbiSigner.HTTP.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String getText(String url) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com/")
                .timeout(Duration.ofSeconds(15))
                .GET();
        if (!sessdata.isBlank()) {
            builder.header("Cookie", "SESSDATA=" + sessdata);
        }
        HttpResponse<String> response = BiliWbiSigner.HTTP.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response.body();
    }

    /** 将 B站 CC 字幕 JSON 转为 LRC 格式字符串 */
    private static String convertSubtitleJsonToLrc(String subtitleJson) {
        JsonObject root = JsonParser.parseString(subtitleJson).getAsJsonObject();
        if (!root.has("body") || !root.get("body").isJsonArray()) {
            return null;
        }

        StringBuilder lrc = new StringBuilder();
        JsonArray body = root.getAsJsonArray("body");
        for (JsonElement element : body) {
            JsonObject line = element.getAsJsonObject();
            String content = line.has("content") && !line.get("content").isJsonNull()
                    ? line.get("content").getAsString().trim()
                    : "";
            if (content.isEmpty()) {
                continue;
            }
            double from = line.has("from") && !line.get("from").isJsonNull()
                    ? line.get("from").getAsDouble()
                    : 0.0D;
            lrc.append(formatLrcTime(from)).append(content).append('\n');
        }

        return lrc.isEmpty() ? null : lrc.toString();
    }

    /** 构建 NetEase 歌词 JSON（支持双语）。注意：字段名必须和 NetEase API 一致（lrc/tlyric） */
    private static String buildNetEaseLyricJson(String zhLrc, String enLrc) {
        JsonObject lyric = new JsonObject();
        lyric.addProperty("lyric", zhLrc);

        JsonObject netEaseLyric = new JsonObject();
        netEaseLyric.addProperty("code", 200);
        netEaseLyric.add("lrc", lyric);

        if (enLrc != null && !enLrc.isBlank()) {
            JsonObject transLyric = new JsonObject();
            transLyric.addProperty("lyric", enLrc);
            netEaseLyric.add("tlyric", transLyric);
        }

        return netEaseLyric.toString();
    }

    private static boolean isChineseSubtitle(String lan) {
        if (lan == null) {
            return false;
        }
        String normalized = lan.toLowerCase();
        return normalized.startsWith("zh")
                || normalized.startsWith("yue")
                || normalized.contains("hans")
                || normalized.contains("hant");
    }

    private static boolean isEnglishSubtitle(String lan) {
        return lan != null && lan.toLowerCase().startsWith("en");
    }

    private static String formatLrcTime(double seconds) {
        int totalMilliseconds = (int) Math.round(seconds * 1000.0D);
        int minutes = totalMilliseconds / 60000;
        int sec = (totalMilliseconds % 60000) / 1000;
        int milliseconds = totalMilliseconds % 1000;
        return String.format("[%02d:%02d.%03d]", minutes, sec, milliseconds);
    }
}
