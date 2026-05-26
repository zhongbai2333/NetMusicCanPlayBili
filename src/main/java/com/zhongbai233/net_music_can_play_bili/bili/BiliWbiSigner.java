package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * B站 WBI 签名
 */
public final class BiliWbiSigner {

    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 52, 44, 34,
    };

    public static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static volatile String cachedKey;
    private static volatile long keyExpiresAt;
    private static final Object LOCK = new Object();

    private BiliWbiSigner() {
    }

    /**
     * 获取 WBI 签名密钥
     */
    public static String getWbiKey() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedKey != null && now < keyExpiresAt) {
            return cachedKey;
        }
        synchronized (LOCK) {
            if (cachedKey != null && now < keyExpiresAt) {
                return cachedKey;
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.bilibili.com/x/web-interface/nav"))
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new RuntimeException("B站 nav 接口返回 HTTP " + resp.statusCode());
            }

            JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonObject wbi = body.getAsJsonObject("data").getAsJsonObject("wbi_img");

            String imgKey = extractFilename(wbi.get("img_url").getAsString());
            String subKey = extractFilename(wbi.get("sub_url").getAsString());
            String mixed = imgKey + subKey;

            StringBuilder key = new StringBuilder(32);
            for (int idx : MIXIN_KEY_ENC_TAB) {
                if (idx < mixed.length()) {
                    key.append(mixed.charAt(idx));
                }
            }

            cachedKey = key.substring(0, 32);
            keyExpiresAt = System.currentTimeMillis() + 30 * 60 * 1000L;
            return cachedKey;
        }
    }

    private static String extractFilename(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static Map<String, String> signParams(Map<String, String> params) throws Exception {
        String key = getWbiKey();
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.put("wts", String.valueOf(System.currentTimeMillis() / 1000));

        // 构建排序后的 query string，追加 key 算 MD5
        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (qs.length() > 0)
                qs.append('&');
            qs.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            qs.append('=');
            qs.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        qs.append(key);

        sorted.put("w_rid", md5(qs.toString()));
        return sorted;
    }

    public static String buildQuery(Map<String, String> signedParams) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : signedParams.entrySet()) {
            if (sb.length() > 0)
                sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static String md5(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(32);
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
