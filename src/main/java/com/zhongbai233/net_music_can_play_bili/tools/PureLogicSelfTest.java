package com.zhongbai233.net_music_can_play_bili.tools;

import com.zhongbai233.net_music_can_play_bili.bili.Mp3FrameSync;
import com.zhongbai233.net_music_can_play_bili.blockentity.TurntableComparatorSignal;
import com.zhongbai233.net_music_can_play_bili.blockentity.TurntableExtractionMode;
import com.zhongbai233.net_music_can_play_bili.blockentity.TurntableRedstoneMode;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ProjectorScreenBounds;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeHeaders;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4StreamParser;
import com.zhongbai233.net_music_can_play_bili.media.stream.MediaNetworkFailureClassifier;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseExecutor;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
        rejectsOversizedMediaPayloadBeforeReading();
        classifiesMediaNetworkFailures();
        spatialVolumeShrinksAudibleDistance();
        createsNamedDaemonThreads();
        closesMediaResourcesOffCallerThread();
        togglesPadDocumentLockWithoutForkingContent();
        preservesMinecartPlaybackSyncMetadata();
        mapsTurntableProgressToComparatorSignal();
        mapsTurntableRedstoneModes();
        mapsTurntableExtractionModes();
        coversMovedAndOversizedProjectorScreens();
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

    private static void rejectsOversizedMediaPayloadBeforeReading() throws Exception {
        InputStream mustNotRead = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("oversized payload must be rejected before reading or allocating");
            }
        };
        try {
            Fmp4StreamParser.readFully(mustNotRead, Long.MAX_VALUE);
            throw new AssertionError("oversized media payload should be rejected");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("too large")) {
                throw new AssertionError("unexpected oversized payload error", expected);
            }
        }
    }

    private static void classifiesMediaNetworkFailures() {
        if (!MediaNetworkFailureClassifier.isNetworkFailure(new SocketTimeoutException("read timed out"))) {
            throw new AssertionError("socket timeout should be classified as a network failure");
        }
        if (!MediaNetworkFailureClassifier.isNetworkFailure(
                new IllegalStateException("wrapper", new java.io.IOException("CDN response ended early")))) {
            throw new AssertionError("nested CDN short read should be classified as a network failure");
        }
        if (MediaNetworkFailureClassifier.isNetworkFailure(
                new java.io.IOException("unable to parse fMP4 moov decoder config"))) {
            throw new AssertionError("container parsing failure must not be presented as a network failure");
        }
        if (MediaNetworkFailureClassifier.isNetworkFailure(
                new java.io.IOException("native video decoder unavailable"))) {
            throw new AssertionError("decoder initialization failure must not be presented as a network failure");
        }
    }

    private static void spatialVolumeShrinksAudibleDistance() {
        float fullAtEdge = AudioUtils.spatialGainForDistance(64.0f, 1.0f);
        float expectedAtEdge = AudioUtils.gainForDistance(64.0f);
        if (Math.abs(fullAtEdge - expectedAtEdge) > 1.0e-6f) {
            throw new AssertionError("spatial audio should retain normal gain through its designed audible edge");
        }
        float fullFadeStart = AudioUtils.spatialGainForDistance(65.0f, 1.0f);
        float fullFadeMiddle = AudioUtils.spatialGainForDistance(70.0f, 1.0f);
        float fullFadeEnd = AudioUtils.spatialGainForDistance(76.0f, 1.0f);
        if (!(fullAtEdge > fullFadeStart && fullFadeStart > fullFadeMiddle
                && fullFadeMiddle > fullFadeEnd && fullFadeEnd > 0.0f)) {
            throw new AssertionError("spatial audio should fade monotonically after its designed audible edge");
        }
        if (AudioUtils.spatialGainForDistance(76.8f, 1.0f) != 0.0f
                || AudioUtils.spatialGainForDistance(77.0f, 1.0f) != 0.0f) {
            throw new AssertionError("full-volume client fade should end after its 12.8-block outer tail");
        }
        float halfAtEdge = AudioUtils.spatialGainForDistance(32.0f, 0.5f);
        if (Math.abs(halfAtEdge - AudioUtils.gainForDistance(32.0f) * 0.5f) > 1.0e-6f) {
            throw new AssertionError("half-volume spatial audio should retain normal gain through 32 blocks");
        }
        float halfFade = AudioUtils.spatialGainForDistance(35.0f, 0.5f);
        if (halfFade <= 0.0f || halfFade >= AudioUtils.gainForDistance(35.0f) * 0.5f
                || AudioUtils.spatialGainForDistance(38.4f, 0.5f) != 0.0f) {
            throw new AssertionError("half-volume client fade should occupy 32 through 38.4 blocks");
        }
        float quarterFade = AudioUtils.spatialGainForDistance(17.0f, 0.25f);
        if (quarterFade <= 0.0f || AudioUtils.spatialGainForDistance(19.2f, 0.25f) != 0.0f) {
            throw new AssertionError("quarter-volume client fade should occupy 16 through 19.2 blocks");
        }
        if (AudioUtils.spatialGainForDistance(1.5f, 0.0f) != 0.0f) {
            throw new AssertionError("muted spatial audio should be silent at any distance");
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

    private static void closesMediaResourcesOffCallerThread() throws Exception {
        String callerThread = Thread.currentThread().getName();
        AtomicReference<String> closeThread = new AtomicReference<>();
        MediaCloseExecutor.closeAsync(() -> closeThread.set(Thread.currentThread().getName()), "self-test resource")
                .get(5L, TimeUnit.SECONDS);
        String actual = closeThread.get();
        if (actual == null || actual.equals(callerThread)) {
            throw new AssertionError("media close should complete on a background thread, got " + actual);
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
        if (!TurntableRedstoneMode.PULSE_TOGGLE.shouldPlay(true)
                || !TurntableRedstoneMode.PULSE_TOGGLE.shouldPlay(false)) {
            throw new AssertionError("pulse mode playback is latched and must not depend on signal duration");
        }
        if (TurntableRedstoneMode.IGNORE.next() != TurntableRedstoneMode.HIGH_SIGNAL
                || TurntableRedstoneMode.HIGH_SIGNAL.next() != TurntableRedstoneMode.LOW_SIGNAL
                || TurntableRedstoneMode.LOW_SIGNAL.next() != TurntableRedstoneMode.PULSE_TOGGLE
                || TurntableRedstoneMode.PULSE_TOGGLE.next() != TurntableRedstoneMode.IGNORE) {
            throw new AssertionError("unexpected turntable redstone mode cycle order");
        }
        if (TurntableRedstoneMode.byName("high_signal") != TurntableRedstoneMode.HIGH_SIGNAL
                || TurntableRedstoneMode.byName("low_signal") != TurntableRedstoneMode.LOW_SIGNAL
                || TurntableRedstoneMode.byName("pulse_toggle") != TurntableRedstoneMode.PULSE_TOGGLE
                || TurntableRedstoneMode.byName("unknown") != TurntableRedstoneMode.IGNORE) {
            throw new AssertionError("turntable redstone mode persistence mapping failed");
        }
    }

    private static void mapsTurntableExtractionModes() {
        if (TurntableExtractionMode.AFTER_PLAYBACK.next() != TurntableExtractionMode.ALWAYS
                || TurntableExtractionMode.ALWAYS.next() != TurntableExtractionMode.AFTER_PLAYBACK) {
            throw new AssertionError("unexpected turntable extraction mode cycle order");
        }
        if (TurntableExtractionMode.byName("after_playback") != TurntableExtractionMode.AFTER_PLAYBACK
                || TurntableExtractionMode.byName("always") != TurntableExtractionMode.ALWAYS
                || TurntableExtractionMode.byName("unknown") != TurntableExtractionMode.AFTER_PLAYBACK) {
            throw new AssertionError("turntable extraction mode persistence mapping failed");
        }
    }

    private static void coversMovedAndOversizedProjectorScreens() {
        BlockPos projector = new BlockPos(70, 10, 20);
        AABB wide = ProjectorScreenBounds.aroundBlock(projector,
                5.0D, 3.0D, -4.0D, 0.0D, 0.0D, 3.0D, 8.0D, 0.0D);
        double centerX = projector.getX() + 5.5D;
        double centerY = projector.getY() + 3.0D;
        double centerZ = projector.getZ() - 3.5D;
        double halfHeight = ProjectorScreenBounds.BASE_HEIGHT * 3.0D * 0.5D;
        double halfWidth = halfHeight * 8.0D;
        assertContains(wide, centerX - halfWidth, centerY - halfHeight, centerZ);
        assertContains(wide, centerX + halfWidth, centerY + halfHeight, centerZ);

        AABB rotated = ProjectorScreenBounds.aroundCenter(centerX, centerY, centerZ,
                90.0D, 45.0D, 3.0D, 8.0D, 0.0D);
        double diagonalUp = halfHeight / Math.sqrt(2.0D);
        assertContains(rotated, centerX - diagonalUp, centerY + diagonalUp, centerZ - halfWidth);
        assertContains(rotated, centerX + diagonalUp, centerY - diagonalUp, centerZ + halfWidth);

        AABB edgeInsideRange = ProjectorScreenBounds.aroundCenter(70.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 3.0D, 8.0D, 0.0D);
        double distanceSqr = ProjectorScreenBounds.distanceToSqr(edgeInsideRange, Vec3.ZERO);
        if (distanceSqr >= 64.0D * 64.0D) {
            throw new AssertionError("projector edge inside render range should not be culled: " + distanceSqr);
        }
        if (ProjectorScreenBounds.distanceToSqr(edgeInsideRange, new Vec3(70.0D, 0.0D, 0.0D)) != 0.0D) {
            throw new AssertionError("camera inside projector bounds should have zero distance");
        }
    }

    private static void assertContains(AABB bounds, double x, double y, double z) {
        double epsilon = 1.0e-8D;
        if (x < bounds.minX - epsilon || x > bounds.maxX + epsilon
                || y < bounds.minY - epsilon || y > bounds.maxY + epsilon
                || z < bounds.minZ - epsilon || z > bounds.maxZ + epsilon) {
            throw new AssertionError("projector bounds " + bounds + " do not contain " + x + "," + y + "," + z);
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
