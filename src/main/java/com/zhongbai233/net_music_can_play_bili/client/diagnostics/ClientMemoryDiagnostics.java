package com.zhongbai233.net_music_can_play_bili.client.diagnostics;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder.NativeMemoryStats;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker.Category;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker.Usage;
import org.slf4j.Logger;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.Locale;

/** Low-frequency JVM, process and mod-owned native memory diagnostics. */
public final class ClientMemoryDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = MemoryResourceTracker.enabled();
    private static final long REPORT_INTERVAL_NANOS = Math.max(1_000L,
            Long.getLong("ncpb.memory.report_interval_ms", 5_000L)) * 1_000_000L;
    private static volatile long nextReportNanoTime;

    private ClientMemoryDiagnostics() {
    }

    public static void tick() {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        if (now < nextReportNanoTime) {
            return;
        }
        nextReportNanoTime = now + REPORT_INTERVAL_NANOS;
        report("periodic");
    }

    public static void report(String reason) {
        if (!ENABLED) {
            return;
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        BufferUsage direct = bufferUsage("direct");
        BufferUsage mapped = bufferUsage("mapped");
        long committedVirtual = committedVirtualMemory();
        long gcCount = 0L;
        long gcMillis = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0L, gc.getCollectionCount());
            gcMillis += Math.max(0L, gc.getCollectionTime());
        }

        Usage decoder = MemoryResourceTracker.usage(Category.DECODER_NV12);
        Usage staging = MemoryResourceTracker.usage(Category.TEXTURE_STAGING);
        Usage audio = MemoryResourceTracker.usage(Category.AUDIO_STAGING);
        Usage pbo = MemoryResourceTracker.usage(Category.GPU_PBO);
        long ownedNative = decoder.currentBytes() + staging.currentBytes() + audio.currentBytes();
        long ownedNativePeak = decoder.peakBytes() + staging.peakBytes() + audio.peakBytes();

        LOGGER.info(
                "NCPB内存[{}]: heap={}/{}/{} nonHeap={}/{} direct={}/{}({}) mapped={}/{}({}) processCommit={} threads={} gc={}/{}ms",
                reason, mib(heap.getUsed()), mib(heap.getCommitted()), mib(heap.getMax()),
                mib(nonHeap.getUsed()), mib(nonHeap.getCommitted()),
                mib(direct.memoryUsed()), mib(direct.capacity()), direct.count(),
                mib(mapped.memoryUsed()), mib(mapped.capacity()), mapped.count(),
                mib(committedVirtual), ManagementFactory.getThreadMXBean().getThreadCount(), gcCount, gcMillis);
        LOGGER.info(
            "NCPB自有内存: nativeExact={} peakSum={} [decoderNv12={}/{} staging={}/{} audio={}/{}] gpuPboEstimate={}/{}; 不含普通GPU纹理/FBO、OpenAL驱动缓冲，数值不可与processCommit直接相加",
                mib(ownedNative), mib(ownedNativePeak),
                mib(decoder.currentBytes()), mib(decoder.peakBytes()),
                mib(staging.currentBytes()), mib(staging.peakBytes()),
                mib(audio.currentBytes()), mib(audio.peakBytes()),
                mib(pbo.currentBytes()), mib(pbo.peakBytes()));
            NativeMemoryStats nativeStats = VideoNativeDecoder.nativeMemoryStats();
            if (nativeStats.available()) {
                LOGGER.info(
                    "NCPB FFmpeg内存: avHeap={}/{} alloc/realloc/free={}/{}/{} d3d11Textures={}/{} d3d11Surfaces={}/{} d3d11LogicalEstimate={}/{}; D3D11逻辑容量不等于实际显存或进程提交",
                    mib(nativeStats.ffmpegCurrentBytes()), mib(nativeStats.ffmpegPeakBytes()),
                    nativeStats.allocations(), nativeStats.reallocations(), nativeStats.frees(),
                    nativeStats.d3d11TextureCurrent(), nativeStats.d3d11TexturePeak(),
                    nativeStats.d3d11SurfaceCurrent(), nativeStats.d3d11SurfacePeak(),
                    mib(nativeStats.d3d11LogicalBytesCurrent()), mib(nativeStats.d3d11LogicalBytesPeak()));
            }
    }

    private static BufferUsage bufferUsage(String requestedName) {
        long count = 0L;
        long memoryUsed = 0L;
        long capacity = 0L;
        List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        for (BufferPoolMXBean pool : pools) {
            if (pool.getName().toLowerCase(Locale.ROOT).startsWith(requestedName)) {
                count += Math.max(0L, pool.getCount());
                memoryUsed += Math.max(0L, pool.getMemoryUsed());
                capacity += Math.max(0L, pool.getTotalCapacity());
            }
        }
        return new BufferUsage(count, memoryUsed, capacity);
    }

    private static long committedVirtualMemory() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
            return Math.max(0L, extended.getCommittedVirtualMemorySize());
        }
        return -1L;
    }

    private static String mib(long bytes) {
        if (bytes < 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.1fMiB", bytes / 1_048_576.0D);
    }

    private record BufferUsage(long count, long memoryUsed, long capacity) {
    }
}