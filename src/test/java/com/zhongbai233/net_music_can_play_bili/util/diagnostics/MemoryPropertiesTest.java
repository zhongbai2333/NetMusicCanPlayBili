package com.zhongbai233.net_music_can_play_bili.util.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPropertiesTest {
    private static final long MIB = 1_048_576L;
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        assertEquals(new MemoryProperties.Flags(false, true), MemoryProperties.flags());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(5_000L), MemoryProperties.reportIntervalNanos());

        MemoryProperties.Protection protection = MemoryProperties.protection();
        assertTrue(protection.enabled());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(2_000L), protection.sampleIntervalNanos());
        assertEquals(512L * MIB, protection.ownedNativeBytes());
        assertEquals(512L * MIB, protection.gpuPboBytes());
        assertEquals(1_024L * MIB, protection.ffmpegBytes());
        assertEquals(2_048L * MIB, protection.d3d11LogicalBytes());
        assertEquals(256L, protection.d3d11Surfaces());
        assertEquals(15, protection.consecutiveSamples());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(60_000L), protection.cooldownNanos());
        assertEquals(0.65D, protection.recoveryRatio());
    }

    @Test
    void explicitValuesRemainConfigurable() {
        set(MemoryProperties.DIAGNOSTICS_ENABLED, "true");
        set(MemoryProperties.PROTECTION_ENABLED, "false");
        set(MemoryProperties.REPORT_INTERVAL_MILLIS, "2500");
        set(MemoryProperties.SAMPLE_INTERVAL_MILLIS, "750");
        set(MemoryProperties.OWNED_NATIVE_LIMIT_MIB, "128");
        set(MemoryProperties.GPU_PBO_LIMIT_MIB, "256");
        set(MemoryProperties.FFMPEG_LIMIT_MIB, "768");
        set(MemoryProperties.D3D11_LOGICAL_LIMIT_MIB, "1536");
        set(MemoryProperties.D3D11_SURFACE_LIMIT, "128");
        set(MemoryProperties.CONSECUTIVE_SAMPLES, "4");
        set(MemoryProperties.COOLDOWN_MILLIS, "10000");
        set(MemoryProperties.RECOVERY_RATIO, "0.5");

        assertEquals(new MemoryProperties.Flags(true, false), MemoryProperties.flags());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(2_500L), MemoryProperties.reportIntervalNanos());
        MemoryProperties.Protection protection = MemoryProperties.protection();
        assertFalse(protection.enabled());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(750L), protection.sampleIntervalNanos());
        assertEquals(128L * MIB, protection.ownedNativeBytes());
        assertEquals(256L * MIB, protection.gpuPboBytes());
        assertEquals(768L * MIB, protection.ffmpegBytes());
        assertEquals(1_536L * MIB, protection.d3d11LogicalBytes());
        assertEquals(128L, protection.d3d11Surfaces());
        assertEquals(4, protection.consecutiveSamples());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(10_000L), protection.cooldownNanos());
        assertEquals(0.5D, protection.recoveryRatio());
    }

    @Test
    void invalidValuesUseCompatibilityDefaults() {
        set(MemoryProperties.DIAGNOSTICS_ENABLED, "yes");
        set(MemoryProperties.PROTECTION_ENABLED, "yes");
        set(MemoryProperties.REPORT_INTERVAL_MILLIS, "invalid");
        set(MemoryProperties.OWNED_NATIVE_LIMIT_MIB, "invalid");
        set(MemoryProperties.CONSECUTIVE_SAMPLES, "invalid");
        set(MemoryProperties.RECOVERY_RATIO, "NaN");

        assertEquals(new MemoryProperties.Flags(false, true), MemoryProperties.flags());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(5_000L), MemoryProperties.reportIntervalNanos());
        MemoryProperties.Protection protection = MemoryProperties.protection();
        assertTrue(protection.enabled());
        assertEquals(512L * MIB, protection.ownedNativeBytes());
        assertEquals(15, protection.consecutiveSamples());
        assertEquals(0.65D, protection.recoveryRatio());
    }

    @Test
    void unsafeValuesAreClampedAndLargeValuesSaturate() {
        set(MemoryProperties.REPORT_INTERVAL_MILLIS, "0");
        set(MemoryProperties.SAMPLE_INTERVAL_MILLIS, "0");
        set(MemoryProperties.OWNED_NATIVE_LIMIT_MIB, "-1");
        set(MemoryProperties.GPU_PBO_LIMIT_MIB, Long.toString(Long.MAX_VALUE));
        set(MemoryProperties.CONSECUTIVE_SAMPLES, "0");
        set(MemoryProperties.COOLDOWN_MILLIS, Long.toString(Long.MAX_VALUE));
        set(MemoryProperties.RECOVERY_RATIO, "2.0");

        assertEquals(TimeUnit.MILLISECONDS.toNanos(1_000L), MemoryProperties.reportIntervalNanos());
        MemoryProperties.Protection protection = MemoryProperties.protection();
        assertEquals(TimeUnit.MILLISECONDS.toNanos(500L), protection.sampleIntervalNanos());
        assertEquals(0L, protection.ownedNativeBytes());
        assertEquals(Long.MAX_VALUE, protection.gpuPboBytes());
        assertEquals(1, protection.consecutiveSamples());
        assertEquals(Long.MAX_VALUE, protection.cooldownNanos());
        assertEquals(0.95D, protection.recoveryRatio());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
