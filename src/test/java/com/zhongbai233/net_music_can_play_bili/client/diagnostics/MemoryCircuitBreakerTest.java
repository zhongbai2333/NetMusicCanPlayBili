package com.zhongbai233.net_music_can_play_bili.client.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCircuitBreakerTest {
    private static final long MIB = 1_048_576L;

    @Test
    void requiresConsecutiveGrowthAboveLimit() {
        MemoryCircuitBreaker breaker = breaker();
        assertFalse(breaker.evaluate(10L, sample(101, 0, 0, 0, 0)).tripped());
        assertFalse(breaker.evaluate(20L, sample(110, 0, 0, 0, 0)).tripped());
        assertTrue(breaker.evaluate(30L, sample(119, 0, 0, 0, 0)).tripped());
        assertFalse(breaker.allowMediaStart());
    }

    @Test
    void stableHighUsageDoesNotTrip() {
        MemoryCircuitBreaker breaker = breaker();
        MemoryCircuitBreaker.Sample stableEightKPool = sample(600, 600, 1_500, 3_000, 300);
        for (long now = 10L; now <= 100L; now += 10L) {
            assertFalse(breaker.evaluate(now, stableEightKPool).tripped());
        }
        assertTrue(breaker.allowMediaStart());
    }

    @Test
    void oneTimeLargeSessionGrowthDoesNotTrip() {
        MemoryCircuitBreaker breaker = breaker();
        assertFalse(breaker.evaluate(10L, sample(90, 90, 90, 90, 90)).tripped());
        MemoryCircuitBreaker.Sample afterEightKStart = sample(700, 700, 1_600, 3_200, 320);
        assertFalse(breaker.evaluate(20L, afterEightKStart).tripped());
        for (long now = 30L; now <= 100L; now += 10L) {
            assertFalse(breaker.evaluate(now, afterEightKStart).tripped());
        }
    }

    @Test
    void safeSampleResetsConsecutivePressure() {
        MemoryCircuitBreaker breaker = breaker();
        assertFalse(breaker.evaluate(10L, sample(101, 0, 0, 0, 0)).tripped());
        assertFalse(breaker.evaluate(20L, sample(110, 0, 0, 0, 0)).tripped());
        assertFalse(breaker.evaluate(30L, sample(99, 0, 0, 0, 0)).tripped());
        assertFalse(breaker.evaluate(40L, sample(110, 0, 0, 0, 0)).tripped());
        assertTrue(breaker.evaluate(50L, sample(119, 0, 0, 0, 0)).tripped());
    }

    @Test
    void enforcesCooldownAndLowWatermark() {
        MemoryCircuitBreaker breaker = breaker();
        breaker.evaluate(10L, sample(101, 0, 0, 0, 0));
        breaker.evaluate(20L, sample(110, 0, 0, 0, 0));
        breaker.evaluate(30L, sample(119, 0, 0, 0, 0));
        assertFalse(breaker.evaluate(500L, sample(10, 0, 0, 0, 0)).recovered());
        assertFalse(breaker.evaluate(1_020L, sample(75, 0, 0, 0, 0)).recovered());
        assertTrue(breaker.evaluate(1_030L, sample(49, 0, 0, 0, 0)).recovered());
        assertTrue(breaker.allowMediaStart());
    }

    @Test
    void forceOpenIsImmediateAndIdempotent() {
        MemoryCircuitBreaker breaker = breaker();
        assertTrue(breaker.forceOpen(1L, "out of memory").tripped());
        assertFalse(breaker.forceOpen(2L, "again").tripped());
    }

    @Test
    void zeroLimitDisablesMetric() {
        MemoryCircuitBreaker breaker = new MemoryCircuitBreaker(new MemoryCircuitBreaker.Limits(
                0L, 100 * MIB, 100 * MIB, 100 * MIB, 100L, 1, 1_000L, 0.5D));
        assertFalse(breaker.evaluate(1L, sample(10_000, 0, 0, 0, 0)).tripped());
    }

    private static MemoryCircuitBreaker breaker() {
        return new MemoryCircuitBreaker(new MemoryCircuitBreaker.Limits(
                100 * MIB, 100 * MIB, 100 * MIB, 100 * MIB, 100L, 2, 1_000L, 0.5D));
    }

    private static MemoryCircuitBreaker.Sample sample(long ownedMiB, long pboMiB, long ffmpegMiB,
            long d3d11MiB, long surfaces) {
        return new MemoryCircuitBreaker.Sample(ownedMiB * MIB, pboMiB * MIB, ffmpegMiB * MIB,
                d3d11MiB * MIB, surfaces);
    }
}