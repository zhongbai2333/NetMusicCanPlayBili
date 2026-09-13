package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliShortLinkSecurityTest {
    @Test
    void acceptsOnlyOfficialHttpsShortLinkOrigins() {
        assertTrue(BiliVideoReferenceParser.looksLikeShortLink("https://b23.tv/AbCd123"));
        assertTrue(BiliVideoReferenceParser.looksLikeShortLink("HTTPS://B23.TV/AbCd123?p=2"));
        assertTrue(BiliVideoReferenceParser.looksLikeShortLink("https://b23.tv:443/AbCd123"));
        assertTrue(BiliVideoReferenceParser.looksLikeShortLink("https://bili2233.cn/AbCd123"));
        assertTrue(BiliVideoReferenceParser.looksLikeShortLink("b23.tv/AbCd123"));
        assertTrue(BiliVideoReferenceParser.looksLikeShortLink("BILI2233.CN/AbCd123"));

        assertFalse(BiliVideoReferenceParser.looksLikeShortLink("http://b23.tv/AbCd123"));
        assertFalse(BiliVideoReferenceParser.looksLikeShortLink("https://b23.tv/"));
        assertFalse(BiliVideoReferenceParser.looksLikeShortLink("https://b23.tv:444/AbCd123"));
        assertFalse(BiliVideoReferenceParser.looksLikeShortLink("https://sub.b23.tv/AbCd123"));
        assertFalse(BiliVideoReferenceParser.looksLikeShortLink("https://b23.tv.attacker.example/AbCd123"));
        assertFalse(BiliVideoReferenceParser.looksLikeShortLink("https://b23.tv@attacker.example/AbCd123"));
        assertFalse(BiliVideoReferenceParser.looksLikeShortLink(
                "https://attacker.example/collect?next=https://b23.tv/AbCd123"));
        assertFalse(BiliVideoReferenceParser.looksLikeShortLink(null));
        assertFalse(BiliVideoReferenceParser.looksLikeShortLink(""));
    }

    @Test
    void parsedShortLinkIsTheValidatedUriRatherThanEmbeddedText() {
        assertEquals(URI.create("https://b23.tv/AbCd123?p=2"),
                BiliVideoReferenceParser.parseShortLinkUri(" https://b23.tv/AbCd123?p=2 "));
        assertEquals(URI.create("https://b23.tv/AbCd123"),
                BiliVideoReferenceParser.parseShortLinkUri("b23.tv/AbCd123"));
        assertNull(BiliVideoReferenceParser.parseShortLinkUri(
                "https://attacker.example/?url=https://bili2233.cn/AbCd123"));
    }

    @Test
    void finalRedirectMustStayOnHttpsBilibiliContentDomains() {
        assertTrue(BiliApiClient.isTrustedExpandedShortLink(
                URI.create("https://www.bilibili.com/video/BV1qM4y1w716")));
        assertTrue(BiliApiClient.isTrustedExpandedShortLink(
                URI.create("https://m.bilibili.com/video/BV1qM4y1w716")));

        assertFalse(BiliApiClient.isTrustedExpandedShortLink(
                URI.create("http://www.bilibili.com/video/BV1qM4y1w716")));
        assertFalse(BiliApiClient.isTrustedExpandedShortLink(
                URI.create("https://bilibili.com.attacker.example/video/BV1qM4y1w716")));
        assertFalse(BiliApiClient.isTrustedExpandedShortLink(
                URI.create("https://b23.tv/AbCd123")));
        assertFalse(BiliApiClient.isTrustedExpandedShortLink(null));
    }

    @Test
    void embeddedShortLinkMarkerNeverTriggersNetworkExpansion() {
        assertNull(BiliApiClient.extractVideoSelectionLenientWithShortLink(
                "https://attacker.example/collect?next=https://b23.tv/not-a-video"));
    }
}
