package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.stream.CancellableHttpRequestScope;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;

/**
 * B站二维码登录状态机
 */
public final class BiliLoginManager implements AutoCloseable {
    private static final java.util.logging.Logger PERSISTENCE_LOGGER = java.util.logging.Logger
            .getLogger(BiliLoginManager.class.getName() + ".persistence");
    private static final URI DEFAULT_GENERATE_URI = URI.create(
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate");
    private static final String DEFAULT_POLL_ENDPOINT =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";

    private final CancellableHttpRequestScope requests = new CancellableHttpRequestScope(
            HttpRequestCloseDiagnostics.global());
    private final HttpClient httpClient;
    private final URI generateUri;
    private final String pollEndpoint;
    private final ExecutorService qrExecutor;
    private CancellableTaskFuture<byte[]> qrTask;
    private CompletableFuture<byte[]> qrImageFuture;
    private String qrImageInput;
    private long qrImageGeneration;
    private final boolean applyProductionHeaders;
    private final BiliCredentialStore credentialStore;
    private volatile String qrcodeKey;
    private volatile String qrUrl;
    private CompletableFuture<State> generateFuture;
    private CompletableFuture<State> pollFuture;

    public BiliLoginManager() {
        this(BiliWbiSigner.HTTP, DEFAULT_GENERATE_URI, DEFAULT_POLL_ENDPOINT, true,
                BiliCredentialStore.defaults(), LocalQrImageEncoder.executor());
    }

    BiliLoginManager(HttpClient httpClient, URI generateUri, String pollEndpoint,
            BiliCredentialStore credentialStore) {
        this(httpClient, generateUri, pollEndpoint, false, credentialStore, LocalQrImageEncoder.executor());
    }

    private BiliLoginManager(HttpClient httpClient, URI generateUri, String pollEndpoint,
            boolean applyProductionHeaders, BiliCredentialStore credentialStore, ExecutorService qrExecutor) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.generateUri = Objects.requireNonNull(generateUri, "generateUri");
        this.pollEndpoint = requireEndpoint(pollEndpoint, "pollEndpoint");
        this.qrExecutor = Objects.requireNonNull(qrExecutor, "qrExecutor");
        this.applyProductionHeaders = applyProductionHeaders;
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
    }

