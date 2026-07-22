package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnUrlFallbacks;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站 API 客户端：BV/AV 解析、视频信息、DASH 音频流地址
 */
public final class BiliApiClient {
    public static final int CODEC_H264 = 7;
    public static final int CODEC_HEVC = 12;
    public static final int CODEC_AV1 = 13;
    static final int FNVAL_DASH = 16;
    static final int FNVAL_4K = 128;
    static final int FNVAL_8K = 1024;
    static final int FNVAL_AV1 = 2048;
    private static final Pattern BV_FULL_RE = Pattern.compile("^[Bb][Vv][0-9A-Za-z]{10}$");
    private static final Pattern AV_FULL_RE = Pattern.compile("^av(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BV_ANYWHERE_RE = Pattern.compile("[Bb][Vv][0-9A-Za-z]{10}");
    private static final Pattern AV_ANYWHERE_RE = Pattern.compile("(?:^|[^0-9A-Za-z])av(\\d+)(?:$|[^0-9A-Za-z])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STORED_SELECTION_RE = Pattern.compile(
            "^((?:[Bb][Vv][0-9A-Za-z]{10}|av\\d+))(?:\\|p=(\\d+))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE_PARAM_RE = Pattern.compile("(?:^|[?&#|;])p=(\\d+)(?:$|[&#;])",
            Pattern.CASE_INSENSITIVE);
    private static final int[] STANDARD_AUDIO_ORDER = { 30280, 30232, 30216 };
    private static final int[] LOSSLESS_AUDIO_ORDER = { 30251, 30280, 30232, 30216 };
    private static final int[] DOLBY_AUDIO_ORDER = { 30250, 30251, 30280, 30232, 30216 };
    private static final int[] BEST_AUDIO_ORDER = { 30250, 30251, 30280, 30232, 30216 };
    private static final Duration SHORT_LINK_TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient SHORT_LINK_HTTP = HttpClient.newBuilder()
            .connectTimeout(SHORT_LINK_TIMEOUT)
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    // B站 SESSDATA Cookie，扫码登录后自动获取
    public static volatile String sessdata = System.getProperty("ncpb.bili.sessdata", "");
    // 扫码登录返回的完整 Web Cookie，可包含 buvid/bili_jct/DedeUserID 等降低风控概率的字段。
    public static volatile String webCookie = System.getProperty("ncpb.bili.cookie", "");

    private BiliApiClient() {
    }

    // == 视频 ID 解析 ==

    public static boolean isBiliVideoId(String input) {
        if (input == null || input.isBlank())
            return false;

        String raw = input.trim();
        return BV_FULL_RE.matcher(raw).matches() || AV_FULL_RE.matcher(raw).matches();
    }

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

    /**
     * 从纯 BV/AV、已存储的 {@code BV...|p=N} 选择串，或常见 B 站链接中提取视频 ID。
     * <p>
     * b23.tv 短链需要联网跟随重定向，命令层暂不自动展开，避免服务器命令卡在网络请求上。
     */
    public static VideoId extractVideoIdLenient(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String trimmed = raw.trim();
        VideoSelection selection = parseStoredVideoSelection(trimmed);
        if (selection != null) {
            return selection.videoId();
        }

        VideoId direct = extractVideoId(trimmed);
        if (direct != null) {
            return direct;
        }

        Matcher bv = BV_ANYWHERE_RE.matcher(trimmed);
        if (bv.find()) {
            String value = bv.group();
            return VideoId.bvid("BV" + value.substring(2));
        }

        Matcher av = AV_ANYWHERE_RE.matcher(trimmed);
        if (av.find()) {
            return VideoId.aid(av.group(1));
        }

        return null;
    }

    public static boolean containsBiliVideoId(String input) {
        return extractVideoIdLenient(input) != null;
    }

    public static VideoId extractVideoIdLenientWithShortLink(String raw) {
        VideoId direct = extractVideoIdLenient(raw);
        if (direct != null || raw == null || raw.isBlank() || !looksLikeBiliShortLink(raw)) {
            return direct;
        }
        String expanded = expandBiliShortLink(raw.trim());
        return expanded == null ? null : extractVideoIdLenient(expanded);
    }

    public static VideoSelection extractVideoSelectionLenient(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        VideoSelection stored = parseStoredVideoSelection(trimmed);
        if (stored != null) {
            return stored;
        }
        VideoId id = extractVideoIdLenient(trimmed);
        return id != null ? new VideoSelection(id, extractPageOrDefault(trimmed, 1)) : null;
    }

    public static VideoSelection extractVideoSelectionLenientWithShortLink(String raw) {
        VideoSelection direct = extractVideoSelectionLenient(raw);
        if (direct != null || raw == null || raw.isBlank() || !looksLikeBiliShortLink(raw)) {
            return direct;
        }
        String expanded = expandBiliShortLink(raw.trim());
        return expanded == null ? null : extractVideoSelectionLenient(expanded);
    }

    private static boolean looksLikeBiliShortLink(String raw) {
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("b23.tv/") || lower.contains("bili2233.cn/");
    }

    private static int extractPageOrDefault(String raw, int fallback) {
        int safeFallback = Math.max(1, fallback);
        if (raw == null || raw.isBlank()) {
            return safeFallback;
        }
        Matcher matcher = PAGE_PARAM_RE.matcher(raw.trim());
        if (!matcher.find()) {
            return safeFallback;
        }
        return parsePositivePageOrDefault(matcher.group(1), safeFallback);
    }

    private static String expandBiliShortLink(String raw) {
        try {
            URI uri = URI.create(raw.startsWith("http://") || raw.startsWith("https://") ? raw : "https://" + raw);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(SHORT_LINK_TIMEOUT)
                    .GET();
            BiliRequestHeaders.applyWebApiHeaders(builder);
            HttpRequest req = builder.build();
            return SHORT_LINK_HTTP.send(req, HttpResponse.BodyHandlers.discarding()).uri().toString();
        } catch (Exception e) {
            logger().debug("B站短链展开失败: {}", raw, e);
            return null;
        }
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
            page = parsePositivePageOrDefault(matcher.group(2), 1);
        }
        return new VideoSelection(videoId, page);
    }

    private static int parsePositivePageOrDefault(String rawPage, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(rawPage));
        } catch (NumberFormatException ignored) {
            return Math.max(1, fallback);
        }
    }

