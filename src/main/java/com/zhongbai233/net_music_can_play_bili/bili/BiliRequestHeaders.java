package com.zhongbai233.net_music_can_play_bili.bili;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpRequest;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** B站 Web/API/CDN 请求头集中入口，统一 UA、Referer 和登录 Cookie。 */
public final class BiliRequestHeaders {
    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0";
    private static final String[] DESKTOP_USER_AGENT_PRESETS = {
            DEFAULT_USER_AGENT,
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/149.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/148.0.0.0 Safari/537.36 Edg/148.0.0.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/148.0.0.0 Safari/537.36",
    };
    public static final String WEB_REFERER = "https://www.bilibili.com/";
    public static final String WEB_ORIGIN = "https://www.bilibili.com";
    /** 直播 API 与直播 CDN 校验的是直播站点来源，与点播站点不同。 */
    public static final String LIVE_REFERER = "https://live.bilibili.com/";
    public static final String LIVE_ORIGIN = "https://live.bilibili.com";
    public static final String ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8";
    private static final Set<String> BILI_COOKIE_DOMAINS = Set.of(
            "bilibili.com",
            "bilivideo.com",
            "bilivideo.cn",
            "hdslb.com");
    private static final AtomicInteger UA_INDEX = new AtomicInteger(
            ThreadLocalRandom.current().nextInt(DESKTOP_USER_AGENT_PRESETS.length));
    private static final AtomicInteger BILI_CDN_403_COUNT = new AtomicInteger();
    private static final int UA_SWITCH_403_THRESHOLD = Math.max(1,
            NcpbSystemProperties.intValue("ncpb.bili.user_agent.switch_403_threshold",
                    "ncpb.ncpb.bili.user_agent.switch_403_threshold", 3));

    private BiliRequestHeaders() {
    }

    public static String userAgent() {
        String override = BiliConfig.userAgent;
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        if (!BiliConfig.rotateUserAgent) {
            return DEFAULT_USER_AGENT;
        }
        int index = Math.floorMod(UA_INDEX.get(), DESKTOP_USER_AGENT_PRESETS.length);
        return DESKTOP_USER_AGENT_PRESETS[index];
    }

    public static void recordBiliCdnResponse(URL url, int statusCode) {
        if (!BiliConfig.rotateUserAgent || !isBiliHost(url)) {
            return;
        }
        if (statusCode == 403) {
            int failures = BILI_CDN_403_COUNT.incrementAndGet();
            if (failures >= UA_SWITCH_403_THRESHOLD) {
                int next = UA_INDEX.updateAndGet(value -> Math.floorMod(value + 1, DESKTOP_USER_AGENT_PRESETS.length));
                BILI_CDN_403_COUNT.set(0);
                logger().warn("Bilibili CDN returned HTTP 403 {} times; switching User-Agent preset to {}/{}: {}",
                        failures, next + 1, DESKTOP_USER_AGENT_PRESETS.length, DESKTOP_USER_AGENT_PRESETS[next]);
            }
        } else if (statusCode == 200 || statusCode == 206) {
            BILI_CDN_403_COUNT.set(0);
        }
    }

    /**
     * Applies browser-like public headers without credentials.
     *
     * <p>This is intentionally used for Bilibili short links: their redirect
     * resolution does not require a login Cookie, and redirect targets must not
     * inherit one.</p>
     */
    public static HttpRequest.Builder applyPublicWebHeaders(HttpRequest.Builder builder) {
        return applyPublicWebHeaders(builder, userAgent());
    }

    static HttpRequest.Builder applyPublicWebHeaders(HttpRequest.Builder builder, String userAgent) {
        builder.header("User-Agent", userAgent)
                .header("Referer", WEB_REFERER)
                .header("Accept-Language", ACCEPT_LANGUAGE);
        return builder;
    }

    /** Applies Web/API headers and only attaches credentials to a matching trusted HTTPS target. */
    public static HttpRequest.Builder applyWebApiHeaders(HttpRequest.Builder builder, URI target) {
        applyWebApiHeaders(builder, target, userAgent(), cookieHeader());
        return builder;
    }

    static HttpRequest.Builder applyWebApiHeaders(HttpRequest.Builder builder, URI target, String userAgent,
            String cookie) {
        applyPublicWebHeaders(builder, userAgent);
        addCookieIfTrusted(builder, target, cookie);
        return builder;
    }

    /**
     * Compatibility overload for existing callers. The builder's actual target is still
     * resolved and checked against the same HTTPS Cookie-target policy.
     */
    public static HttpRequest.Builder applyWebApiHeaders(HttpRequest.Builder builder) {
        URI target;
        try {
            target = builder.build().uri();
        } catch (IllegalStateException ignored) {
            target = null;
        }
        return applyWebApiHeaders(builder, target);
    }

    public static HttpRequest.Builder applyBiliCdnHeaders(HttpRequest.Builder builder, URL url) {
        return applyBiliCdnHeaders(builder, url, userAgent(), cookieHeader());
    }

