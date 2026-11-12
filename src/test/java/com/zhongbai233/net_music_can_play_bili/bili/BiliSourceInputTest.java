package com.zhongbai233.net_music_can_play_bili.bili;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Official source input parsing and bounded, destination-validated short-link expansion. */
class BiliSourceInputTest {
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

    @Test void legitimateChainAndRelativeRedirectAreResolvedWithoutNetwork() throws Exception {
        List<URI>requested=new ArrayList<>();URI result=BiliShortLinkExpansion.expand(URI.create("https://b23.tv/x"),Duration.ofSeconds(5),()->0L,(u,t)->{requested.add(u);return requested.size()==1?new BiliShortLinkExpansion.Response(302,"https://www.bilibili.com/video/BV1234567890?p=2"):new BiliShortLinkExpansion.Response(200,null);});assertEquals(2,requested.size());assertEquals("www.bilibili.com",result.getHost());assertEquals("p=2",result.getQuery());
    }
    @Test void hostileRedirectIsRejectedBeforeAnyRequestToDestination() throws Exception {
        for(String bad:List.of("http://www.bilibili.com/a","https://localhost/a","https://bilibili.com.evil.test/a","https://user@www.bilibili.com/a","https://www.bilibili.com:8443/a")) {
            List<URI>requested=new ArrayList<>();assertNull(BiliShortLinkExpansion.expand(URI.create("https://b23.tv/x"),Duration.ofSeconds(5),()->0L,(u,t)->{requested.add(u);return new BiliShortLinkExpansion.Response(302,bad);}));assertEquals(1,requested.size());
        }
    }
    @Test void totalDeadlineAndRedirectCountAreFinite() throws Exception {
        AtomicLong clock=new AtomicLong();AtomicInteger n=new AtomicInteger();assertNull(BiliShortLinkExpansion.expand(URI.create("https://b23.tv/x"),Duration.ofSeconds(5),clock::get,(u,t)->{assertTrue(t.toNanos()<=5_000_000_000L);n.incrementAndGet();clock.addAndGet(3_000_000_000L);return new BiliShortLinkExpansion.Response(302,"/x");}));assertEquals(2,n.get());
        n.set(0);assertNull(BiliShortLinkExpansion.expand(URI.create("https://b23.tv/x"),Duration.ofSeconds(5),()->0L,(u,t)->{n.incrementAndGet();return new BiliShortLinkExpansion.Response(302,"/x");}));assertEquals(6,n.get());
    }

    @Test
    void acceptsLivePrefixedRoomIds() {
        assertEquals("123456", BiliLiveRoomInput.parseRoomId("live:123456"));
        assertEquals("123456", BiliLiveRoomInput.parseRoomId("  LIVE:123456  "));
        assertEquals("6", BiliLiveRoomInput.parseRoomId("live: 6 "));
    }

    @Test
    void acceptsPastedLiveRoomLinks() {
        assertEquals("123456", BiliLiveRoomInput.parseRoomId("https://live.bilibili.com/123456"));
        assertEquals("123456", BiliLiveRoomInput.parseRoomId("https://live.bilibili.com/123456/"));
        assertEquals("123456", BiliLiveRoomInput.parseRoomId(
                "https://live.bilibili.com/123456?spm_id_from=333.1007&broadcast_type=0"));
        assertEquals("123456", BiliLiveRoomInput.parseRoomId("http://live.bilibili.com/h5/123456"));
    }

    @Test
    void rejectsInputThatIsNotALiveRoom() {
        assertEquals("", BiliLiveRoomInput.parseRoomId(null));
        assertEquals("", BiliLiveRoomInput.parseRoomId(""));
        assertEquals("", BiliLiveRoomInput.parseRoomId("live:abc"));
        assertEquals("", BiliLiveRoomInput.parseRoomId("live:../../etc"));
        assertEquals("", BiliLiveRoomInput.parseRoomId("https://www.bilibili.com/video/BV1qM4y1w716"));
        assertEquals("", BiliLiveRoomInput.parseRoomId("https://live.bilibili.example/123456"));
        assertEquals("", BiliLiveRoomInput.parseRoomId("https://example.com/stream.m3u8"));
    }

    @Test
    void explicitParsingRejectsBareDigitsButAcceptsPrefixAndUrls() {
        assertEquals("123456", BiliLiveRoomInput.parseExplicitRoomId("live:123456"));
        assertEquals("123456", BiliLiveRoomInput.parseExplicitRoomId(" LIVE:123456 "));
        assertEquals("123456", BiliLiveRoomInput.parseExplicitRoomId("https://live.bilibili.com/123456?x=1"));
        // 白名单入口不能把裸数字当成直播间
        assertEquals("", BiliLiveRoomInput.parseExplicitRoomId("123456"));
        assertEquals("", BiliLiveRoomInput.parseExplicitRoomId("live:abc"));
        assertEquals("", BiliLiveRoomInput.parseExplicitRoomId("https://www.bilibili.com/video/BV1qM4y1w716"));
        assertEquals("", BiliLiveRoomInput.parseExplicitRoomId(null));
    }

    @Test
    void roundTripsPlaceholderUrls() {
        String placeholder = BiliLiveRoomInput.placeholderUrl("123456");

        assertEquals("http://live/123456.m3u8", placeholder);
        assertEquals("123456", BiliLiveRoomInput.roomIdFromPlaceholder(placeholder));
    }

    @Test
    void rejectsForeignOrMalformedPlaceholders() {
        assertEquals("", BiliLiveRoomInput.roomIdFromPlaceholder(null));
        assertEquals("", BiliLiveRoomInput.roomIdFromPlaceholder("http://live/abc.m3u8"));
        assertEquals("", BiliLiveRoomInput.roomIdFromPlaceholder("http://live/.m3u8"));
        assertEquals("", BiliLiveRoomInput.roomIdFromPlaceholder("http://live/123456.flv"));
        assertEquals("", BiliLiveRoomInput.roomIdFromPlaceholder("https://live/123456.m3u8"));
        assertEquals("", BiliLiveRoomInput.roomIdFromPlaceholder("https://cdn.example/live/123456.m3u8"));
    }
}