    public static String formatStoredVideoSelection(VideoId videoId, int page) {
        return videoId.asInputText() + "|p=" + Math.max(1, page);
    }

    // == API ==

    public record VideoInfo(long aid, String title, List<String> staffNames, long cid, int duration, int page,
            int totalPages, String part, VideoId videoId) {
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

    public record VideoStream(int quality, int codecId, int width, int height, String frameRate, String codecs,
            String baseUrl, long initStart, long initEnd, long indexStart, long indexEnd, List<String> cdnCandidates) {
        public VideoStream(int quality, int codecId, int width, int height, String frameRate, String codecs,
                String baseUrl) {
            this(quality, codecId, width, height, frameRate, codecs, baseUrl, -1L, -1L, -1L, -1L,
                    baseUrl == null || baseUrl.isBlank() ? List.of() : List.of(baseUrl));
        }

        public VideoStream(int quality, int codecId, int width, int height, String frameRate, String codecs,
                String baseUrl, long initStart, long initEnd, long indexStart, long indexEnd) {
            this(quality, codecId, width, height, frameRate, codecs, baseUrl, initStart, initEnd, indexStart, indexEnd,
                    baseUrl == null || baseUrl.isBlank() ? List.of() : List.of(baseUrl));
        }

        public boolean hasSegmentBase() {
            return initStart >= 0L && initEnd >= initStart && indexStart >= 0L && indexEnd >= indexStart;
        }

        public String codecName() {
            return switch (codecId) {
                case CODEC_H264 -> "H.264";
                case CODEC_HEVC -> "HEVC";
                case CODEC_AV1 -> "AV1";
                default -> "codecId=" + codecId;
            };
        }

        public String displaySize() {
            return width > 0 && height > 0 ? width + "x" + height : "unknown";
        }

        public String qualityLabel() {
            return BiliApiClient.qualityLabel(quality);
        }
    }

    /** 同一次 playurl 响应内可用于探测和回退的视频候选。 */
    public record VideoStreamPlan(int requestedQualityCeiling, List<VideoStream> av1Candidates,
            List<VideoStream> h264Candidates, List<VideoStream> softwareAv1Candidates, List<String> diagnostics) {
        public VideoStreamPlan {
            av1Candidates = List.copyOf(av1Candidates);
            h264Candidates = List.copyOf(h264Candidates);
            softwareAv1Candidates = List.copyOf(softwareAv1Candidates);
            diagnostics = List.copyOf(diagnostics);
        }

        public VideoStream preferred() {
            if (!av1Candidates.isEmpty()) {
                return av1Candidates.get(0);
            }
            if (!h264Candidates.isEmpty()) {
                return h264Candidates.get(0);
            }
            throw new IllegalStateException("该视频没有可用的 AV1/H.264 DASH 视频流");
        }

        public List<VideoStream> fallbackOrder() {
            List<VideoStream> result = new ArrayList<>(
                    av1Candidates.size() + h264Candidates.size() + softwareAv1Candidates.size());
            result.addAll(av1Candidates);
            result.addAll(h264Candidates);
            result.addAll(softwareAv1Candidates);
            return List.copyOf(result);
        }
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

    // B站视频 ID：BV 或 AV
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

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET();
        BiliRequestHeaders.applyWebApiHeaders(builder);
        HttpRequest req = builder.build();

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

        // 提取 UP主名称
        List<String> staffNames = new ArrayList<>();
        if (data.has("staff") && !data.get("staff").isJsonNull()) {
            JsonArray staff = data.getAsJsonArray("staff");
            for (JsonElement e : staff) {
                JsonObject member = e.getAsJsonObject();
                if (member.has("name") && !member.get("name").isJsonNull()) {
                    staffNames.add(member.get("name").getAsString());
                }
            }
        }
        if (staffNames.isEmpty() && data.has("owner") && !data.get("owner").isJsonNull()) {
            JsonObject owner = data.getAsJsonObject("owner");
            if (owner.has("name") && !owner.get("name").isJsonNull()) {
                staffNames.add(owner.get("name").getAsString());
            }
        }

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

        return new VideoInfo(aid, title, staffNames, cid, duration, actualPage, totalPages, part, id);
    }

    public static String getBestAudioUrl(VideoId id, long cid) throws Exception {
        return getBestAudioUrl(id, cid, true);
    }

    // 获取 DASH 音频流直链
    public static String getBestAudioUrl(VideoId id, long cid, boolean allowDolby) throws Exception {
        Map<String, String> params = new HashMap<>();
        id.putPlayUrlParam(params);
        params.put("cid", String.valueOf(cid));
        params.put("fnval", "4048");
        params.put("fnver", "0");
        params.put("fourk", "1");
        params.put("platform", "pc");
        Map<String, String> signed = BiliWbiSigner.signParams(params);

        String url = "https://api.bilibili.com/x/player/wbi/playurl?"
                + BiliWbiSigner.buildQuery(signed);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15));
        BiliRequestHeaders.applyWebApiHeaders(builder);
        HttpRequest req = builder.GET().build();

