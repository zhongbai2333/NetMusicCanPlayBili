package com.zhongbai233.net_music_can_play_bili.client.pad;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** 低频 Pad 地图/GUI 性能汇总日志；生产环境默认不在 INFO 打印，DEBUG/TRACE 或显式属性开启时才统计。 */
public final class PadPerfLogger {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean EXPLICIT_ENABLED = Boolean
            .parseBoolean(System.getProperty("ncpb.pad.perf_log", "false"));
    private static final long SLOW_WARN_NS = Long.getLong("ncpb.pad.perf_slow_warn_ms", 12L) * 1_000_000L;
    private static final long SLOW_WARN_COOLDOWN_NS = Long.getLong("ncpb.pad.perf_slow_warn_cooldown_ms", 5000L)
            * 1_000_000L;
    private static final long REPORT_INTERVAL_NS = 5_000_000_000L;
    private static long nextReportNs = System.nanoTime() + REPORT_INTERVAL_NS;
    private static long nextSlowWarnNs;
    private static long sampleStepNs;
    private static long sampleStepMaxNs;
    private static int sampleSteps;
    private static int sampleJobs;
    private static int sampleJobStepsTotal;
    private static int sampleJobStepsMax;
    private static long bakeNs;
    private static long bakeMaxNs;
    private static int bakes;
    private static long guiNs;
    private static long guiMaxNs;
    private static int guiFrames;
    private static int cellCacheHits;
    private static int cellCacheMisses;

    private PadPerfLogger() {
    }

    public static void recordSampleStep(long ns) {
        maybeWarnSlow("sampleStep", ns);
        if (!enabled()) {
            return;
        }
        sampleStepNs += ns;
        sampleStepMaxNs = Math.max(sampleStepMaxNs, ns);
        sampleSteps++;
        maybeReport();
    }

    public static void recordSampleJobComplete(int steps) {
        if (!enabled()) {
            return;
        }
        sampleJobs++;
        sampleJobStepsTotal += steps;
        sampleJobStepsMax = Math.max(sampleJobStepsMax, steps);
        maybeReport();
    }

    public static void recordCellCacheHit() {
        if (!enabled()) {
            return;
        }
        cellCacheHits++;
    }

    public static void recordCellCacheMiss() {
        if (!enabled()) {
            return;
        }
        cellCacheMisses++;
    }

    public static void recordMapBake(long ns) {
        maybeWarnSlow("mapBake", ns);
        if (!enabled()) {
            return;
        }
        bakeNs += ns;
        bakeMaxNs = Math.max(bakeMaxNs, ns);
        bakes++;
        maybeReport();
    }

    public static void recordGuiFrame(long ns) {
        maybeWarnSlow("guiFrame", ns);
        if (!enabled()) {
            return;
        }
        guiNs += ns;
        guiMaxNs = Math.max(guiMaxNs, ns);
        guiFrames++;
        maybeReport();
    }

    private static void maybeReport() {
        long now = System.nanoTime();
        if (now < nextReportNs) {
            return;
        }
        nextReportNs = now + REPORT_INTERVAL_NS;
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(
                    "Pad性能峰值: sampleStepMax={}ms jobStepsMax={} mapBakeMax={}ms guiFrameMax={}ms",
                    millis(sampleStepMaxNs), sampleJobStepsMax, millis(bakeMaxNs), millis(guiMaxNs));
        }
        LOGGER.debug(
                "Pad性能: sampleSteps={} avg={}ms max={}ms jobs={} jobStepsAvg={} jobStepsMax={} cacheHit={} cacheMiss={} hitRate={} mapBakes={} avg={}ms max={}ms guiFrames={} avg={}ms max={}ms",
                sampleSteps, millis(avg(sampleStepNs, sampleSteps)), millis(sampleStepMaxNs), sampleJobs,
                avgInt(sampleJobStepsTotal, sampleJobs), sampleJobStepsMax,
                cellCacheHits, cellCacheMisses, hitRate(), bakes, millis(avg(bakeNs, bakes)), millis(bakeMaxNs),
                guiFrames, millis(avg(guiNs, guiFrames)), millis(guiMaxNs));
        sampleStepNs = 0L;
        sampleStepMaxNs = 0L;
        sampleSteps = 0;
        sampleJobs = 0;
        sampleJobStepsTotal = 0;
        sampleJobStepsMax = 0;
        bakeNs = 0L;
        bakeMaxNs = 0L;
        bakes = 0;
        guiNs = 0L;
        guiMaxNs = 0L;
        guiFrames = 0;
        cellCacheHits = 0;
        cellCacheMisses = 0;
    }

    private static void maybeWarnSlow(String phase, long ns) {
        if (SLOW_WARN_NS <= 0L || ns < SLOW_WARN_NS || !LOGGER.isDebugEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now < nextSlowWarnNs) {
            return;
        }
        nextSlowWarnNs = now + SLOW_WARN_COOLDOWN_NS;
        LOGGER.debug("Pad慢操作: phase={} cost={}ms", phase, millis(ns));
    }

    private static boolean enabled() {
        return EXPLICIT_ENABLED || LOGGER.isDebugEnabled() || LOGGER.isTraceEnabled();
    }

    private static long avg(long total, int count) {
        return count <= 0 ? 0L : total / count;
    }

    private static int avgInt(int total, int count) {
        return count <= 0 ? 0 : Math.round(total / (float) count);
    }

    private static String millis(long ns) {
        return String.format(java.util.Locale.ROOT, "%.3f", ns / 1_000_000.0D);
    }

    private static String hitRate() {
        int total = cellCacheHits + cellCacheMisses;
        if (total <= 0) {
            return "0.0%";
        }
        return String.format(java.util.Locale.ROOT, "%.1f%%", cellCacheHits * 100.0D / total);
    }
}
