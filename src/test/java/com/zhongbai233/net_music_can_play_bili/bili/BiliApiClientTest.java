package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void buildsMinimalVideoFnvalForQualityCeiling() {
        assertEquals(2064, BiliApiClient.videoFnval(116));
        assertEquals(2192, BiliApiClient.videoFnval(120));
        assertEquals(3216, BiliApiClient.videoFnval(127));
    }

    @Test
    void plansAv1BeforeH264AndKeepsDescendingFallbacks() {
        BiliApiClient.VideoStreamPlan plan = BiliApiClient.buildVideoStreamPlan(List.of(
                stream(80, BiliApiClient.CODEC_H264, "avc1.640028"),
                stream(120, BiliApiClient.CODEC_AV1, "av01.0.13M.08"),
                stream(80, BiliApiClient.CODEC_AV1, "av01.0.08M.08")), 120);

        assertEquals(List.of(120, 80), plan.av1Candidates().stream().map(stream -> stream.quality()).toList());
        assertEquals(List.of(80), plan.h264Candidates().stream().map(stream -> stream.quality()).toList());
        assertEquals(BiliApiClient.CODEC_AV1, plan.preferred().codecId());
        assertEquals(List.of(BiliApiClient.CODEC_AV1, BiliApiClient.CODEC_AV1, BiliApiClient.CODEC_H264),
            plan.fallbackOrder().stream().limit(3).map(stream -> stream.codecId()).toList());
    }

    @Test
    void rejectsHevcUnknownAndCodecStringMismatch() {
        BiliApiClient.VideoStreamPlan plan = BiliApiClient.buildVideoStreamPlan(List.of(
                stream(120, BiliApiClient.CODEC_HEVC, "hev1.1.6.L120"),
                stream(120, BiliApiClient.CODEC_AV1, "hev1.1.6.L120"),
                stream(120, 0, "av01.0.13M.08"),
                stream(80, BiliApiClient.CODEC_H264, "avc1.640028")), 120);

        assertTrue(plan.av1Candidates().isEmpty());
        assertEquals(1, plan.h264Candidates().size());
        assertTrue(plan.diagnostics().stream().anyMatch(value -> value.contains("hevc-disabled")));
        assertTrue(plan.diagnostics().stream().anyMatch(value -> value.contains("codec-string-mismatch")));
        assertTrue(plan.diagnostics().stream().anyMatch(value -> value.contains("unsupported-codec")));
    }

    @Test
    void neverSelectsOnlyHevcOrStreamsAboveCeiling() {
        assertThrows(IllegalStateException.class, () -> BiliApiClient.buildVideoStreamPlan(List.of(
                stream(127, BiliApiClient.CODEC_HEVC, "hev1.1.6.L180"),
                stream(120, BiliApiClient.CODEC_AV1, "av01.0.13M.08")), 80));
    }

    @Test
    void keepsOnlyHighestH264Fallback() {
        BiliApiClient.VideoStreamPlan plan = BiliApiClient.buildVideoStreamPlan(List.of(
                stream(80, BiliApiClient.CODEC_H264, "avc1.640028"),
                stream(64, BiliApiClient.CODEC_H264, "avc1.64001f"),
                stream(32, BiliApiClient.CODEC_H264, "avc1.4d401e")), 80);

        assertEquals(List.of(80),
                plan.h264Candidates().stream().map(stream -> stream.quality()).toList());
    }

    @Test
    void limitsAv1HardwareProbesToRepresentativeQualitySteps() {
        BiliApiClient.VideoStreamPlan plan = BiliApiClient.buildVideoStreamPlan(List.of(
                stream(127, BiliApiClient.CODEC_AV1, "av01.0.17M.08"),
                stream(120, BiliApiClient.CODEC_AV1, "av01.0.13M.08"),
                stream(116, BiliApiClient.CODEC_AV1, "av01.0.09M.08"),
                stream(80, BiliApiClient.CODEC_AV1, "av01.0.08M.08")), 127);

        assertEquals(List.of(127, 120, 116),
                plan.av1Candidates().stream().map(stream -> stream.quality()).toList());
    }

        @Test
        void doesNotOfferAv1SoftwareFallbackWithoutDav1dOrLibaom() {
        BiliApiClient.VideoStream lowResolutionAv1 = new BiliApiClient.VideoStream(
            64, BiliApiClient.CODEC_AV1, 1280, 720, "30", "av01.0.05M.08",
            "https://example.test/video-64-13.m4s");

        BiliApiClient.VideoStreamPlan plan = BiliApiClient.buildVideoStreamPlan(
            List.of(lowResolutionAv1), 64);

        assertTrue(plan.softwareAv1Candidates().isEmpty());
        assertEquals(List.of(BiliApiClient.CODEC_AV1),
            plan.fallbackOrder().stream().map(stream -> stream.codecId()).toList());
        }

    private static BiliApiClient.VideoStream stream(int quality, int codecId, String codecs) {
        int width = quality >= 120 ? 3840 : 1920;
        int height = quality >= 120 ? 2160 : 1080;
        return new BiliApiClient.VideoStream(quality, codecId, width, height, "60", codecs,
                "https://example.test/video-" + quality + "-" + codecId + ".m4s");
    }
}