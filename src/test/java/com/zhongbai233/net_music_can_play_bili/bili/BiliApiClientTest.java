package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliApiClientTest {
    private static final String BVID = "BV1qM4y1w716";

    @Test
    void acceptsCanonicalVideoIdsCaseInsensitively() {
        assertTrue(BiliApiClient.isBiliVideoId(BVID));
        assertTrue(BiliApiClient.isBiliVideoId("bv1qM4y1w716"));
        assertTrue(BiliApiClient.isBiliVideoId("AV170001"));
        assertFalse(BiliApiClient.isBiliVideoId("https://www.bilibili.com/video/" + BVID));
        assertFalse(BiliApiClient.isBiliVideoId("BV-too-short"));
    }

    @Test
    void extractsIdsAndPagesFromCommonLinks() {
        BiliApiClient.VideoSelection selection = BiliApiClient.extractVideoSelectionLenient(
                "https://www.bilibili.com/video/" + BVID + "?spm_id_from=333.1007&p=3");

        assertEquals(BVID, selection.videoId().asInputText());
        assertEquals(3, selection.page());
        assertEquals("av170001", BiliApiClient.extractVideoIdLenient(
                "https://www.bilibili.com/video/av170001/").asInputText());
    }

    @Test
    void roundTripsStoredSelections() {
        BiliApiClient.VideoId id = BiliApiClient.VideoId.bvid(BVID);
        String stored = BiliApiClient.formatStoredVideoSelection(id, 7);
        BiliApiClient.VideoSelection parsed = BiliApiClient.parseStoredVideoSelection(stored);

        assertEquals(BVID + "|p=7", stored);
        assertEquals(id, parsed.videoId());
        assertEquals(7, parsed.page());
    }

    @Test
    void clampsZeroPageAndSafelyHandlesOverflow() {
        assertEquals(1, BiliApiClient.parseStoredVideoSelection(BVID + "|p=0").page());
        assertEquals(1, BiliApiClient.parseStoredVideoSelection(
                BVID + "|p=999999999999999999999999999999").page());
        assertEquals(1, BiliApiClient.extractVideoSelectionLenient(
                "https://www.bilibili.com/video/" + BVID + "?p=999999999999999999999999").page());
    }

    @Test
    void rejectsMalformedStoredSelections() {
        assertNull(BiliApiClient.parseStoredVideoSelection(null));
        assertNull(BiliApiClient.parseStoredVideoSelection(""));
        assertNull(BiliApiClient.parseStoredVideoSelection(BVID + "|p=-1"));
        assertNull(BiliApiClient.parseStoredVideoSelection(BVID + "|p=abc"));
        assertNull(BiliApiClient.extractVideoSelectionLenient("not a bilibili video"));
    }
}