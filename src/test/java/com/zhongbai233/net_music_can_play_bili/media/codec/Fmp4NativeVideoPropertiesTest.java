package com.zhongbai233.net_music_can_play_bili.media.codec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Fmp4NativeVideoPropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void decoderAndSeekDefaultsRemainCompatible() {
        Fmp4NativeVideoProperties.Decoder decoder = Fmp4NativeVideoProperties.decoder();
        assertEquals(8, decoder.maxPendingFrames());
        assertEquals(3, decoder.streamRecoveryAttempts());
        assertTrue(decoder.reuseOutputBuffers());
        assertTrue(decoder.directNv12Buffers());
        assertEquals(512, decoder.segmentBaseCacheMaxEntries());

        Fmp4NativeVideoProperties.FirstFrameProbe firstFrameProbe =
                Fmp4NativeVideoProperties.firstFrameProbe();
        assertEquals(2_000L, firstFrameProbe.timeoutMillis());
        assertEquals(256, firstFrameProbe.maxPackets());

        Fmp4NativeVideoProperties.Seek seek = Fmp4NativeVideoProperties.seek();
        assertEquals(4 * 1024 * 1024, seek.initProbeBytes());
        assertEquals(8 * 1024 * 1024, seek.moofScanBytes());
        assertEquals(12, seek.maxAttempts());
        assertEquals(1024L * 1024L, seek.prerollBytes());
        assertEquals(3.0D, seek.closeFragmentSeconds());
        assertEquals(0.25D, seek.targetEpsilonSeconds());
        assertEquals(0.0D, seek.leadSeconds());
        assertFalse(seek.rangeEnabled());
        assertEquals(5_000L, seek.autoOffsetMillis());
        assertEquals(-1.0D, seek.fallbackMaxResidualSeconds());
        assertEquals(1_000L, seek.noCopyDropGuardMillis());
    }

    @Test
    void unsafeNonPositiveCapacitiesAreClamped() {
        set(Fmp4NativeVideoProperties.MAX_PENDING_FRAMES, "0");
        set(Fmp4NativeVideoProperties.INIT_PROBE_BYTES, "0");
        set(Fmp4NativeVideoProperties.MOOF_SCAN_BYTES, "-1");
        set(Fmp4NativeVideoProperties.SEEK_MAX_ATTEMPTS, "0");
        set(Fmp4NativeVideoProperties.SEEK_PREROLL_BYTES, "-1");
        set(Fmp4NativeVideoProperties.CLOSE_FRAGMENT_SECONDS, "-1");
        set(Fmp4NativeVideoProperties.TARGET_EPSILON_SECONDS, "-1");
        set(Fmp4NativeVideoProperties.STREAM_RECOVERY_ATTEMPTS, "-1");
        set(Fmp4NativeVideoProperties.SEGMENT_BASE_CACHE_MAX_ENTRIES, "0");
        set(Fmp4NativeVideoProperties.AV1_FIRST_FRAME_PROBE_TIMEOUT_MILLIS, "0");
        set(Fmp4NativeVideoProperties.AV1_FIRST_FRAME_PROBE_MAX_PACKETS, "0");

        Fmp4NativeVideoProperties.Decoder decoder = Fmp4NativeVideoProperties.decoder();
        assertEquals(1, decoder.maxPendingFrames());
        assertEquals(0, decoder.streamRecoveryAttempts());
        assertEquals(1, decoder.segmentBaseCacheMaxEntries());
        assertEquals(250L, Fmp4NativeVideoProperties.firstFrameProbe().timeoutMillis());
        assertEquals(1, Fmp4NativeVideoProperties.firstFrameProbe().maxPackets());
        Fmp4NativeVideoProperties.Seek seek = Fmp4NativeVideoProperties.seek();
        assertEquals(1, seek.initProbeBytes());
        assertEquals(1, seek.moofScanBytes());
        assertEquals(1, seek.maxAttempts());
        assertEquals(0L, seek.prerollBytes());
        assertEquals(0.0D, seek.closeFragmentSeconds());
        assertEquals(0.0D, seek.targetEpsilonSeconds());
    }

    @Test
    void explicitBufferAndSeekFlagsRemainConfigurable() {
        set(Fmp4NativeVideoProperties.REUSE_OUTPUT_BUFFERS, "false");
        set(Fmp4NativeVideoProperties.DIRECT_NV12_BUFFERS, "false");
        set(Fmp4NativeVideoProperties.RANGE_SEEK_ENABLED, "true");
        set(Fmp4NativeVideoProperties.SEEK_LEAD_SECONDS, "1.5");
        set(Fmp4NativeVideoProperties.FALLBACK_MAX_RESIDUAL_SECONDS, "2.25");

        assertFalse(Fmp4NativeVideoProperties.decoder().reuseOutputBuffers());
        assertFalse(Fmp4NativeVideoProperties.decoder().directNv12Buffers());
        assertTrue(Fmp4NativeVideoProperties.seek().rangeEnabled());
        assertEquals(1.5D, Fmp4NativeVideoProperties.seek().leadSeconds());
        assertEquals(2.25D, Fmp4NativeVideoProperties.seek().fallbackMaxResidualSeconds());
    }

    @Test
    void firstFrameProbeOverridesRemainStrictlyBounded() {
        set(Fmp4NativeVideoProperties.AV1_FIRST_FRAME_PROBE_TIMEOUT_MILLIS, "12000");
        set(Fmp4NativeVideoProperties.AV1_FIRST_FRAME_PROBE_MAX_PACKETS, "5000");

        assertEquals(10_000L, Fmp4NativeVideoProperties.firstFrameProbe().timeoutMillis());
        assertEquals(4_096, Fmp4NativeVideoProperties.firstFrameProbe().maxPackets());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
