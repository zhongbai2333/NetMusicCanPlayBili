package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliLiveStreamResolverTest {

    @Test
    void prefersFlvThenTsThenFmp4AndKeepsCdnOrder() {
        List<BiliLiveStreamResolver.LiveStream> streams = BiliLiveStreamResolver.parseStreams(playInfo());

        assertEquals(List.of(
                "https://flv-1.example/live-bvc/a.flv?k=3",
                "https://flv-2.example/live-bvc/a.flv?k=4",
                "https://hls-b.example/live-bvc/a.m3u8?k=2",
                "https://hls-a.example/live-bvc/a.m4s?k=1"),
                streams.stream().map(stream -> stream.url()).toList());
        assertTrue(streams.get(0).isFlv());
        assertFalse(streams.get(0).isHls());
        assertTrue(streams.get(2).isHls());
    }

    @Test
    void splitsFlvAndHlsUrlsOnTheRoom() {
        BiliLiveStreamResolver.LiveRoom room = new BiliLiveStreamResolver.LiveRoom("123",
                BiliLiveStreamResolver.LIVE_STATUS_LIVE, BiliLiveStreamResolver.parseStreams(playInfo()));

        assertTrue(room.isLive());
        assertEquals(2, room.flvUrls().size());
        assertEquals(2, room.hlsUrls().size());
        assertTrue(room.flvUrls().get(0).endsWith(".flv?k=3"));
    }

    @Test
    void ignoresUnknownProtocolsAndDeduplicatesUrls() {
        JsonObject data = parse("""
                {"playurl_info": {"playurl": {"stream": [
                  {"protocol_name": "http_stream", "format": [
                    {"format_name": "flv", "codec": [
                      {"codec_name": "avc", "base_url": "/a.flv", "url_info": [
                        {"host": "https://one.example", "extra": ""},
                        {"host": "https://one.example", "extra": ""}]}]}]},
                  {"protocol_name": "rtmp", "format": [
                    {"format_name": "flv", "codec": [
                      {"codec_name": "avc", "base_url": "/b.flv", "url_info": [
                        {"host": "rtmp://two.example", "extra": ""}]}]}]}
                ]}}}
                """);

        List<BiliLiveStreamResolver.LiveStream> streams = BiliLiveStreamResolver.parseStreams(data);

        assertEquals(List.of("https://one.example/a.flv"),
                streams.stream().map(stream -> stream.url()).toList());
    }

    @Test
    void returnsEmptyListWhenPlayInfoIsMissingOrMalformed() {
        assertTrue(BiliLiveStreamResolver.parseStreams(parse("{}")).isEmpty());
        assertTrue(BiliLiveStreamResolver.parseStreams(parse("{\"playurl_info\": null}")).isEmpty());
        assertTrue(BiliLiveStreamResolver.parseStreams(
                parse("{\"playurl_info\": {\"playurl\": {\"stream\": {}}}}")).isEmpty());
    }

    @Test
    void filtersHevcCodecEntries() {
        JsonObject data = parse("""
                {"playurl_info": {"playurl": {"stream": [
                  {"protocol_name": "http_stream", "format": [
                    {"format_name": "flv", "codec": [
                      {"codec_name": "hevc", "base_url": "/hevc.flv", "url_info": [
                        {"host": "https://one.example", "extra": ""}]},
                      {"codec_name": "avc", "base_url": "/avc.flv", "url_info": [
                        {"host": "https://one.example", "extra": ""}]}]}]}
                ]}}}
                """);

        List<BiliLiveStreamResolver.LiveStream> streams = BiliLiveStreamResolver.parseStreams(data);

        assertEquals(List.of("https://one.example/avc.flv"),
                streams.stream().map(stream -> stream.url()).toList());
    }

    @Test
    void offlineRoomsAreNotPlayable() {
        BiliLiveStreamResolver.LiveRoom offline = new BiliLiveStreamResolver.LiveRoom("1",
                BiliLiveStreamResolver.LIVE_STATUS_OFFLINE, List.of());
        BiliLiveStreamResolver.LiveRoom carousel = new BiliLiveStreamResolver.LiveRoom("1",
                BiliLiveStreamResolver.LIVE_STATUS_CAROUSEL, List.of());

        assertFalse(offline.isLive());
        assertTrue(carousel.isLive());
        assertEquals("未开播", BiliLiveStreamResolver.describeLiveStatus(
                BiliLiveStreamResolver.LIVE_STATUS_OFFLINE));
    }

    @Test
    void acceptsOnlyNumericRoomIds() {
        assertTrue(BiliLiveStreamResolver.isValidRoomId("123456"));
        assertFalse(BiliLiveStreamResolver.isValidRoomId(""));
        assertFalse(BiliLiveStreamResolver.isValidRoomId("12a"));
        assertFalse(BiliLiveStreamResolver.isValidRoomId("../etc"));
        assertFalse(BiliLiveStreamResolver.isValidRoomId("12345678901234567"));
    }

    @Test
    void parsesAndBoundsLiveRoomMetadata() throws Exception {
        JsonObject root = parse("""
                {"code":0,"data":{"room_id":7734200,"title":"  赛事直播  ",
                 "parent_area_name":"赛事","area_name":"游戏赛事",
                 "live_time":"2026-08-12 16:43:22"}}
                """);

        BiliLiveStreamResolver.LiveMetadata metadata =
                BiliLiveStreamResolver.parseRoomMetadata(root, "6");

        assertEquals("7734200", metadata.roomId());
        assertEquals("赛事直播", metadata.title());
        assertEquals("赛事", metadata.parentAreaName());
        assertEquals("游戏赛事", metadata.areaName());
        assertEquals("2026-08-12 16:43:22", metadata.liveTime());
    }

    private static JsonObject playInfo() {
        return parse("""
                {
                  "live_status": 1,
                  "title": "测试直播间",
                  "playurl_info": {"playurl": {"stream": [
                    {"protocol_name": "http_hls", "format": [
                      {"format_name": "fmp4", "codec": [
                        {"codec_name": "avc", "base_url": "/live-bvc/a.m4s", "url_info": [
                          {"host": "https://hls-a.example", "extra": "?k=1"}]}]},
                      {"format_name": "ts", "codec": [
                        {"codec_name": "avc", "base_url": "/live-bvc/a.m3u8", "url_info": [
                          {"host": "https://hls-b.example", "extra": "?k=2"}]}]}]},
                    {"protocol_name": "http_stream", "format": [
                      {"format_name": "flv", "codec": [
                        {"codec_name": "avc", "base_url": "/live-bvc/a.flv", "url_info": [
                          {"host": "https://flv-1.example", "extra": "?k=3"},
                          {"host": "https://flv-2.example", "extra": "?k=4"}]}]}]}
                  ]}}
                }
                """);
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
