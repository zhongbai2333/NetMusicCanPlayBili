package com.zhongbai233.net_music_can_play_bili.client.diagnostics;

/**
 * High-watermark growth circuit breaker with cooldown and low-water recovery.
 * Stable bounded pools may remain above the observation watermark without
 * tripping. The class is independent from Minecraft so its state transitions
 * can be tested without launching a client.
 */
final class MemoryCircuitBreaker {
    private static final long MIN_BYTE_GROWTH = 8L * 1_048_576L;

    record Limits(long ownedNativeBytes, long gpuPboBytes, long ffmpegBytes,
            long d3d11LogicalBytes, long d3d11Surfaces, int consecutiveSamples,
            long cooldownNanos, double recoveryRatio) {
        Limits {
            consecutiveSamples = Math.max(1, consecutiveSamples);
            cooldownNanos = Math.max(0L, cooldownNanos);
            recoveryRatio = Math.max(0.05D, Math.min(0.95D, recoveryRatio));
        }
    }

    record Sample(long ownedNativeBytes, long gpuPboBytes, long ffmpegBytes,
            long d3d11LogicalBytes, long d3d11Surfaces) {
    }

    record Evaluation(boolean tripped, boolean recovered, String reason) {
        static Evaluation none() {
            return new Evaluation(false, false, "");
        }
    }

    private final Limits limits;
    private int pressureSamples;
    private boolean open;
    private long reopenAfterNanos;
    private String reason = "";
    private Sample previousSample;

    MemoryCircuitBreaker(Limits limits) {
        this.limits = limits;
    }

    synchronized Evaluation evaluate(long nowNanos, Sample sample) {
        if (open) {
            if (nowNanos >= reopenAfterNanos && belowRecoveryWatermark(sample)) {
                open = false;
                pressureSamples = 0;
                reason = "";
                previousSample = sample;
                return new Evaluation(false, true, "resources returned below recovery watermark");
            }
            return Evaluation.none();
        }

        Sample previous = previousSample;
        previousSample = sample;
        String growing = previous != null ? growingExceededLimit(previous, sample) : "";
        if (growing.isEmpty()) {
            pressureSamples = 0;
            return Evaluation.none();
        }
        pressureSamples++;
        if (pressureSamples < limits.consecutiveSamples()) {
            return Evaluation.none();
        }
        return open(nowNanos, growing);
    }

    synchronized Evaluation forceOpen(long nowNanos, String forcedReason) {
        return open(nowNanos, forcedReason == null || forcedReason.isBlank()
                ? "allocation failure"
                : forcedReason);
    }

    synchronized boolean allowMediaStart() {
        return !open;
    }

    synchronized String reason() {
        return reason;
    }

    private Evaluation open(long nowNanos, String openReason) {
        boolean newlyOpened = !open;
        open = true;
        reason = openReason;
        reopenAfterNanos = saturatedAdd(nowNanos, limits.cooldownNanos());
        return newlyOpened ? new Evaluation(true, false, reason) : Evaluation.none();
    }

    private String growingExceededLimit(Sample previous, Sample sample) {
        if (growingBytes(previous.ownedNativeBytes(), sample.ownedNativeBytes(), limits.ownedNativeBytes())) {
            return "NCPB native buffers " + mib(sample.ownedNativeBytes()) + " > " + mib(limits.ownedNativeBytes());
        }
        if (growingBytes(previous.gpuPboBytes(), sample.gpuPboBytes(), limits.gpuPboBytes())) {
            return "NCPB PBO estimate " + mib(sample.gpuPboBytes()) + " > " + mib(limits.gpuPboBytes());
        }
        if (growingBytes(previous.ffmpegBytes(), sample.ffmpegBytes(), limits.ffmpegBytes())) {
            return "FFmpeg heap " + mib(sample.ffmpegBytes()) + " > " + mib(limits.ffmpegBytes());
        }
        if (growingBytes(previous.d3d11LogicalBytes(), sample.d3d11LogicalBytes(), limits.d3d11LogicalBytes())) {
            return "D3D11 logical resources " + mib(sample.d3d11LogicalBytes()) + " > "
                    + mib(limits.d3d11LogicalBytes());
        }
        if (growingCount(previous.d3d11Surfaces(), sample.d3d11Surfaces(), limits.d3d11Surfaces())) {
            return "D3D11 surfaces " + sample.d3d11Surfaces() + " > " + limits.d3d11Surfaces();
        }
        return "";
    }

    private static boolean growingBytes(long previous, long current, long limit) {
        long minimumGrowth = Math.min(MIN_BYTE_GROWTH, Math.max(1L, limit / 100L));
        return exceeds(current, limit) && current > previous && current - previous >= minimumGrowth;
    }

    private static boolean growingCount(long previous, long current, long limit) {
        return exceeds(current, limit) && current > previous;
    }

    private boolean belowRecoveryWatermark(Sample sample) {
        return below(sample.ownedNativeBytes(), limits.ownedNativeBytes())
                && below(sample.gpuPboBytes(), limits.gpuPboBytes())
                && below(sample.ffmpegBytes(), limits.ffmpegBytes())
                && below(sample.d3d11LogicalBytes(), limits.d3d11LogicalBytes())
                && below(sample.d3d11Surfaces(), limits.d3d11Surfaces());
    }

    private boolean below(long value, long limit) {
        return limit <= 0L || value < (long) (limit * limits.recoveryRatio());
    }

    private static boolean exceeds(long value, long limit) {
        return limit > 0L && value > limit;
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        return result < left ? Long.MAX_VALUE : result;
    }

    private static String mib(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1fMiB", bytes / 1_048_576.0D);
    }
}