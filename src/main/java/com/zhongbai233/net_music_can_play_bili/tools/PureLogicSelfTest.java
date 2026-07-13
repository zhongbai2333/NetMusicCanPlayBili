package com.zhongbai233.net_music_can_play_bili.tools;

import com.zhongbai233.net_music_can_play_bili.bili.Mp3FrameSync;
import com.zhongbai233.net_music_can_play_bili.blockentity.TurntableComparatorSignal;
import com.zhongbai233.net_music_can_play_bili.blockentity.TurntableRedstoneMode;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeHeaders;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.UUID;

/**
 * Lightweight pure-logic self tests that do not require launching Minecraft.
 */
public final class PureLogicSelfTest {
    private PureLogicSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        findsMp3SyncAfterNoise();
        rejectsInvalidMp3Frames();
        parsesHttpRangeTotals();
        parsesFullHttpRanges();
        buildsRangeRequests();
        speakerVolumeShrinksAudibleDistance();
        createsNamedDaemonThreads();
        togglesPadDocumentLockWithoutForkingContent();
        preservesMinecartPlaybackSyncMetadata();
        mapsTurntableProgressToComparatorSignal();
        mapsTurntableRedstoneModes();
        System.out.println("PureLogicSelfTest passed");
    }

    private static void findsMp3SyncAfterNoise() {
        byte[] stream = new byte[13 + 417 * 3];
        for (int i = 0; i < 13; i++) {
            stream[i] = (byte) (0x40 + i);
        }
        for (int offset = 13; offset < stream.length; offset += 417) {
            writeMpeg1Layer3FrameHeader(stream, offset);
        }
        int sync = Mp3FrameSync.findFrameSync(stream, stream.length);
        if (sync != 13) {
            throw new AssertionError("expected MP3 sync at byte 13, got " + sync);
        }
    }

    private static void rejectsInvalidMp3Frames() {
        byte[] invalid = { 0x00, 0x11, 0x22, 0x33, 0x44 };
        if (Mp3FrameSync.findFrameSync(invalid, invalid.length) != -1) {
            throw new AssertionError("invalid MP3 bytes should not produce a sync offset");
        }
        if (Mp3FrameSync.parseFrame(invalid, 0, invalid.length) != null) {
            throw new AssertionError("invalid MP3 bytes should not parse as a frame");
        }
        byte[] truncatedHeader = { (byte) 0xFF, (byte) 0xFB, (byte) 0x90 };
        if (Mp3FrameSync.findFrameSync(truncatedHeader, truncatedHeader.length) != -1) {
            throw new AssertionError("truncated MP3 header should not produce a sync offset");
        }
    }

    private static void parsesHttpRangeTotals() {
        long total = HttpRangeHeaders.parseContentRangeTotal("bytes 0-0/123456")
                .orElseThrow(() -> new AssertionError("missing content-range total"));
        if (total != 123456L) {
            throw new AssertionError("unexpected content-range total " + total);
        }
        if (HttpRangeHeaders.parseContentRangeTotal("bytes 0-0/*").isPresent()) {
            throw new AssertionError("wildcard content-range total should be empty");
        }
        if (HttpRangeHeaders.parseContentRangeTotal("nope").isPresent()) {
            throw new AssertionError("invalid content-range should be empty");
        }
        if (!HttpRangeHeaders.isRedirectStatus(301) || !HttpRangeHeaders.isRedirectStatus(308)
                || HttpRangeHeaders.isRedirectStatus(200)) {
            throw new AssertionError("redirect status classification failed");
        }
    }

    private static void parsesFullHttpRanges() {
        HttpRangeHeaders.ContentRange range = HttpRangeHeaders.parseContentRange("bytes 10-20/100");
        if (!range.isKnown() || range.start() != 10L || range.endInclusive() != 20L || range.totalLength() != 100L) {
            throw new AssertionError("unexpected parsed content range " + range);
        }
        HttpRangeHeaders.ContentRange unknownTotal = HttpRangeHeaders.parseContentRange("bytes 10-20/*");
        if (!unknownTotal.isKnown() || unknownTotal.hasKnownTotalLength()) {
            throw new AssertionError("wildcard total content range should preserve range but not total");
        }
        if (HttpRangeHeaders.parseContentRange("bytes 20-10/100").isKnown()) {
            throw new AssertionError("reversed content range should be unknown");
        }
    }

    private static void buildsRangeRequests() throws Exception {
        URL url = URI.create("https://example.invalid/audio.m4a").toURL();
        HttpRequest request = HttpRangeHeaders.rangeRequest(url, 42L, false, Duration.ofSeconds(3)).build();
        String range = request.headers().firstValue("Range")
                .orElseThrow(() -> new AssertionError("missing Range header"));
        if (!"bytes=42-".equals(range)) {
            throw new AssertionError("unexpected Range header " + range);
        }
        HttpRequest probe = HttpRangeHeaders.rangeRequest(url, 999L, true, Duration.ofSeconds(3)).build();
        String probeRange = probe.headers().firstValue("Range")
                .orElseThrow(() -> new AssertionError("missing probe Range header"));
        if (!"bytes=0-0".equals(probeRange)) {
            throw new AssertionError("unexpected probe Range header " + probeRange);
        }
        HttpRequest bounded = HttpRangeHeaders.boundedRangeRequest(url, 10L, 20L, Duration.ofSeconds(3)).build();
        String boundedRange = bounded.headers().firstValue("Range")
                .orElseThrow(() -> new AssertionError("missing bounded Range header"));
        if (!"bytes=10-20".equals(boundedRange)) {
            throw new AssertionError("unexpected bounded Range header " + boundedRange);
        }
    }

    private static void speakerVolumeShrinksAudibleDistance() {
        float fullAtThirtyTwo = AudioUtils.speakerGainForDistance(32.0f, 1.0f);
        if (fullAtThirtyTwo <= 0.0f) {
            throw new AssertionError("full-volume speaker should still be audible at 32 blocks");
        }
        float halfAtThirtyTwo = AudioUtils.speakerGainForDistance(32.0f, 0.5f);
        if (halfAtThirtyTwo <= 0.0f) {
            throw new AssertionError("half-volume speaker should include its 32-block edge");
        }
        float halfPastEdge = AudioUtils.speakerGainForDistance(32.01f, 0.5f);
        if (halfPastEdge != 0.0f) {
            throw new AssertionError("half-volume speaker should be silent past 32 blocks, got " + halfPastEdge);
        }
        float lowAtThirtyTwo = AudioUtils.speakerGainForDistance(32.0f, 0.25f);
        if (lowAtThirtyTwo != 0.0f) {
            throw new AssertionError(
                    "quarter-volume speaker should not be audible at 32 blocks, got " + lowAtThirtyTwo);
        }
        if (AudioUtils.speakerGainForDistance(1.5f, 0.0f) != 0.0f) {
            throw new AssertionError("muted speaker should be silent at any distance");
        }
    }

    private static void createsNamedDaemonThreads() {
        Thread direct = NetMusicThreadFactory.daemonThread("unit-worker", () -> {
        });
        if (!direct.isDaemon() || !"unit-worker".equals(direct.getName())) {
            throw new AssertionError("daemonThread should preserve name and daemon flag");
        }
        Thread pooled = NetMusicThreadFactory.daemon("unit-pool").newThread(() -> {
        });
        if (!pooled.isDaemon() || !pooled.getName().startsWith("unit-pool-")) {
            throw new AssertionError("daemon factory should create named daemon threads");
        }
    }

    private static void togglesPadDocumentLockWithoutForkingContent() {
        PadDocument draft = PadDocument.DEFAULT.withLocked(false);
        PadDocument locked = draft.withLocked(true);
        if (!locked.locked()) {
            throw new AssertionError("published Pad copy should be locked");
        }
        if (!draft.mediaEntries().equals(locked.mediaEntries())
                || !draft.triggerPoints().equals(locked.triggerPoints())) {
            throw new AssertionError("lock transition should preserve Pad document content lists");
        }
        PadDocument unlocked = locked.withLocked(false);
        if (unlocked.locked()) {
            throw new AssertionError("draft mirror should remain editable");
        }
        if (unlocked.sequence() <= locked.sequence()) {
            throw new AssertionError("lock transition should bump document version");
        }
        PadDocument mirroredDraft = locked.copyWithLocked(false);
        if (mirroredDraft.locked() || mirroredDraft.sequence() != locked.sequence()
                || mirroredDraft.updatedAtMillis() != locked.updatedAtMillis()) {
            throw new AssertionError("copyWithLocked should preserve content version while changing item lock state");
        }
    }

    private static void preservesMinecartPlaybackSyncMetadata() {
        UUID minecartUuid = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        String synced = PlaybackSync.withSync("https://example.invalid/audio.m4a", "session-1", 1_250L, 9_000L);
        synced = PlaybackSync.withMinecartAnchor(synced, 42, minecartUuid);
        PlaybackSync.Metadata metadata = PlaybackSync.parse(synced);
        PlaybackSync.MinecartAnchor anchor = PlaybackSync.parseMinecartAnchor(synced);
        if (!"session-1".equals(metadata.sessionId()) || metadata.elapsedMillis() != 1_250L
                || metadata.totalMillis() != 9_000L) {
            throw new AssertionError("minecart anchor should preserve playback timing metadata");
        }
        if (anchor == null || anchor.entityId() != 42 || !minecartUuid.equals(anchor.entityUuid())) {
            throw new AssertionError("minecart anchor metadata did not round-trip");
        }
        String transferred = PlaybackSync.transferSync(synced, "https://cdn.example.invalid/audio.m4a");
        PlaybackSync.MinecartAnchor transferredAnchor = PlaybackSync.parseMinecartAnchor(transferred);
        if (transferredAnchor == null || transferredAnchor.entityId() != 42
                || !minecartUuid.equals(transferredAnchor.entityUuid())) {
            throw new AssertionError("minecart anchor should survive URL transfer");
        }
        if (!"https://cdn.example.invalid/audio.m4a".equals(PlaybackSync.strip(transferred))) {
            throw new AssertionError("stripping sync metadata should restore the clean target URL");
        }
    }

    private static void mapsTurntableProgressToComparatorSignal() {
        assertComparatorSignal(0, false, 0L, 60_000L);
        assertComparatorSignal(0, true, 0L, 0L);
        assertComparatorSignal(1, true, 0L, 60_000L);
        assertComparatorSignal(8, true, 30_000L, 60_000L);
        assertComparatorSignal(15, true, 60_000L, 60_000L);
        assertComparatorSignal(15, true, 90_000L, 60_000L);
        assertComparatorSignal(1, true, -1_000L, 60_000L);
    }

    private static void assertComparatorSignal(int expected, boolean hasDisc, long elapsedMillis,
            long durationMillis) {
        int actual = TurntableComparatorSignal.fromProgress(hasDisc, elapsedMillis, durationMillis);
        if (actual != expected) {
            throw new AssertionError("expected comparator signal " + expected + ", got " + actual);
        }
    }

    private static void mapsTurntableRedstoneModes() {
        if (!TurntableRedstoneMode.HIGH_SIGNAL.shouldPlay(true)
                || TurntableRedstoneMode.HIGH_SIGNAL.shouldPlay(false)) {
            throw new AssertionError("high-signal mode should only play while powered");
        }
        if (TurntableRedstoneMode.LOW_SIGNAL.shouldPlay(true)
                || !TurntableRedstoneMode.LOW_SIGNAL.shouldPlay(false)) {
            throw new AssertionError("low-signal mode should only play while unpowered");
        }
        if (!TurntableRedstoneMode.IGNORE.shouldPlay(true)
                || !TurntableRedstoneMode.IGNORE.shouldPlay(false)) {
            throw new AssertionError("ignore mode should not block playback at either signal level");
        }
        if (TurntableRedstoneMode.IGNORE.next() != TurntableRedstoneMode.HIGH_SIGNAL
                || TurntableRedstoneMode.HIGH_SIGNAL.next() != TurntableRedstoneMode.LOW_SIGNAL
                || TurntableRedstoneMode.LOW_SIGNAL.next() != TurntableRedstoneMode.IGNORE) {
            throw new AssertionError("unexpected turntable redstone mode cycle order");
        }
        if (TurntableRedstoneMode.byName("high_signal") != TurntableRedstoneMode.HIGH_SIGNAL
                || TurntableRedstoneMode.byName("low_signal") != TurntableRedstoneMode.LOW_SIGNAL
                || TurntableRedstoneMode.byName("unknown") != TurntableRedstoneMode.IGNORE) {
            throw new AssertionError("turntable redstone mode persistence mapping failed");
        }
    }

    /**
     * MPEG 1 Layer III, 128 kbps, 44100 Hz, no padding => frame length 417 bytes.
     */
    private static void writeMpeg1Layer3FrameHeader(byte[] target, int offset) {
        target[offset] = (byte) 0xFF;
        target[offset + 1] = (byte) 0xFB;
        target[offset + 2] = (byte) 0x90;
        target[offset + 3] = 0x64;
    }
}
