package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import com.zhongbai233.net_music_can_play_bili.media.stream.CancellableHttpRequestScope;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;

/**
 * B站直播流地址解析。
 *
 * <p>
 * 同一个直播间会同时提供 HTTP-FLV 和 HLS 两种分发方式。本模组自己解析 FLV：
 * 单条长连接、无需轮询播放列表、可以带上直播站点的请求头和登录 Cookie。
 * HLS 结果仍然保留，作为没有 FLV 时交回 NetMusic 通用 m3u8 播放路径的兜底。
 * </p>
 */
public final class BiliLiveStreamResolver {
    /** 直播间未开播。 */
    public static final int LIVE_STATUS_OFFLINE = 0;
    /** 直播中。 */
    public static final int LIVE_STATUS_LIVE = 1;
    /** 轮播（主播未开播，但房间在放录像）。 */
    public static final int LIVE_STATUS_CAROUSEL = 2;

    private static final String ROOM_INIT_API = "https://api.live.bilibili.com/room/v1/Room/room_init";
    private static final String ROOM_INFO_API = "https://api.live.bilibili.com/room/v1/Room/get_info";
    private static final String PLAY_INFO_API = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo";
    private static final String LEGACY_PLAY_URL_API = "https://api.live.bilibili.com/room/v1/Room/playUrl";
    private static final Duration API_TIMEOUT = Duration.ofSeconds(10);
    private static final long ROOM_METADATA_TTL_NANOS = TimeUnit.MINUTES.toNanos(5L);
    private static final int ROOM_METADATA_CACHE_LIMIT = 128;
    private static final ConcurrentHashMap<String, CachedLiveMetadata> ROOM_METADATA = new ConcurrentHashMap<>();

    private BiliLiveStreamResolver() {
    }

    /** 一路可播放的直播流地址。 */
    public record LiveStream(String protocol, String format, String codec, String url) {
        public boolean isFlv() {
            return "flv".equals(format);
        }

        public boolean isHls() {
            return "http_hls".equals(protocol);
        }
    }

    /** 一次房间信息快照；播放 URL 刷新时可复用，不按字幕元素重复请求。 */
    public record LiveMetadata(String roomId, String title, String parentAreaName, String areaName,
            String liveTime) {
        public LiveMetadata {
            roomId = safeMetadataText(roomId, 32);
            title = safeMetadataText(title, 256);
            parentAreaName = safeMetadataText(parentAreaName, 64);
            areaName = safeMetadataText(areaName, 64);
            liveTime = safeMetadataText(liveTime, 64);
        }

        public static LiveMetadata empty(String roomId) {
            return new LiveMetadata(roomId, "", "", "", "");
        }
    }

    /** 直播间解析结果；{@code roomId} 为 room_init 归一化后的真实房间号。 */
    public record LiveRoom(String roomId, int liveStatus, List<LiveStream> streams, LiveMetadata metadata) {
        public LiveRoom {
            streams = streams != null ? List.copyOf(streams) : List.of();
            metadata = metadata != null ? metadata : LiveMetadata.empty(roomId);
        }

        public LiveRoom(String roomId, int liveStatus, List<LiveStream> streams) {
            this(roomId, liveStatus, streams, LiveMetadata.empty(roomId));
        }

        public boolean isLive() {
            return liveStatus == LIVE_STATUS_LIVE || liveStatus == LIVE_STATUS_CAROUSEL;
        }

        /** FLV 直连地址，按 B站返回的 CDN 顺序排列。 */
        public List<String> flvUrls() {
            return streams.stream().filter(stream -> stream.isFlv()).map(stream -> stream.url()).toList();
        }

        /** HLS 播放列表地址，供 NetMusic 通用 m3u8 路径兜底。 */
        public List<String> hlsUrls() {
            return streams.stream().filter(stream -> stream.isHls()).map(stream -> stream.url()).toList();
        }
    }

