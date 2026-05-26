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

/**
 * B站直播流 m3u8 地址解析工具。
 */
public final class BiliLiveAudioResolver {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    private BiliLiveAudioResolver() {
    }

    // 根据直播间号解析为 m3u8 链接
    public static String resolveM3u8Url(String roomId) throws Exception {
        String playUrl = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
                + "?room_id=" + roomId + "&protocol=0,1&format=0,1,2&codec=0,1";
        JsonObject playJson = getJson(playUrl);
        JsonObject data = playJson.getAsJsonObject("data");

        String m3u8 = extractBestM3u8(data);
        if (m3u8 != null)
            return m3u8;

        m3u8 = getM3u8FromLegacyApi(roomId);
        if (m3u8 != null)
            return m3u8;

        throw new RuntimeException("未获取到直播流地址");
    }

    private static String extractBestM3u8(JsonObject data) {
        JsonObject playurlInfo = data.has("playurl_info") && !data.get("playurl_info").isJsonNull()
                ? data.getAsJsonObject("playurl_info")
                : null;
        if (playurlInfo == null)
            return null;

        JsonObject playurl = playurlInfo.has("playurl") && !playurlInfo.get("playurl").isJsonNull()
                ? playurlInfo.getAsJsonObject("playurl")
                : null;
        if (playurl == null)
            return null;

        JsonArray streams = playurl.has("stream") && !playurl.get("stream").isJsonNull()
                ? playurl.getAsJsonArray("stream")
                : null;
        if (streams == null)
            return null;

        String tsFallback = null;
        for (JsonElement se : streams) {
            JsonObject s = se.getAsJsonObject();
            String proto = s.get("protocol_name").getAsString();
            if (!"http_hls".equals(proto))
                continue;
            JsonArray formats = s.getAsJsonArray("format");
            for (JsonElement fe : formats) {
                JsonObject f = fe.getAsJsonObject();
                String fname = f.get("format_name").getAsString();
                JsonArray codecs = f.getAsJsonArray("codec");
                for (JsonElement ce : codecs) {
                    JsonObject c = ce.getAsJsonObject();
                    JsonArray urls = c.getAsJsonArray("url_info");
                    String base = c.get("base_url").getAsString();
                    for (JsonElement ue : urls) {
                        JsonObject u = ue.getAsJsonObject();
                        String full = u.get("host").getAsString() + base + u.get("extra").getAsString();
                        if ("ts".equals(fname))
                            return full;
                        if (tsFallback == null)
                            tsFallback = full;
                    }
                }
            }
        }
        return tsFallback;
    }

    private static String getM3u8FromLegacyApi(String roomId) throws Exception {
        String url = "https://api.live.bilibili.com/room/v1/Room/playUrl?cid=" + roomId + "&platform=web&qn=0";
        JsonObject root = getJson(url);
        JsonArray durl = root.getAsJsonObject("data").getAsJsonArray("durl");
        if (durl != null && !durl.isEmpty()) {
            return durl.get(0).getAsJsonObject().get("url").getAsString();
        }
        return null;
    }

    private static JsonObject getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .header("Referer", "https://live.bilibili.com/")
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        HttpResponse<String> resp = BiliWbiSigner.HTTP.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

}