        HttpResponse<String> resp = BiliWbiSigner.HTTP.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();

        int code = body.get("code").getAsInt();
        if (code != 0) {
            String msg = body.has("message") ? body.get("message").getAsString() : "unknown";
            throw new RuntimeException("B站 playurl API 返回 code=" + code + ": " + msg);
        }

        JsonObject dash = body.getAsJsonObject("data").getAsJsonObject("dash");

        Map<Integer, List<String>> streams = new HashMap<>();

        // 标准 DASH
        JsonArray audioArr = dash.has("audio") && !dash.get("audio").isJsonNull()
                ? dash.getAsJsonArray("audio")
                : null;
        if (audioArr != null) {
            for (JsonElement e : audioArr) {
                JsonObject a = e.getAsJsonObject();
                addAudioStreamCandidates(streams, a);
            }
        }

        // 杜比全景声 (EC-3) — 仅在用户开启且 FFmpeg native 可用时纳入选择
        boolean nativeDolbyAvailable = com.zhongbai233.net_music_can_play_bili.media.codec.Eac3NativeDecoder
                .isNativeAvailable();
        boolean dashHasDolby = dash.has("dolby") && !dash.get("dolby").isJsonNull();
        boolean dolbyOk = allowDolby
                && BiliConfig.dolbyEnabled
                && nativeDolbyAvailable;
        if (dolbyOk && dashHasDolby) {
            JsonObject dolby = dash.getAsJsonObject("dolby");
            if (dolby.has("audio") && !dolby.get("audio").isJsonNull()) {
                JsonArray dolbyArr = dolby.getAsJsonArray("audio");
                for (JsonElement e : dolbyArr) {
                    JsonObject a = e.getAsJsonObject();
                    addAudioStreamCandidates(streams, a);
                }
            }
        }

        String configuredAudioPreference = System.getProperty("ncpb.bili.audio.preference", "auto")
                .trim().toLowerCase(java.util.Locale.ROOT);
        boolean allowFlac = configuredAudioPreference.equals("auto")
                || configuredAudioPreference.equals("dolby")
                || configuredAudioPreference.equals("atmos")
                || configuredAudioPreference.equals("eac3")
                || configuredAudioPreference.equals("flac")
                || configuredAudioPreference.equals("lossless")
                || configuredAudioPreference.equals("hires")
                || configuredAudioPreference.equals("best");

