package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BiliLiveRoomInputTest {

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