    BiliLoginManager(HttpClient client, URI generate, String poll, BiliCredentialStore store,
            ExecutorService qrExecutor) {
        this(client, generate, poll, false, store, qrExecutor);
    }

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
        SUCCESS_SESSION_ONLY,
        EXPIRED,
        FAILED,
    }

    public synchronized CompletableFuture<State> generate() {
        if (requests.isClosed()) {
            return CompletableFuture.completedFuture(State.FAILED);
        }
        if (generateFuture != null && !generateFuture.isDone()) {
            return generateFuture;
        }
        cancelQrImage();
        qrcodeKey = null;
        qrUrl = null;
        try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(generateUri)
                        .timeout(Duration.ofSeconds(10))
                        .GET();
                applyHeaders(builder, generateUri);
                HttpRequest req = builder.build();
                generateFuture = requests.sendAsync(httpClient, req,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), "bili-login-generate")
                        .handle((resp, error) -> parseGeneratedResponse(resp, error));
                return generateFuture;
        } catch (RuntimeException error) {
            logger().error("生成二维码请求启动异常", error);
            return CompletableFuture.completedFuture(State.FAILED);
        }
    }

    private State parseGeneratedResponse(HttpResponse<String> resp, Throwable error) {
        if (error != null) {
            if (!isCancellation(error)) {
                logger().error("生成二维码异常", error);
            }
            return State.FAILED;
        }
        if (requests.isClosed() || resp == null || resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return State.FAILED;
        }
        try {
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                int code = root.get("code").getAsInt();
                if (code != 0) {
                    logger().error("生成二维码失败: code={}", code);
                    return State.FAILED;
                }
                JsonObject data = root.getAsJsonObject("data");
                var keyValue = data.get("qrcode_key");
                var urlValue = data.get("url");
                if (keyValue == null || !keyValue.isJsonPrimitive() || !keyValue.getAsJsonPrimitive().isString()
                        || urlValue == null || !urlValue.isJsonPrimitive() || !urlValue.getAsJsonPrimitive().isString()) {
                    return State.FAILED;
                }
                String key = keyValue.getAsString();
                String url = urlValue.getAsString();
                if (key.isBlank() || url.isBlank() || requests.isClosed()) return State.FAILED;
                this.qrcodeKey = key;
                this.qrUrl = url;
                return State.PENDING;
        } catch (Exception e) {
            logger().error("生成二维码响应解析异常", e);
            return State.FAILED;
        }
    }

    public synchronized CompletableFuture<State> poll() {
        if (requests.isClosed() || qrcodeKey == null || qrcodeKey.isBlank()) {
            return CompletableFuture.completedFuture(State.FAILED);
        }
        if (pollFuture != null && !pollFuture.isDone()) {
            return pollFuture;
        }
        try {
                String url = appendQuery(pollEndpoint, "qrcode_key="
                        + URLEncoder.encode(qrcodeKey, StandardCharsets.UTF_8));
                URI target = URI.create(url);
                HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                        .timeout(Duration.ofSeconds(10))
                        .GET();
                applyHeaders(builder, target);
                HttpRequest req = builder.build();
                pollFuture = requests.sendAsync(httpClient, req,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), "bili-login-poll")
                        .handle((resp, error) -> parsePollResponse(resp, error));
                return pollFuture;
        } catch (RuntimeException error) {
            logger().error("轮询登录请求启动异常", error);
            return CompletableFuture.completedFuture(State.FAILED);
        }
    }

    private State parsePollResponse(HttpResponse<String> resp, Throwable error) {
        if (error != null) {
            if (!isCancellation(error)) {
                logger().error("轮询登录状态异常", error);
            }
            return requests.isClosed() ? State.FAILED : State.PENDING;
        }
        if (requests.isClosed()) return State.FAILED;
        if (resp == null || resp.statusCode() < 200 || resp.statusCode() >= 300) return State.PENDING;
        try {
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                int code = root.get("code").getAsInt();
                if (code != 0) return State.PENDING;
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
                            BiliCredentialStore.SaveResult saveResult = BiliConfig.saveCredentials(credentialStore,
                                    new BiliCredentialStore.Credentials(sessdata, BiliApiClient.webCookie));
                            if (saveResult.saved()) {
                                PERSISTENCE_LOGGER.info("B站登录成功，登录凭据已按本机加密保存，Cookie 字段数="
                                        + cookiePairs.size());
                                return State.SUCCESS;
                            }
                            PERSISTENCE_LOGGER.warning("B站登录成功，但加密凭据保存失败（" + saveResult.status()
                                    + "）；登录仅在本次游戏中有效");
                            return State.SUCCESS_SESSION_ONLY;
                        }
                        logger().warn("登录成功但未找到 SESSDATA cookie, Set-Cookie 字段数={}", cookiePairs.size());
                        return State.FAILED;
                    case 86038: // 二维码过期
                        return State.EXPIRED;
                    default:
                        logger().warn("未知轮询状态: dataCode={}, code={}", dataCode, code);
                        return State.PENDING;
                }
        } catch (Exception e) {
            logger().error("轮询登录响应解析异常", e);
            return State.PENDING;
        }
    }

    public synchronized CompletableFuture<byte[]> loadQrImage(String qrContentUrl) {
        if (requests.isClosed() || qrContentUrl == null || qrContentUrl.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        if (qrImageFuture != null && qrContentUrl.equals(qrImageInput) && !qrImageFuture.isDone()) {
            return qrImageFuture;
        }
        cancelQrImage();
        long generation = qrImageGeneration;
        qrImageInput = qrContentUrl;
        qrTask = LocalQrImageEncoder.encodeAsync(qrContentUrl, qrExecutor);
        qrImageFuture = qrTask.handle((bytes, error) -> {
            synchronized (BiliLoginManager.this) {
                if (requests.isClosed() || generation != qrImageGeneration) return null;
                if (error != null) {
                    if (!isCancellation(error)) logger().warn("Local QR image unavailable");
                    return null;
                }
                return bytes;
            }
        });
        return qrImageFuture;
    }

    private void cancelQrImage() {
        qrImageGeneration++;
        if (qrTask != null) qrTask.cancel(true);
        qrTask = null;
        qrImageFuture = null;
        qrImageInput = null;
    }

    @Override
    public synchronized void close() {
        requests.close();
        cancelQrImage();
    }

    int activeRequestCount() {
        return requests.activeRequests();
    }

    private static boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CancellationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String requireEndpoint(String endpoint, String name) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return endpoint;
    }

    private static String appendQuery(String endpoint, String query) {
        return endpoint + (endpoint.contains("?") ? "&" : "?") + query;
    }

    private void applyHeaders(HttpRequest.Builder builder, URI target) {
        if (applyProductionHeaders) {
            BiliRequestHeaders.applyWebApiHeaders(builder, target);
        }
    }

    private static Logger logger() {
        return LoggerHolder.INSTANCE;
    }

    private static final class LoggerHolder {
        private static final Logger INSTANCE = LogUtils.getLogger();

        private LoggerHolder() {
        }
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
