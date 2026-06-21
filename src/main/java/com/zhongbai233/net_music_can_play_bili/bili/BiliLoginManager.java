package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * B站二维码登录状态机
 */
public final class BiliLoginManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private String qrcodeKey;
    private String qrUrl;

    public String getQrUrl() {
        return qrUrl;
    }

    public String getQrcodeKey() {
        return qrcodeKey;
    }

    public enum State {
        PENDING,
        SCANNED,
        SUCCESS,
        EXPIRED,
        FAILED,
    }

    public CompletableFuture<State> generate() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET();
                BiliRequestHeaders.applyWebApiHeaders(builder);
                HttpRequest req = builder.build();
                HttpResponse<String> resp = BiliWbiSigner.HTTP.send(req,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                int code = root.get("code").getAsInt();
                if (code != 0) {
                    LOGGER.error("生成二维码失败: code={}", code);
                    return State.FAILED;
                }
                JsonObject data = root.getAsJsonObject("data");
                this.qrcodeKey = data.get("qrcode_key").getAsString();
                this.qrUrl = data.get("url").getAsString();
                return State.PENDING;
            } catch (Exception e) {
                LOGGER.error("生成二维码异常", e);
                return State.FAILED;
            }
        });
    }

    public CompletableFuture<State> poll() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key="
                        + URLEncoder.encode(qrcodeKey, StandardCharsets.UTF_8);
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET();
                BiliRequestHeaders.applyWebApiHeaders(builder);
                HttpRequest req = builder.build();
                HttpResponse<String> resp = BiliWbiSigner.HTTP.send(req,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                int code = root.get("code").getAsInt();
                int dataCode = root.has("data") && !root.get("data").isJsonNull()
                        ? root.getAsJsonObject("data").get("code").getAsInt()
                        : -1;

                switch (dataCode) {
                    case 86101:
                        return State.PENDING;
                    case 86090:
                        return State.SCANNED;
                    case 0:
                        // 从 Set-Cookie 中提取尽可能完整的 Web Cookie，SESSDATA 负责登录态，
                        // buvid/bili_jct/DedeUserID 等字段可降低后续 Web API/CDN 风控概率。
                        var headers = resp.headers();
                        Map<String, String> cookiePairs = new LinkedHashMap<>();
                        String sessdata = "";
                        for (String setCookie : headers.allValues("Set-Cookie")) {
                            CookiePair pair = parseCookiePair(setCookie);
                            if (pair != null) {
                                cookiePairs.put(pair.name(), pair.value());
                                if ("SESSDATA".equals(pair.name())) {
                                    sessdata = pair.value();
                                }
                            }
                        }
                        if (!sessdata.isBlank()) {
                            BiliApiClient.sessdata = sessdata;
                            BiliApiClient.webCookie = buildCookieHeader(cookiePairs);
                            BiliConfig.save();
                            LOGGER.info("B站登录成功, 已保存 Web Cookie 字段数={}", cookiePairs.size());
                            return State.SUCCESS;
                        }
                        LOGGER.warn("登录成功但未找到 SESSDATA cookie, Set-Cookie 字段数={}", cookiePairs.size());
                        return State.FAILED;
                    case 86038: // 二维码过期
                        return State.EXPIRED;
                    default:
                        LOGGER.warn("未知轮询状态: dataCode={}, code={}", dataCode, code);
                        return State.PENDING;
                }
            } catch (Exception e) {
                LOGGER.error("轮询登录状态异常", e);
                return State.PENDING;
            }
        });
    }

    private static CookiePair parseCookiePair(String setCookie) {
        if (setCookie == null || setCookie.isBlank()) {
            return null;
        }
        int semicolon = setCookie.indexOf(';');
        String pair = semicolon >= 0 ? setCookie.substring(0, semicolon) : setCookie;
        int equals = pair.indexOf('=');
        if (equals <= 0 || equals >= pair.length() - 1) {
            return null;
        }
        String name = pair.substring(0, equals).trim();
        String value = pair.substring(equals + 1).trim();
        return name.isBlank() || value.isBlank() ? null : new CookiePair(name, value);
    }

    private static String buildCookieHeader(Map<String, String> cookiePairs) {
        StringBuilder header = new StringBuilder();
        for (Map.Entry<String, String> entry : cookiePairs.entrySet()) {
            if (header.length() > 0) {
                header.append("; ");
            }
            header.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return header.toString();
    }

    private record CookiePair(String name, String value) {
    }
}
