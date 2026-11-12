package com.zhongbai233.net_music_can_play_bili.util.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