    /** 直播间号只允许纯数字，避免占位 URL 被拼进任意地址。 */
    public static boolean isValidRoomId(String roomId) {
        if (roomId == null || roomId.isBlank() || roomId.length() > 16) {
            return false;
        }
        for (int i = 0; i < roomId.length(); i++) {
            if (roomId.charAt(i) < '0' || roomId.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    public static LiveRoom resolve(String roomId) throws IOException {
        if (!isValidRoomId(roomId)) {
            throw new IOException("非法的直播间号: " + roomId);
        }

        // 文档要求取流接口使用真实房间号：先经 room_init 把短号归一化，顺带拿到开播状态。
        RoomInit init = requestRoomInit(roomId);
        String realRoomId = init != null && init.roomId() > 0 ? Long.toString(init.roomId()) : roomId;

        JsonObject data = requestPlayInfo(realRoomId);
        int liveStatus = data.has("live_status")
                ? optInt(data, "live_status", LIVE_STATUS_OFFLINE)
                : (init != null ? init.liveStatus() : LIVE_STATUS_OFFLINE);
        List<LiveStream> streams = parseStreams(data);
        if (streams.isEmpty()) {
            String legacy = requestLegacyFlvUrl(realRoomId);
            if (legacy != null) {
                streams = List.of(new LiveStream("http_stream", "flv", "", legacy));
            }
        }
        return new LiveRoom(realRoomId, liveStatus, streams, requestRoomMetadata(realRoomId));
    }

    private record RoomInit(long roomId, int liveStatus) {
    }

    /**
     * 只查开播状态（room_init 单次调用），供服务端在开始播放前校验。
     *
     * @return 直播状态；接口不可用（网络故障等）时返回 -1，调用方应放行避免误伤
     * @throws IOException 直播间号非法或不存在
     */
    public static int queryLiveStatus(String roomId) throws IOException {
        if (!isValidRoomId(roomId)) {
            throw new IOException("非法的直播间号: " + roomId);
        }
        RoomInit init = requestRoomInit(roomId);
        return init != null ? init.liveStatus() : -1;
    }

    /** {@code room_init}：短号转真实房间号；接口异常时返回 null，由上层用原始输入继续。 */
    private static RoomInit requestRoomInit(String roomId) throws IOException {
        JsonObject root;
        try {
            root = getJson(ROOM_INIT_API + "?id=" + roomId);
        } catch (IOException e) {
            return null;
        }
        int code = optInt(root, "code", -1);
        if (code == 60004) {
            throw new IOException("直播间不存在: " + roomId);
        }
        if (code != 0) {
            return null;
        }
        JsonObject data = optObject(root, "data");
        if (data == null) {
            return null;
        }
        long realRoomId = optLong(data, "room_id", 0L);
        return new RoomInit(realRoomId, optInt(data, "live_status", LIVE_STATUS_OFFLINE));
    }

    private static LiveMetadata requestRoomMetadata(String roomId) {
        long now = System.nanoTime();
        CachedLiveMetadata cached = ROOM_METADATA.get(roomId);
        if (cached != null && now - cached.storedNanos() >= 0L
                && now - cached.storedNanos() < ROOM_METADATA_TTL_NANOS) {
            return cached.metadata();
        }
        try {
            LiveMetadata metadata = parseRoomMetadata(getJson(ROOM_INFO_API + "?room_id=" + roomId), roomId);
            cacheRoomMetadata(roomId, new CachedLiveMetadata(metadata, now), now);
            return metadata;
        } catch (IOException | RuntimeException ignored) {
            return cached != null ? cached.metadata() : LiveMetadata.empty(roomId);
        }
    }

    private static void cacheRoomMetadata(String roomId, CachedLiveMetadata metadata, long now) {
        if (ROOM_METADATA.size() >= ROOM_METADATA_CACHE_LIMIT) {
            ROOM_METADATA.entrySet().removeIf(entry -> {
                long age = now - entry.getValue().storedNanos();
                return age < 0L || age >= ROOM_METADATA_TTL_NANOS;
            });
        }
        while (ROOM_METADATA.size() >= ROOM_METADATA_CACHE_LIMIT) {
            String oldestKey = ROOM_METADATA.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(entry -> entry.getValue().storedNanos()))
                    .map(java.util.Map.Entry::getKey).orElse(null);
            if (oldestKey == null || ROOM_METADATA.remove(oldestKey) == null) {
                break;
            }
        }
        ROOM_METADATA.put(roomId, metadata);
    }

    static LiveMetadata parseRoomMetadata(JsonObject root, String fallbackRoomId) throws IOException {
        if (optInt(root, "code", -1) != 0) {
            throw new IOException("B站直播房间信息接口返回错误: code=" + optInt(root, "code", -1));
        }
        JsonObject data = optObject(root, "data");
        if (data == null) {
            throw new IOException("B站直播房间信息接口未返回 data");
        }
        long numericRoomId = optLong(data, "room_id", 0L);
        String roomId = numericRoomId > 0L ? Long.toString(numericRoomId) : fallbackRoomId;
        return new LiveMetadata(roomId, optString(data, "title", ""),
                optString(data, "parent_area_name", ""), optString(data, "area_name", ""),
                optString(data, "live_time", ""));
    }

    /**
     * 从 {@code getRoomPlayInfo} 的 data 节点提取全部可播放地址。
     *
     * <p>
     * 顺序为 FLV、HLS/TS、HLS/fMP4；同一封装内保持 B站返回的 CDN 优先级。
     * </p>
     */
    public static List<LiveStream> parseStreams(JsonObject data) {
        JsonArray streamArray = playurlStreams(data);
        if (streamArray == null) {
            return List.of();
        }

        List<RankedStream> ranked = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        for (JsonElement streamElement : streamArray) {
            if (!streamElement.isJsonObject()) {
                continue;
            }
            JsonObject stream = streamElement.getAsJsonObject();
            String protocol = optString(stream, "protocol_name", "");
            for (JsonElement formatElement : optArray(stream, "format")) {
                if (!formatElement.isJsonObject()) {
                    continue;
                }
                JsonObject format = formatElement.getAsJsonObject();
                String formatName = optString(format, "format_name", "");
                int rank = rank(protocol, formatName);
                if (rank < 0) {
                    continue;
                }
                collectCodecUrls(format, protocol, formatName, rank, ranked, seenUrls);
            }
        }

        ranked.sort(Comparator.comparingInt(entry -> entry.rank()));
        return ranked.stream().map(entry -> entry.stream()).toList();
    }

    private static void collectCodecUrls(JsonObject format, String protocol, String formatName, int rank,
            List<RankedStream> ranked, Set<String> seenUrls) {
        for (JsonElement codecElement : optArray(format, "codec")) {
            if (!codecElement.isJsonObject()) {
                continue;
            }
            JsonObject codec = codecElement.getAsJsonObject();
            String codecName = optString(codec, "codec_name", "");
            // 防御：即使请求了 codec=0，也过滤掉服务端可能塞回来的 HEVC 流。
            if ("hevc".equalsIgnoreCase(codecName)) {
                continue;
            }
            String baseUrl = optString(codec, "base_url", "");
            for (JsonElement urlElement : optArray(codec, "url_info")) {
                if (!urlElement.isJsonObject()) {
                    continue;
                }
                JsonObject urlInfo = urlElement.getAsJsonObject();
                String full = optString(urlInfo, "host", "") + baseUrl + optString(urlInfo, "extra", "");
                if (!full.startsWith("http") || !seenUrls.add(full)) {
                    continue;
                }
                ranked.add(new RankedStream(rank, new LiveStream(protocol, formatName, codecName, full)));
            }
        }
    }

    /** 越小越优先；返回 -1 表示本模组不处理该封装。 */
    private static int rank(String protocol, String formatName) {
        if ("http_stream".equals(protocol) && "flv".equals(formatName)) {
            return 0;
        }
        if ("http_hls".equals(protocol) && "ts".equals(formatName)) {
            return 1;
        }
        if ("http_hls".equals(protocol) && "fmp4".equals(formatName)) {
            return 2;
        }
        return -1;
    }

    private static JsonArray playurlStreams(JsonObject data) {
        JsonObject playurlInfo = optObject(data, "playurl_info");
        JsonObject playurl = playurlInfo != null ? optObject(playurlInfo, "playurl") : null;
        if (playurl == null || !playurl.has("stream") || !playurl.get("stream").isJsonArray()) {
            return null;
        }
        return playurl.getAsJsonArray("stream");
    }

    private static JsonObject requestPlayInfo(String roomId) throws IOException {
        // codec=0 只要 AVC：本模组的原生解码不含 HEVC，直播画面也按 H.264 设计。
        String url = PLAY_INFO_API + "?room_id=" + roomId
                + "&protocol=0,1&format=0,1,2&codec=0&qn=10000&platform=web&ptype=8";
        JsonObject root = getJson(url);
        int code = optInt(root, "code", -1);
        if (code != 0) {
            throw new IOException("B站直播接口返回错误: code=" + code + " message=" + optString(root, "message", ""));
        }
        JsonObject data = optObject(root, "data");
        if (data == null) {
            throw new IOException("B站直播接口未返回房间数据");
        }
        return data;
    }

    /** 旧版接口只返回 FLV 直链，作为新接口无结果时的兜底。 */
    private static String requestLegacyFlvUrl(String roomId) {
        try {
            JsonObject root = getJson(LEGACY_PLAY_URL_API + "?cid=" + roomId + "&platform=web&qn=0");
            JsonObject data = optObject(root, "data");
            if (data == null) {
                return null;
            }
            for (JsonElement element : optArray(data, "durl")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                String url = optString(element.getAsJsonObject(), "url", "");
                if (url.startsWith("http")) {
                    return url;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 兜底接口失败时保持主接口的错误语义。
        }
        return null;
    }

    private static JsonObject getJson(String url) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(API_TIMEOUT)
                .GET();
        BiliRequestHeaders.applyLiveHeaders(builder, null);
        try {
            HttpResponse<String> response = CancellableHttpRequestScope.sendOneBlocking(BiliWbiSigner.HTTP,
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                    HttpRequestCloseDiagnostics.global(), "bili-api-live-resolve");
            if (response.statusCode() != 200) {
                throw new IOException("B站直播接口 HTTP " + response.statusCode());
            }
            JsonElement parsed = JsonParser.parseString(response.body());
            if (!parsed.isJsonObject()) {
                throw new IOException("B站直播接口返回了非 JSON 对象");
            }
            return parsed.getAsJsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("解析直播地址时被中断", e);
        } catch (RuntimeException e) {
            throw new IOException("解析直播地址失败: " + e.getMessage(), e);
        }
    }

    public static String describeLiveStatus(int liveStatus) {
        return switch (liveStatus) {
            case LIVE_STATUS_LIVE -> "直播中";
            case LIVE_STATUS_CAROUSEL -> "轮播中";
            case LIVE_STATUS_OFFLINE -> "未开播";
            default -> "未知状态(" + liveStatus + ")";
        };
    }

    private static JsonObject optObject(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key)
                : null;
    }

    private static JsonArray optArray(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonArray()
                ? parent.getAsJsonArray(key)
                : new JsonArray();
    }

    private static String optString(JsonObject parent, String key, String fallback) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return parent.get(key).getAsString().trim();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int optInt(JsonObject parent, String key, int fallback) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return parent.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long optLong(JsonObject parent, String key, long fallback) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return parent.get(key).getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String safeMetadataText(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private record RankedStream(int rank, LiveStream stream) {
    }

    private record CachedLiveMetadata(LiveMetadata metadata, long storedNanos) {
    }
}
