package com.zhongbai233.net_music_can_play_bili.client.diagnostics;

public final class MemoryCircuitBreakerSelfTest {
    private static final long MIB = 1_048_576L;

    private MemoryCircuitBreakerSelfTest() {
    }

    public static void main(String[] args) {
        requiresConsecutiveGrowthAboveLimit();
        stableHighUsageDoesNotTrip();
        oneTimeLargeSessionGrowthDoesNotTrip();
        safeSampleResetsConsecutivePressure();
        enforcesCooldownAndLowWatermark();
        forceOpenIsImmediateAndIdempotent();
        zeroLimitDisablesMetric();
        System.out.println("MemoryCircuitBreakerSelfTest passed");
    }

    private static void requiresConsecutiveGrowthAboveLimit() {
        MemoryCircuitBreaker breaker = breaker();
        check(!breaker.evaluate(10L, sample(101, 0, 0, 0, 0)).tripped(),
                "first high sample must establish a baseline");
        check(!breaker.evaluate(20L, sample(110, 0, 0, 0, 0)).tripped(),
                "first growing sample must not trip");
        check(breaker.evaluate(30L, sample(119, 0, 0, 0, 0)).tripped(),
                "second consecutive growing sample must trip");
        check(!breaker.allowMediaStart(), "open breaker must reject media starts");
    }

    private static void stableHighUsageDoesNotTrip() {
        MemoryCircuitBreaker breaker = breaker();
        MemoryCircuitBreaker.Sample stableEightKPool = sample(600, 600, 1_500, 3_000, 300);
        for (long now = 10L; now <= 100L; now += 10L) {
            check(!breaker.evaluate(now, stableEightKPool).tripped(),
                    "stable high-resolution pool must not be treated as a leak");
        }
        check(breaker.allowMediaStart(), "stable high usage must keep media admission open");
    }

    private static void oneTimeLargeSessionGrowthDoesNotTrip() {
        MemoryCircuitBreaker breaker = breaker();
        check(!breaker.evaluate(10L, sample(90, 90, 90, 90, 90)).tripped(), "baseline must not trip");
        MemoryCircuitBreaker.Sample afterEightKStart = sample(700, 700, 1_600, 3_200, 320);
        check(!breaker.evaluate(20L, afterEightKStart).tripped(), "one large session growth must not trip");
        for (long now = 30L; now <= 100L; now += 10L) {
            check(!breaker.evaluate(now, afterEightKStart).tripped(),
                    "stable pool after a large session start must reset growth pressure");
        }
    }

    private static void enforcesCooldownAndLowWatermark() {
        MemoryCircuitBreaker breaker = breaker();
        breaker.evaluate(10L, sample(101, 0, 0, 0, 0));
        breaker.evaluate(20L, sample(110, 0, 0, 0, 0));
        breaker.evaluate(30L, sample(119, 0, 0, 0, 0));
        check(!breaker.evaluate(500L, sample(10, 0, 0, 0, 0)).recovered(),
                "breaker must remain open during cooldown");
        check(!breaker.evaluate(1_020L, sample(75, 0, 0, 0, 0)).recovered(),
                "breaker must remain open above recovery watermark");
        check(breaker.evaluate(1_030L, sample(49, 0, 0, 0, 0)).recovered(),
                "breaker should recover below low watermark after cooldown");
        check(breaker.allowMediaStart(), "recovered breaker must allow media starts");
    }

    private static void safeSampleResetsConsecutivePressure() {
        MemoryCircuitBreaker breaker = breaker();
        check(!breaker.evaluate(10L, sample(101, 0, 0, 0, 0)).tripped(), "first high sample must not trip");
        check(!breaker.evaluate(20L, sample(110, 0, 0, 0, 0)).tripped(),
            "first growth sample must not trip");
        check(!breaker.evaluate(30L, sample(99, 0, 0, 0, 0)).tripped(),
                "safe sample should reset pressure streak");
        check(!breaker.evaluate(40L, sample(110, 0, 0, 0, 0)).tripped(),
            "new growth streak must start from one");
        check(breaker.evaluate(50L, sample(119, 0, 0, 0, 0)).tripped(),
            "second growth sample in new streak should trip");
    }

    private static void forceOpenIsImmediateAndIdempotent() {
        MemoryCircuitBreaker breaker = breaker();
        check(breaker.forceOpen(1L, "out of memory").tripped(), "forced open must trip immediately");
        check(!breaker.forceOpen(2L, "again").tripped(), "already-open breaker must not retrigger cleanup");
    }

    private static void zeroLimitDisablesMetric() {
        MemoryCircuitBreaker breaker = new MemoryCircuitBreaker(new MemoryCircuitBreaker.Limits(
                0L, 100 * MIB, 100 * MIB, 100 * MIB, 100L, 1, 1_000L, 0.5D));
        check(!breaker.evaluate(1L, sample(10_000, 0, 0, 0, 0)).tripped(),
                "zero limit must disable its metric");
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

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}