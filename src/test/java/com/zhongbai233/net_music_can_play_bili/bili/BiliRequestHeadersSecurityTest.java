package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliRequestHeadersSecurityTest {
    private static final String TEST_COOKIE = "SESSDATA=test-session; bili_jct=test-csrf";

    @Test
    void cookieDomainAllowlistAcceptsOnlyExactDomainsAndRealSubdomains() {
        assertTrue(BiliRequestHeaders.isBiliHost(URI.create("https://bilibili.com/")));
        assertTrue(BiliRequestHeaders.isBiliHost(URI.create("https://api.live.bilibili.com/")));
        assertTrue(BiliRequestHeaders.isBiliHost(URI.create("https://upos-sz-mirrorcos.bilivideo.com/")));
        assertTrue(BiliRequestHeaders.isBiliHost(URI.create("https://mcdn.bilivideo.cn/")));
        assertTrue(BiliRequestHeaders.isBiliHost(URI.create("https://aisubtitle.hdslb.com/")));
        assertTrue(BiliRequestHeaders.isBiliHost(URI.create("https://API.BILIBILI.COM./")));

        assertFalse(BiliRequestHeaders.isBiliHost(URI.create("https://notbilibili.com/")));
        assertFalse(BiliRequestHeaders.isBiliHost(URI.create("https://bilibili.com.attacker.example/")));
        assertFalse(BiliRequestHeaders.isBiliHost(URI.create("https://evil-bilivideo.example/")));
        assertFalse(BiliRequestHeaders.isBiliHost(URI.create("https://hdslb.com.attacker.example/")));
        assertFalse(BiliRequestHeaders.isBiliHost(URI.create("https://mcdn.attacker.example/")));
        assertFalse(BiliRequestHeaders.isBiliHost(".bilibili.com"));
        assertFalse(BiliRequestHeaders.isBiliHost("api..bilibili.com"));
        assertFalse(BiliRequestHeaders.isBiliHost((URI) null));
    }

    @Test
    void webApiCookieRequiresAnExplicitAllowlistedTarget() {
        HttpRequest trusted = webApiRequest("https://api.bilibili.com/x/web-interface/nav");
        HttpRequest trustedExplicitPort = webApiRequest("https://api.bilibili.com:443/x/web-interface/nav");
        HttpRequest untrusted = webApiRequest("https://bilibili.com.attacker.example/collect");
        HttpRequest shortLink = webApiRequest("https://b23.tv/test-code");
        HttpRequest plaintext = webApiRequest("http://api.bilibili.com/x/web-interface/nav");
        HttpRequest nonStandardPort = webApiRequest("https://api.bilibili.com:8443/x/web-interface/nav");
        HttpRequest withUserInfo = webApiRequest("https://viewer@api.bilibili.com/x/web-interface/nav");
        HttpRequest mismatchedTarget = webApiRequest(
                "https://collector.attacker.example/", "https://api.bilibili.com/x/web-interface/nav");

        assertEquals(List.of(TEST_COOKIE), trusted.headers().allValues("Cookie"));
        assertEquals(List.of(TEST_COOKIE), trustedExplicitPort.headers().allValues("Cookie"));
        assertTrue(untrusted.headers().allValues("Cookie").isEmpty());
        assertTrue(shortLink.headers().allValues("Cookie").isEmpty());
        assertTrue(plaintext.headers().allValues("Cookie").isEmpty());
        assertTrue(nonStandardPort.headers().allValues("Cookie").isEmpty());
        assertTrue(withUserInfo.headers().allValues("Cookie").isEmpty());
        assertTrue(mismatchedTarget.headers().allValues("Cookie").isEmpty());

        HttpRequest.Builder targetlessBuilder = HttpRequest.newBuilder(URI.create("https://api.bilibili.com/"));
        BiliRequestHeaders.applyWebApiHeaders(targetlessBuilder, null, "test-agent", TEST_COOKIE);
        assertTrue(targetlessBuilder.build().headers().allValues("Cookie").isEmpty());
    }

    @Test
    void publicShortLinkHeadersKeepBrowserIdentityButNeverCredentials() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://b23.tv/test-code"));
        BiliRequestHeaders.applyPublicWebHeaders(builder, "test-agent");
        HttpRequest request = builder.build();

        assertTrue(request.headers().allValues("Cookie").isEmpty());
        assertEquals(BiliRequestHeaders.WEB_REFERER,
                request.headers().firstValue("Referer").orElseThrow());
        assertEquals(BiliRequestHeaders.ACCEPT_LANGUAGE,
                request.headers().firstValue("Accept-Language").orElseThrow());
        assertEquals("test-agent", request.headers().firstValue("User-Agent").orElseThrow());
    }

    @Test
    void dynamicCdnAndLiveHeadersRequireHttpsStandardPortAndMatchingTarget() throws Exception {
        HttpRequest trustedCdn = cdnRequest(
                "https://mcdn.bilivideo.cn/video.m4s", "https://mcdn.bilivideo.cn/video.m4s");
        HttpRequest plaintextCdn = cdnRequest(
                "http://mcdn.bilivideo.cn/video.m4s", "http://mcdn.bilivideo.cn/video.m4s");
        HttpRequest nonStandardPortCdn = cdnRequest(
                "https://mcdn.bilivideo.cn:8443/video.m4s", "https://mcdn.bilivideo.cn:8443/video.m4s");
        HttpRequest mismatchedCdn = cdnRequest(
                "https://collector.attacker.example/video.m4s", "https://mcdn.bilivideo.cn/video.m4s");

        HttpRequest trustedLiveApi = liveUriRequest(
                "https://api.live.bilibili.com/room", "https://api.live.bilibili.com/room");
        HttpRequest plaintextLiveApi = liveUriRequest(
                "http://api.live.bilibili.com/room", "http://api.live.bilibili.com/room");
        HttpRequest nonStandardPortLiveApi = liveUriRequest(
                "https://api.live.bilibili.com:8443/room", "https://api.live.bilibili.com:8443/room");
        HttpRequest mismatchedLiveApi = liveUriRequest(
                "https://collector.attacker.example/room", "https://api.live.bilibili.com/room");

        HttpRequest trustedLiveStream = liveUrlRequest(
                "https://edge.bilivideo.com/live.flv", "https://edge.bilivideo.com/live.flv");
        HttpRequest plaintextLiveStream = liveUrlRequest(
                "http://edge.bilivideo.com/live.flv", "http://edge.bilivideo.com/live.flv");
        HttpRequest mismatchedLiveStream = liveUrlRequest(
                "https://collector.attacker.example/live.flv", "https://edge.bilivideo.com/live.flv");

        assertEquals(List.of(TEST_COOKIE), trustedCdn.headers().allValues("Cookie"));
        assertTrue(plaintextCdn.headers().allValues("Cookie").isEmpty());
        assertTrue(nonStandardPortCdn.headers().allValues("Cookie").isEmpty());
        assertTrue(mismatchedCdn.headers().allValues("Cookie").isEmpty());
        assertEquals(List.of(TEST_COOKIE), trustedLiveApi.headers().allValues("Cookie"));
        assertTrue(plaintextLiveApi.headers().allValues("Cookie").isEmpty());
        assertTrue(nonStandardPortLiveApi.headers().allValues("Cookie").isEmpty());
        assertTrue(mismatchedLiveApi.headers().allValues("Cookie").isEmpty());
        assertEquals(List.of(TEST_COOKIE), trustedLiveStream.headers().allValues("Cookie"));
        assertTrue(plaintextLiveStream.headers().allValues("Cookie").isEmpty());
        assertTrue(mismatchedLiveStream.headers().allValues("Cookie").isEmpty());

        assertEquals(BiliRequestHeaders.LIVE_REFERER,
                plaintextLiveStream.headers().firstValue("Referer").orElseThrow());
        assertEquals("test-agent", plaintextLiveStream.headers().firstValue("User-Agent").orElseThrow());
    }

    private static HttpRequest webApiRequest(String rawTarget) {
        return webApiRequest(rawTarget, rawTarget);
    }

    private static HttpRequest webApiRequest(String actualTarget, String declaredTarget) {
        URI actual = URI.create(actualTarget);
        URI declared = URI.create(declaredTarget);
        HttpRequest.Builder builder = HttpRequest.newBuilder(actual);
        BiliRequestHeaders.applyWebApiHeaders(builder, declared, "test-agent", TEST_COOKIE);
        return builder.build();
    }

    private static HttpRequest cdnRequest(String actualTarget, String declaredTarget) throws Exception {
        URI actual = URI.create(actualTarget);
        HttpRequest.Builder builder = HttpRequest.newBuilder(actual);
        BiliRequestHeaders.applyBiliCdnHeaders(builder, URI.create(declaredTarget).toURL(),
                "test-agent", TEST_COOKIE);
        return builder.build();
    }

    private static HttpRequest liveUriRequest(String actualTarget, String declaredTarget) {
        URI target = URI.create(actualTarget);
        URI declared = URI.create(declaredTarget);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target);
        BiliRequestHeaders.applyLiveHeaders(builder, declared, "test-agent", TEST_COOKIE);
        return builder.build();
    }

    private static HttpRequest liveUrlRequest(String actualTarget, String declaredTarget) throws Exception {
        URI target = URI.create(actualTarget);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target);
        BiliRequestHeaders.applyLiveHeaders(builder, URI.create(declaredTarget).toURL(),
                "test-agent", TEST_COOKIE);
        return builder.build();
    }
}
