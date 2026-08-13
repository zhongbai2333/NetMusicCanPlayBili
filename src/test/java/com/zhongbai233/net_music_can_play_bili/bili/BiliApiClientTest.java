package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    void plansFrozenPlayurlResponseMatrix() throws Exception {
        JsonObject matrix;
        try (var input = BiliApiClientTest.class.getResourceAsStream("/bili/av1-playurl-matrix.json")) {
            if (input == null) {
                throw new AssertionError("Missing frozen AV1 playurl matrix");
            }
            matrix = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        assertFrozenPlan(matrix, "eight_k_av1_with_lower_h264", 127,
                List.of(127, 120), List.of(80), BiliApiClient.CODEC_AV1);
        BiliApiClient.VideoStreamPlan mixed = assertFrozenPlan(matrix, "same_quality_mixed_codecs", 120,
                List.of(120), List.of(120), BiliApiClient.CODEC_AV1);
        assertTrue(mixed.diagnostics().stream().anyMatch(value -> value.contains("hevc-disabled")));
        assertFrozenPlan(matrix, "h264_without_av1", 116,
                List.of(), List.of(116), BiliApiClient.CODEC_H264);

        JsonObject hevcOnly = matrix.getAsJsonObject("hevc_only");
        assertThrows(IllegalStateException.class, () -> planFixture(hevcOnly, 120));

        BiliApiClient.VideoStreamPlan standard = assertFrozenPlan(matrix, "special_quality_filter", 120,
                List.of(120), List.of(116), BiliApiClient.CODEC_AV1);
        assertTrue(standard.diagnostics().stream()
                .filter(value -> value.contains("special-quality-not-requested")).count() >= 2L);
    }

    @Test
    void frozenResponseKeepsPerStreamCdnAndSegmentBaseIdentity() throws Exception {
        JsonObject fixture;
        try (var input = BiliApiClientTest.class.getResourceAsStream("/bili/av1-playurl-matrix.json")) {
            if (input == null) {
                throw new AssertionError("Missing frozen AV1 playurl matrix");
            }
            fixture = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("same_quality_mixed_codecs");
        }

        List<BiliApiClient.VideoStream> streams = BiliApiClient.parseVideoStreams(
                fixture.getAsJsonObject("response").toString());
        BiliApiClient.VideoStream av1 = streams.stream()
                .filter(stream -> stream.codecId() == BiliApiClient.CODEC_AV1).findFirst().orElseThrow();
        BiliApiClient.VideoStream h264 = streams.stream()
                .filter(stream -> stream.codecId() == BiliApiClient.CODEC_H264).findFirst().orElseThrow();

        assertEquals(List.of("https://av1-primary.example.test/video.m4s",
                "https://av1-backup.example.test/video.m4s"), av1.cdnCandidates());
        assertEquals(List.of("https://h264-primary.example.test/video.m4s",
                "https://h264-backup.example.test/video.m4s"), h264.cdnCandidates());
        assertEquals(List.of(0L, 999L, 1_000L, 1_999L),
                List.of(av1.initStart(), av1.initEnd(), av1.indexStart(), av1.indexEnd()));
        assertEquals(List.of(2_000L, 2_999L, 3_000L, 3_999L),
                List.of(h264.initStart(), h264.initEnd(), h264.indexStart(), h264.indexEnd()));
    }

    @Test
    void keepsAv1HardwareOnlyWithoutSoftwareFallback() {
        BiliApiClient.VideoStream lowResolutionAv1 = new BiliApiClient.VideoStream(
                64, BiliApiClient.CODEC_AV1, 1280, 720, "30", "av01.0.05M.08",
                "https://example.test/video-64-13.m4s");

        BiliApiClient.VideoStreamPlan plan = BiliApiClient.buildVideoStreamPlan(
                List.of(lowResolutionAv1), 64);

        assertTrue(plan.softwareAv1Candidates().isEmpty());
        assertEquals(List.of(BiliApiClient.VideoDecodePreference.HARDWARE_REQUIRED),
                plan.candidateOrder().stream()
                        .map(BiliApiClient.PlannedVideoCandidate::decodePreference)
                        .toList());
    }

    @Test
    void appliesEveryCodecPolicyToOneFrozenCandidateSet() {
        List<BiliApiClient.VideoStream> streams = List.of(
                sizedStream(120, BiliApiClient.CODEC_AV1, 3840, 2160, "60", "av01.0.13M.08"),
                sizedStream(80, BiliApiClient.CODEC_AV1, 1920, 1080, "60", "av01.0.08M.08"),
                sizedStream(80, BiliApiClient.CODEC_H264, 1920, 1080, "60", "avc1.640028"));

        BiliApiClient.VideoStreamPlan auto = BiliApiClient.buildVideoStreamPlan(
                streams, 120, BiliApiClient.VideoCodecPolicy.AUTO);
        assertEquals(List.of(
                BiliApiClient.VideoDecodePreference.HARDWARE_REQUIRED,
                BiliApiClient.VideoDecodePreference.HARDWARE_REQUIRED,
                BiliApiClient.VideoDecodePreference.AUTO),
                auto.candidateOrder().stream().map(BiliApiClient.PlannedVideoCandidate::decodePreference).toList());
        assertTrue(auto.softwareAv1Candidates().isEmpty());

        BiliApiClient.VideoStreamPlan preferred = BiliApiClient.buildVideoStreamPlan(
                streams, 120, BiliApiClient.VideoCodecPolicy.PREFER_AV1);
        assertEquals(List.of(
                BiliApiClient.VideoDecodePreference.HARDWARE_REQUIRED,
                BiliApiClient.VideoDecodePreference.HARDWARE_REQUIRED,
                BiliApiClient.VideoDecodePreference.AUTO),
                preferred.candidateOrder().stream()
                        .map(BiliApiClient.PlannedVideoCandidate::decodePreference).toList());
        assertTrue(preferred.softwareAv1Candidates().isEmpty());
        assertEquals(List.of(
                BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED,
                BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED,
                BiliVideoStreamResolver.DecodeMode.AUTO),
                BiliVideoStreamResolver.buildCandidates(preferred, 30).stream()
                        .map(BiliVideoStreamResolver.VideoCandidate::decodeMode).toList());

        BiliApiClient.VideoStreamPlan compatibility = BiliApiClient.buildVideoStreamPlan(
                streams, 120, BiliApiClient.VideoCodecPolicy.COMPATIBILITY);
        assertEquals(List.of(120), compatibility.av1Candidates().stream()
                .map(BiliApiClient.VideoStream::quality).toList());
        assertTrue(compatibility.softwareAv1Candidates().isEmpty());
        assertEquals(List.of(BiliApiClient.CODEC_AV1, BiliApiClient.CODEC_H264),
                compatibility.fallbackOrder().stream().map(BiliApiClient.VideoStream::codecId).toList());

        BiliApiClient.VideoStreamPlan h264 = BiliApiClient.buildVideoStreamPlan(
                streams, 120, BiliApiClient.VideoCodecPolicy.H264);
        assertTrue(h264.av1Candidates().isEmpty());
        assertTrue(h264.softwareAv1Candidates().isEmpty());
        assertEquals(List.of(BiliApiClient.CODEC_H264),
                h264.fallbackOrder().stream().map(BiliApiClient.VideoStream::codecId).toList());
    }

    @Test
    void h264PolicyFailsExplicitlyWhenOnlyAv1Exists() {
        BiliApiClient.VideoStream av1 = sizedStream(
                80, BiliApiClient.CODEC_AV1, 1920, 1080, "30", "av01.0.08M.08");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BiliApiClient.buildVideoStreamPlan(List.of(av1), 80,
                        BiliApiClient.VideoCodecPolicy.H264));

        assertTrue(failure.getMessage().contains("codec policy=h264"));
    }

    @Test
    void subtitlePreferenceSeparatesAiTracksAndPrefersHumanWhenFallbackIsAllowed() {
        BiliApiClient.SubtitleInfo ai = new BiliApiClient.SubtitleInfo(
                "ai-zh", "//example.test/ai.json", true);
        BiliApiClient.SubtitleInfo human = new BiliApiClient.SubtitleInfo(
                "zh-CN", "//example.test/human.json", false);

        assertEquals(List.of(human), BiliApiClient.selectSubtitleCandidates(
                List.of(ai, human), BiliApiClient.SubtitlePreference.HUMAN_ONLY));
        assertEquals(List.of(ai), BiliApiClient.selectSubtitleCandidates(
                List.of(human, ai), BiliApiClient.SubtitlePreference.AI_ONLY));
        assertEquals(List.of(human, ai), BiliApiClient.selectSubtitleCandidates(
                List.of(ai, human), BiliApiClient.SubtitlePreference.HUMAN_OR_AI));
        assertEquals("https://example.test/ai.json", ai.normalizedUrl());
    }

    private static BiliApiClient.VideoStreamPlan assertFrozenPlan(JsonObject matrix, String name,
            int preferredQuality, List<Integer> expectedAv1, List<Integer> expectedH264, int expectedCodec) {
        BiliApiClient.VideoStreamPlan plan = planFixture(matrix.getAsJsonObject(name), preferredQuality);
        assertEquals(expectedAv1, plan.av1Candidates().stream().map(stream -> stream.quality()).toList(), name);
        assertEquals(expectedH264, plan.h264Candidates().stream().map(stream -> stream.quality()).toList(), name);
        assertEquals(expectedCodec, plan.preferred().codecId(), name);
        assertTrue(plan.fallbackOrder().stream().noneMatch(stream -> stream.codecId() == BiliApiClient.CODEC_HEVC),
                name);
        return plan;
    }

    private static BiliApiClient.VideoStreamPlan planFixture(JsonObject fixture, int preferredQuality) {
        List<BiliApiClient.VideoStream> streams = BiliApiClient.parseVideoStreams(
                fixture.getAsJsonObject("response").toString());
        return BiliApiClient.buildVideoStreamPlan(streams, preferredQuality);
    }

    private static BiliApiClient.VideoStream stream(int quality, int codecId, String codecs) {
        int width = quality >= 120 ? 3840 : 1920;
        int height = quality >= 120 ? 2160 : 1080;
        return new BiliApiClient.VideoStream(quality, codecId, width, height, "60", codecs,
                "https://example.test/video-" + quality + "-" + codecId + ".m4s");
    }

    private static BiliApiClient.VideoStream sizedStream(int quality, int codecId, int width, int height,
            String frameRate, String codecs) {
        return new BiliApiClient.VideoStream(quality, codecId, width, height, frameRate, codecs,
                "https://example.test/video-" + quality + "-" + codecId + "-" + width + "x" + height + ".m4s");
    }

}
