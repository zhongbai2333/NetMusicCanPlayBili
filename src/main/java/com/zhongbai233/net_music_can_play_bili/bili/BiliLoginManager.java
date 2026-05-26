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
import java.util.concurrent.CompletableFuture;

/**
 * B站二维码登录状态机
 */
public final class BiliLoginManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

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
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", UA)
                        .timeout(Duration.ofSeconds(10))
                        .GET().build();
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
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", UA)
                        .timeout(Duration.ofSeconds(10))
                        .GET().build();
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
                        // 从 Set-Cookie 中提取 SESSDATA
                        var headers = resp.headers();
                        for (String setCookie : headers.allValues("Set-Cookie")) {
                            if (setCookie.startsWith("SESSDATA=")) {
                                String sessdata = setCookie.substring("SESSDATA=".length());
                                int semicolon = sessdata.indexOf(';');
                                if (semicolon > 0)
                                    sessdata = sessdata.substring(0, semicolon);
                                BiliApiClient.sessdata = sessdata;
                                BiliConfig.save();
                                LOGGER.info("B站登录成功, SESSDATA 已保存到本地");
                                return State.SUCCESS;
                            }
                        }
                        LOGGER.warn("登录成功但未找到 SESSDATA cookie");
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
}