        // Hi-Res FLAC 是独立的无损音频轨；自动模式按 杜比 -> FLAC -> AAC 顺位兜底。
        if (allowFlac && dash.has("flac") && !dash.get("flac").isJsonNull()) {
            JsonObject flac = dash.getAsJsonObject("flac");
            if (flac.has("audio") && !flac.get("audio").isJsonNull()) {
                JsonObject a = flac.getAsJsonObject("audio");
                addAudioStreamCandidates(streams, a);
            }
        }

        if (streams.isEmpty()) {
            throw new RuntimeException("该视频没有可用的 DASH 音频流");
        }

        int selectedQuality = -1;
        String selectedUrl = null;
        List<String> selectedCandidates = List.of();
        String effectiveAudioPreference = effectiveAudioPreference(configuredAudioPreference);
        int[] qualityOrder = audioQualityOrder(effectiveAudioPreference, allowDolby);
        for (int qid : qualityOrder) {
            List<String> candidates = streams.get(qid);
            if (candidates != null && !candidates.isEmpty()) {
                selectedQuality = qid;
                selectedCandidates = BiliCdnSelector.orderCandidates(candidates);
                selectedUrl = BiliCdnSelector.selectPreferred(selectedCandidates);
                break;
            }
        }

        if (selectedUrl == null) {
            selectedQuality = streams.keySet().iterator().next();
            selectedCandidates = BiliCdnSelector.orderCandidates(streams.get(selectedQuality));
            selectedUrl = BiliCdnSelector.selectPreferred(selectedCandidates);
        }
        logger().debug(
                "B站音频流选择摘要: id={} cid={} preference={} effectivePreference={} allowDolby={} dolbyConfig={} nativeDolby={} dashDolby={} qualities={} selected={}({}) candidateCount={} host={}",
                id.asInputText(), cid, configuredAudioPreference, effectiveAudioPreference, allowDolby,
                BiliConfig.dolbyEnabled, nativeDolbyAvailable, dashHasDolby, streams.keySet(), selectedQuality,
                audioQualityLabel(selectedQuality),
                selectedCandidates.size(), hostOf(selectedUrl));
        CdnUrlFallbacks.registerAlternates(selectedCandidates);
        return selectedUrl;
    }

    private static void addAudioStreamCandidates(Map<Integer, List<String>> streams, JsonObject stream) {
        if (stream == null || !stream.has("id") || stream.get("id").isJsonNull()) {
            return;
        }
        List<String> urls = extractStreamUrls(stream);
        if (urls.isEmpty()) {
            return;
        }
        for (String url : urls) {
            registerAudioSegmentBase(url, stream);
        }
        streams.computeIfAbsent(stream.get("id").getAsInt(), ignored -> new ArrayList<>()).addAll(urls);
    }

    private static List<String> extractStreamUrls(JsonObject stream) {
        Set<String> urls = new LinkedHashSet<>();
        addUrlField(urls, stream, "baseUrl");
        addUrlField(urls, stream, "base_url");
        addUrlField(urls, stream, "backupUrl");
        addUrlField(urls, stream, "backup_url");
        return new ArrayList<>(urls);
    }

    private static void addUrlField(Set<String> urls, JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return;
        }
        JsonElement value = object.get(field);
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                addUrlValue(urls, element);
            }
        } else {
            addUrlValue(urls, value);
        }
    }

    private static void addUrlValue(Set<String> urls, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        String url = value.getAsString();
        if (url != null && !url.isBlank()) {
            urls.add(url);
        }
    }

    private static int[] audioQualityOrder(String preference, boolean allowDolby) {
        return switch (preference) {
            case "auto", "best" -> allowDolby ? BEST_AUDIO_ORDER : LOSSLESS_AUDIO_ORDER;
            case "dolby", "atmos", "eac3" -> allowDolby ? DOLBY_AUDIO_ORDER : LOSSLESS_AUDIO_ORDER;
            case "flac", "lossless", "hires" -> LOSSLESS_AUDIO_ORDER;
            default -> STANDARD_AUDIO_ORDER;
        };
    }

    private static String effectiveAudioPreference(String configuredPreference) {
        if (configuredPreference == null || configuredPreference.isBlank() || "auto".equals(configuredPreference)) {
            return "auto";
        }
        return configuredPreference;
    }

    private static void registerAudioSegmentBase(String baseUrl, JsonObject stream) {
        long[] initRange = parseSegmentBaseRange(stream, "initialization");
        long[] indexRange = parseSegmentBaseRange(stream, "index_range");
        HttpAudioStreamHandler.registerSegmentBase(baseUrl, initRange[0], initRange[1], indexRange[0], indexRange[1]);
    }

    private static String hostOf(String value) {
        try {
            String host = URI.create(value).getHost();
            return host != null ? host : "unknown";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    // 获取 DASH 视频流直链
    public static String getBestVideoUrl(VideoId id, long cid, int preferredQuality) throws Exception {
        return getBestVideoStream(id, cid, preferredQuality).baseUrl();
    }

    public static VideoStream getBestVideoStream(VideoId id, long cid, int preferredQuality) throws Exception {
        return getVideoStreamPlan(id, cid, preferredQuality).preferred();
    }

    public static VideoStreamPlan getVideoStreamPlan(VideoId id, long cid, int preferredQuality) throws Exception {
        Map<String, String> params = new HashMap<>();
        id.putPlayUrlParam(params);
        params.put("cid", String.valueOf(cid));
        params.put("qn", String.valueOf(preferredQuality));
        params.put("fnval", String.valueOf(videoFnval(preferredQuality)));
        params.put("fnver", "0");
        params.put("fourk", "1");
        params.put("high_quality", "1");
        params.put("platform", "pc");
        Map<String, String> signed = BiliWbiSigner.signParams(params);

        String url = "https://api.bilibili.com/x/player/wbi/playurl?"
                + BiliWbiSigner.buildQuery(signed);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15));
        BiliRequestHeaders.applyWebApiHeaders(builder);
        HttpRequest req = builder.GET().build();

        HttpResponse<String> resp = BiliWbiSigner.HTTP.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();

        int code = body.get("code").getAsInt();
        if (code != 0) {
            String msg = body.has("message") ? body.get("message").getAsString() : "unknown";
            throw new RuntimeException("B站 playurl API 返回 code=" + code + ": " + msg);
        }

        JsonObject dash = body.getAsJsonObject("data").getAsJsonObject("dash");
        JsonArray videoArr = dash.getAsJsonArray("video");
        if (videoArr == null || videoArr.isEmpty()) {
            throw new RuntimeException("该视频没有 DASH 视频流");
        }

        List<VideoStream> streams = new ArrayList<>();
        for (JsonElement e : videoArr) {
            JsonObject v = e.getAsJsonObject();
            int qid = v.get("id").getAsInt();
            int codecId = v.has("codecid") ? v.get("codecid").getAsInt() : 0;
            String baseUrl = v.has("baseUrl") && !v.get("baseUrl").isJsonNull()
                    ? v.get("baseUrl").getAsString()
                    : v.has("base_url") && !v.get("base_url").isJsonNull() ? v.get("base_url").getAsString() : "";
            List<String> cdnCandidates = extractStreamUrls(v);
            if (cdnCandidates.isEmpty() && baseUrl != null && !baseUrl.isBlank()) {
                cdnCandidates = List.of(baseUrl);
            }
            int width = v.has("width") && !v.get("width").isJsonNull() ? v.get("width").getAsInt() : 0;
            int height = v.has("height") && !v.get("height").isJsonNull() ? v.get("height").getAsInt() : 0;
            String frameRate = v.has("frameRate") && !v.get("frameRate").isJsonNull()
                    ? v.get("frameRate").getAsString()
                    : "";
            String codecs = v.has("codecs") && !v.get("codecs").isJsonNull()
                    ? v.get("codecs").getAsString()
                    : "";
            long[] initRange = parseSegmentBaseRange(v, "initialization");
            long[] indexRange = parseSegmentBaseRange(v, "index_range");
            streams.add(new VideoStream(qid, codecId, width, height, frameRate, codecs, baseUrl,
                    initRange[0], initRange[1], indexRange[0], indexRange[1], cdnCandidates));
        }

        VideoStreamPlan plan = buildVideoStreamPlan(streams, preferredQuality);
        VideoStream selected = plan.preferred();
        logger().debug(
                "B站视频流选择摘要: id={} cid={} qualityCeiling={} available={} selected={} codec={} size={}x{} fps={} av1Candidates={} h264Candidates={} rejected={} host={}",
                id.asInputText(), cid, qualityLabel(preferredQuality),
                streams.stream().map(stream -> qualityLabel(stream.quality())).distinct().toList(),
                qualityLabel(selected.quality()),
                selected.codecId(), selected.width(), selected.height(), selected.frameRate(),
                plan.av1Candidates().size(), plan.h264Candidates().size(), plan.diagnostics(),
                hostOf(selected.baseUrl()));
        return registerVideoPlan(plan);
    }

    static int videoFnval(int preferredQuality) {
        int fnval = FNVAL_DASH | FNVAL_AV1;
        if (videoQualityRank(preferredQuality) >= videoQualityRank(120)) {
            fnval |= FNVAL_4K;
        }
        if (videoQualityRank(preferredQuality) >= videoQualityRank(127)) {
            fnval |= FNVAL_8K;
        }
        return fnval;
    }

    static VideoStreamPlan buildVideoStreamPlan(List<VideoStream> streams, int preferredQuality) {
        int ceilingRank = videoQualityRank(preferredQuality);
        boolean requestedSpecial = isSpecialVideoQuality(preferredQuality);
        List<String> diagnostics = new ArrayList<>();
        List<VideoStream> accepted = new ArrayList<>();
        for (VideoStream stream : streams) {
            String rejection = rejectionReason(stream, ceilingRank, requestedSpecial);
            if (rejection == null) {
                accepted.add(preferCdn(stream));
            } else {
                diagnostics.add("q" + stream.quality() + "/" + stream.codecId() + ":" + rejection);
            }
        }
        java.util.Comparator<VideoStream> highestQualityFirst = (left, right) -> {
            int quality = Integer.compare(videoQualityRank(right.quality()), videoQualityRank(left.quality()));
            if (quality != 0) {
                return quality;
            }
            int width = Integer.compare(right.width(), left.width());
            return width != 0 ? width : left.baseUrl().compareTo(right.baseUrl());
        };
        List<VideoStream> sortedAv1 = accepted.stream().filter(stream -> stream.codecId() == CODEC_AV1)
                .sorted(highestQualityFirst).toList();
        List<VideoStream> av1 = selectAv1ProbeCandidates(sortedAv1);
        List<VideoStream> h264 = accepted.stream().filter(stream -> stream.codecId() == CODEC_H264)
                .sorted(highestQualityFirst).limit(1).toList();
        List<VideoStream> softwareAv1 = sortedAv1.stream()
                .filter(BiliApiClient::isSafeSoftwareAv1)
                .limit(1)
                .toList();
        if (av1.isEmpty() && h264.isEmpty()) {
            throw new IllegalStateException("该视频没有画质上限内且编码标识有效的 AV1/H.264 DASH 视频流: "
                    + diagnostics);
        }
        return new VideoStreamPlan(preferredQuality, av1, h264, softwareAv1, diagnostics);
    }

    private static boolean isSafeSoftwareAv1(VideoStream stream) {
        double fps = parseFrameRateValue(stream.frameRate());
        int width = Math.max(0, stream.width());
        int height = Math.max(0, stream.height());
        return width <= 1280 && height <= 720 && fps <= 60.5D
                || width <= 1920 && height <= 1080 && fps <= 30.5D;
    }

    private static double parseFrameRateValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Double.POSITIVE_INFINITY;
        }
        try {
            String[] parts = raw.trim().split("/", 2);
            double numerator = Double.parseDouble(parts[0]);
            if (parts.length == 1) {
                return numerator;
            }
            double denominator = Double.parseDouble(parts[1]);
            return denominator > 0.0D ? numerator / denominator : Double.POSITIVE_INFINITY;
        } catch (NumberFormatException ignored) {
            return Double.POSITIVE_INFINITY;
        }
    }

    private static List<VideoStream> selectAv1ProbeCandidates(List<VideoStream> sortedAv1) {
        if (sortedAv1.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<VideoStream> probes = new LinkedHashSet<>();
        probes.add(sortedAv1.get(0));
        addHighestAtOrBelow(probes, sortedAv1, 120);
        addHighestAtOrBelow(probes, sortedAv1, 116);
        return List.copyOf(probes);
    }

    private static void addHighestAtOrBelow(Set<VideoStream> result, List<VideoStream> sortedStreams,
            int qualityCeiling) {
        int ceilingRank = videoQualityRank(qualityCeiling);
        sortedStreams.stream()
                .filter(stream -> videoQualityRank(stream.quality()) <= ceilingRank)
                .findFirst()
                .ifPresent(result::add);
    }

    private static String rejectionReason(VideoStream stream, int ceilingRank, boolean requestedSpecial) {
        if (stream.codecId() != CODEC_AV1 && stream.codecId() != CODEC_H264) {
            return stream.codecId() == CODEC_HEVC ? "hevc-disabled" : "unsupported-codec";
        }
        String codecs = stream.codecs() == null ? "" : stream.codecs().trim().toLowerCase(java.util.Locale.ROOT);
        if (codecs.isEmpty()) {
            return "missing-codecs";
        }
        if (stream.codecId() == CODEC_AV1 && !codecs.startsWith("av01.")) {
            return "codec-string-mismatch";
        }
        if (stream.codecId() == CODEC_H264 && !codecs.startsWith("avc1.")) {
            return "codec-string-mismatch";
        }
        if (!requestedSpecial && isSpecialVideoQuality(stream.quality())) {
            return "special-quality-not-requested";
        }
        if (videoQualityRank(stream.quality()) > ceilingRank) {
            return "above-quality-ceiling";
        }
        if (stream.baseUrl() == null || stream.baseUrl().isBlank()) {
            return "missing-url";
        }
        return null;
    }

    private static VideoStream preferCdn(VideoStream stream) {
        List<String> ordered = BiliCdnSelector.orderCandidates(stream.cdnCandidates());
        String preferredUrl = BiliCdnSelector.selectPreferred(ordered);
        return new VideoStream(stream.quality(), stream.codecId(), stream.width(), stream.height(),
                stream.frameRate(), stream.codecs(), preferredUrl.isBlank() ? stream.baseUrl() : preferredUrl,
                stream.initStart(), stream.initEnd(), stream.indexStart(), stream.indexEnd(), ordered);
    }

    private static VideoStreamPlan registerVideoPlan(VideoStreamPlan plan) {
        for (VideoStream stream : plan.fallbackOrder()) {
            if (stream.hasSegmentBase()) {
                Fmp4NativeVideoDecoder.registerSegmentBase(stream.baseUrl(), stream.initStart(), stream.initEnd(),
                        stream.indexStart(), stream.indexEnd());
            }
            CdnUrlFallbacks.registerAlternates(stream.cdnCandidates());
        }
        return plan;
    }

    private static long[] parseSegmentBaseRange(JsonObject stream, String key) {
        if (!stream.has("segment_base") || stream.get("segment_base").isJsonNull()) {
            return new long[] { -1L, -1L };
        }
        JsonObject segmentBase = stream.getAsJsonObject("segment_base");
        if (!segmentBase.has(key) || segmentBase.get(key).isJsonNull()) {
            return new long[] { -1L, -1L };
        }
        String raw = segmentBase.get(key).getAsString();
        int dash = raw.indexOf('-');
        if (dash <= 0 || dash >= raw.length() - 1) {
            return new long[] { -1L, -1L };
        }
        try {
            long start = Long.parseLong(raw.substring(0, dash).trim());
            long end = Long.parseLong(raw.substring(dash + 1).trim());
            return end >= start ? new long[] { start, end } : new long[] { -1L, -1L };
        } catch (NumberFormatException ignored) {
            return new long[] { -1L, -1L };
        }
    }

    public static boolean isSpecialVideoQuality(int quality) {
        return quality == 100 || quality == 125 || quality == 126 || quality == 129;
    }

    public static String qualityLabel(int quality) {
        return "q" + quality + "(" + switch (quality) {
            case 6 -> "240P 极速";
            case 16 -> "360P";
            case 32 -> "480P";
            case 64 -> "720P";
            case 74 -> "720P60";
            case 80 -> "1080P";
            case 100 -> "智能修复/特性";
            case 112 -> "1080P+";
            case 116 -> "1080P60";
            case 120 -> "4K";
            case 125 -> "HDR 真彩色/特性";
            case 126 -> "杜比视界/特性";
            case 127 -> "8K";
            case 129 -> "HDR Vivid/特性";
            default -> "unknown";
        } + ")";
    }

    public static String audioQualityLabel(int quality) {
        return switch (quality) {
            case 30216 -> "64K AAC";
            case 30232 -> "132K AAC";
            case 30280 -> "192K AAC";
            case 30250 -> "Dolby Atmos/EC-3";
            case 30251 -> "Hi-Res/FLAC";
            default -> "unknown";
        };
    }

    private static int videoQualityRank(int quality) {
        return switch (quality) {
            case 6 -> 10;
            case 16 -> 20;
            case 32 -> 30;
            case 64 -> 40;
            case 74 -> 45;
            case 80 -> 50;
            case 112 -> 60;
            case 116 -> 65;
            case 120 -> 70;
            case 127 -> 80;
            default -> isSpecialVideoQuality(quality) ? -1 : quality;
        };
    }

    // 获取字幕并转为 NetEase 歌词 JSON 格式
    public static String getBilingualSubtitleAsNetEaseLyric(VideoInfo info) throws Exception {
        return getBilingualSubtitleAsNetEaseLyric(info, false);
    }

    public static String getBilingualSubtitleAsNetEaseLyric(VideoInfo info, boolean allowAi) throws Exception {
        List<SubtitleInfo> all = getAllSubtitles(info);
        if (all.isEmpty()) {
            return null;
        }

        List<SubtitleInfo> candidates = new ArrayList<>();
        for (SubtitleInfo s : all) {
            if (!s.normalizedUrl().isBlank() && (allowAi || !s.isAiGenerated())) {
                candidates.add(s);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        SubtitleInfo chinese = null;
        SubtitleInfo english = null;
        SubtitleInfo other = null;
        for (SubtitleInfo s : candidates) {
            if (chinese == null && isChineseSubtitle(s.lan())) {
                chinese = s;
            } else if (english == null && isEnglishSubtitle(s.lan())) {
                english = s;
            } else if (other == null) {
                other = s;
            }
        }

        SubtitleInfo zhSub = chinese != null ? chinese
                : english != null ? english
                        : other;
        if (zhSub == null) {
            return null;
        }

        SubtitleInfo transSub = null;
        if (zhSub == chinese) {
            transSub = english != null ? english : other;
        } else if (zhSub == english) {
            transSub = chinese != null ? chinese : other;
        } else {
            transSub = chinese != null ? chinese : (english != null ? english : null);
        }

        String zhLrc = convertSubtitleJsonToLrc(getText(zhSub.normalizedUrl()));
        String transLrc = transSub != null ? convertSubtitleJsonToLrc(getText(transSub.normalizedUrl())) : null;
        return buildNetEaseLyricJson(zhLrc, transLrc);
    }

    static List<SubtitleInfo> getAllSubtitles(VideoInfo info) throws Exception {
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
                .timeout(Duration.ofSeconds(15))
                .GET();
        BiliRequestHeaders.applyWebApiHeaders(builder);
        HttpResponse<String> response = BiliWbiSigner.HTTP.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String getText(String url) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET();
        BiliRequestHeaders.applyWebApiHeaders(builder);
        HttpResponse<String> response = BiliWbiSigner.HTTP.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response.body();
    }

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

    // 为没有 CC 字幕的 B站视频生成占位歌词。
    public static String buildPlaceholderNetEaseLyric(VideoInfo info, String note) {
        String title = info.displayTitle();
        String artists = !info.staffNames().isEmpty() ? String.join(" | ", info.staffNames()) : "";

        // 截断过长内容，避免歌词行溢出
        int maxArtistLen = 30;
        if (artists.length() > maxArtistLen) {
            artists = artists.substring(0, maxArtistLen - 1) + "\u2026";
        }
        int maxTotal = 52;
        int titleBudget = maxTotal - (artists.isEmpty() ? 0 : 5 + artists.length()); // " By. " = 5
        if (title.length() > titleBudget) {
            title = title.substring(0, Math.max(8, titleBudget - 1)) + "\u2026";
        }

        StringBuilder zhLrc = new StringBuilder();
        zhLrc.append(formatLrcTime(0)).append(title);
        if (!artists.isEmpty()) {
            zhLrc.append(" By. ").append(artists);
        }
        zhLrc.append('\n');

        String transLrc = formatLrcTime(0) + "\uff08" + note + "\uff09\n";

        return buildNetEaseLyricJson(zhLrc.toString(), transLrc);
    }

    // 构建 NetEase 歌词 JSON
    private static String buildNetEaseLyricJson(String zhLrc, String transLrc) {
        JsonObject netEaseLyric = new JsonObject();
        netEaseLyric.addProperty("code", 200);

        boolean hasTrans = transLrc != null && !transLrc.isBlank();

        // lrc 必须有内容，有翻译时放翻译，否则放原文
        String lrcContent = hasTrans ? transLrc : zhLrc;
        if (lrcContent != null && !lrcContent.isBlank()) {
            JsonObject lrc = new JsonObject();
            lrc.addProperty("lyric", lrcContent);
            netEaseLyric.add("lrc", lrc);
        }

        // tlyric 仅在有翻译时传递，放原文
        if (hasTrans && zhLrc != null && !zhLrc.isBlank()) {
            JsonObject tlyric = new JsonObject();
            tlyric.addProperty("lyric", zhLrc);
            netEaseLyric.add("tlyric", tlyric);
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

    private static Logger logger() {
        return LoggerHolder.INSTANCE;
    }

    private static final class LoggerHolder {
        private static final Logger INSTANCE = LogUtils.getLogger();

        private LoggerHolder() {
        }
    }
}