    static HttpRequest.Builder applyBiliCdnHeaders(HttpRequest.Builder builder, URL url, String userAgent,
            String cookie) {
        builder.header("User-Agent", userAgent)
                .header("Referer", refererFor(url))
                .header("Origin", originFor(url))
                .header("Accept", "*/*")
                .header("Accept-Language", ACCEPT_LANGUAGE);
        addCookieIfTrusted(builder, toUri(url), cookie);
        return builder;
    }

    /** 直播 API 与直播流 CDN 共用的请求头；Referer/Origin 必须指向直播站点。 */
    public static HttpRequest.Builder applyLiveHeaders(HttpRequest.Builder builder, URL url) {
        return applyLiveHeaders(builder, url, userAgent(), cookieHeader());
    }

    static HttpRequest.Builder applyLiveHeaders(HttpRequest.Builder builder, URL url, String userAgent,
            String cookie) {
        builder.header("User-Agent", userAgent)
                .header("Referer", LIVE_REFERER)
                .header("Origin", LIVE_ORIGIN)
                .header("Accept", "*/*")
                .header("Accept-Language", ACCEPT_LANGUAGE);
        addCookieIfTrusted(builder, toUri(url), cookie);
        return builder;
    }

    /** 直播 API 请求的 URI 入口；Cookie 与目标 URI 做同一域名边界校验。 */
    public static HttpRequest.Builder applyLiveHeaders(HttpRequest.Builder builder, URI uri) {
        return applyLiveHeaders(builder, uri, userAgent(), cookieHeader());
    }

    static HttpRequest.Builder applyLiveHeaders(HttpRequest.Builder builder, URI uri, String userAgent,
            String cookie) {
        builder.header("User-Agent", userAgent)
                .header("Referer", LIVE_REFERER)
                .header("Origin", LIVE_ORIGIN)
                .header("Accept", "*/*")
                .header("Accept-Language", ACCEPT_LANGUAGE);
        addCookieIfTrusted(builder, uri, cookie);
        return builder;
    }

    static void addCookieIfTrusted(HttpRequest.Builder builder, URI declaredTarget, String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return;
        }
        URI actualTarget = requestTarget(builder);
        if (!isTrustedCookieTarget(actualTarget) || !isTrustedCookieTarget(declaredTarget)
                || !sameAuthority(actualTarget, declaredTarget)) {
            return;
        }
        builder.header("Cookie", cookie.trim());
    }

    static boolean isTrustedCookieTarget(URI target) {
        if (target == null || target.isOpaque() || target.getRawUserInfo() != null
                || !"https".equalsIgnoreCase(target.getScheme())) {
            return false;
        }
        int port = target.getPort();
        return (port == -1 || port == 443) && isBiliHost(target);
    }

    private static URI requestTarget(HttpRequest.Builder builder) {
        try {
            return builder.build().uri();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static boolean sameAuthority(URI actualTarget, URI declaredTarget) {
        String actualHost = actualTarget.getHost();
        String declaredHost = declaredTarget.getHost();
        return actualHost != null && declaredHost != null
                && normalizeHost(actualHost).equals(normalizeHost(declaredHost))
                && effectiveHttpsPort(actualTarget) == effectiveHttpsPort(declaredTarget);
    }

    private static int effectiveHttpsPort(URI target) {
        return target.getPort() == -1 ? 443 : target.getPort();
    }

    private static URI toUri(URL url) {
        if (url == null) {
            return null;
        }
        try {
            return url.toURI();
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    public static String cookieHeader() {
        String fullCookie = BiliApiClient.webCookie;
        if (fullCookie != null && !fullCookie.isBlank()) {
            return fullCookie.trim();
        }
        String sessdata = BiliApiClient.sessdata;
        return sessdata == null || sessdata.isBlank() ? "" : "SESSDATA=" + sessdata.trim();
    }

    public static String refererFor(URL url) {
        return isBiliHost(url) ? WEB_REFERER : "https://" + safeHost(url) + "/";
    }

    public static String originFor(URL url) {
        return isBiliHost(url) ? WEB_ORIGIN : "https://" + safeHost(url);
    }

    public static boolean isBiliHost(URL url) {
        return url != null && isBiliHost(url.getHost());
    }

    public static boolean isBiliHost(URI uri) {
        return uri != null && isBiliHost(uri.getHost());
    }

    static boolean isBiliHost(String host) {
        if (host == null) {
            return false;
        }
        String lower = normalizeHost(host);
        if (lower.isBlank() || lower.startsWith(".") || lower.contains("..")) {
            return false;
        }
        for (String domain : BILI_COOKIE_DOMAINS) {
            if (lower.equals(domain) || lower.endsWith('.' + domain)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeHost(String host) {
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static String safeHost(URL url) {
        String host = url.getHost();
        return host == null || host.isBlank() ? "localhost" : host;
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
