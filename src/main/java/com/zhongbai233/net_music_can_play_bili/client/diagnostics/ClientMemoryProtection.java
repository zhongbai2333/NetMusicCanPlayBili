package com.zhongbai233.net_music_can_play_bili.client.diagnostics;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder.NativeMemoryStats;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker.Category;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker.Usage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/** Low-frequency guard against runaway media-native and GPU resource growth. */
public final class ClientMemoryProtection {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long MIB = 1_048_576L;
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("ncpb.memory.protection", "true"));
    private static final long SAMPLE_INTERVAL_NANOS = Math.max(500L,
            Long.getLong("ncpb.memory.protection.sample_interval_ms", 2_000L)) * 1_000_000L;
    private static final MemoryCircuitBreaker BREAKER = new MemoryCircuitBreaker(new MemoryCircuitBreaker.Limits(
            mibProperty("ncpb.memory.protection.owned_native_mib", 512L),
            mibProperty("ncpb.memory.protection.gpu_pbo_mib", 512L),
            mibProperty("ncpb.memory.protection.ffmpeg_mib", 1_024L),
            mibProperty("ncpb.memory.protection.d3d11_logical_mib", 2_048L),
            Long.getLong("ncpb.memory.protection.d3d11_surfaces", 256L),
            Integer.getInteger("ncpb.memory.protection.consecutive_samples", 15),
            Math.max(5_000L, Long.getLong("ncpb.memory.protection.cooldown_ms", 60_000L)) * 1_000_000L,
            doubleProperty("ncpb.memory.protection.recovery_ratio", 0.65D)));

    private static volatile long nextSampleNanos;

    private ClientMemoryProtection() {
    }

    public static void tick(Runnable emergencyCleanup) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        if (now < nextSampleNanos) {
            return;
        }
        nextSampleNanos = now + SAMPLE_INTERVAL_NANOS;
        MemoryCircuitBreaker.Evaluation evaluation = BREAKER.evaluate(now, sample());
        if (evaluation.tripped()) {
            trip(evaluation.reason(), emergencyCleanup);
        } else if (evaluation.recovered()) {
            LOGGER.info("NCPB内存保护已恢复，允许新媒体会话: {}", evaluation.reason());
            notifyPlayer("媒体内存已回到安全水位，播放熔断已恢复", ChatFormatting.GREEN);
        }
    }

    public static boolean allowMediaStart() {
        return !ENABLED || BREAKER.allowMediaStart();
    }

    public static String rejectionReason() {
        return BREAKER.reason();
    }

    public static boolean tripOnAllocationFailure(String reason, Runnable emergencyCleanup) {
        if (!ENABLED) {
            return false;
        }
        MemoryCircuitBreaker.Evaluation evaluation = BREAKER.forceOpen(System.nanoTime(), reason);
        if (!evaluation.tripped()) {
            return false;
        }
        trip(evaluation.reason(), emergencyCleanup);
        return true;
    }

    private static void trip(String reason, Runnable emergencyCleanup) {
        LOGGER.error("NCPB媒体内存保护熔断: {}。停止媒体并进入冷却期", reason);
        notifyPlayer("媒体内存保护已熔断：" + reason + "；已停止媒体并进入冷却", ChatFormatting.RED);
        if (emergencyCleanup != null) {
            Minecraft.getInstance().execute(emergencyCleanup);
        }
    }

    private static MemoryCircuitBreaker.Sample sample() {
        Usage decoder = MemoryResourceTracker.usage(Category.DECODER_NV12);
        Usage staging = MemoryResourceTracker.usage(Category.TEXTURE_STAGING);
        Usage audio = MemoryResourceTracker.usage(Category.AUDIO_STAGING);
        Usage pbo = MemoryResourceTracker.usage(Category.GPU_PBO);
        long ownedNative = saturatedAdd(saturatedAdd(decoder.currentBytes(), staging.currentBytes()),
                audio.currentBytes());
        NativeMemoryStats nativeStats = VideoNativeDecoder.nativeMemoryStats();
        return new MemoryCircuitBreaker.Sample(ownedNative, pbo.currentBytes(),
                nativeStats.available() ? nativeStats.ffmpegCurrentBytes() : 0L,
                nativeStats.available() ? nativeStats.d3d11LogicalBytesCurrent() : 0L,
                nativeStats.available() ? nativeStats.d3d11SurfaceCurrent() : 0L);
    }

    private static void notifyPlayer(String message, ChatFormatting color) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.literal(message).withStyle(color));
            }
        });
    }

    private static long mibProperty(String key, long fallbackMiB) {
        long mib = Math.max(0L, Long.getLong(key, fallbackMiB));
        return mib > Long.MAX_VALUE / MIB ? Long.MAX_VALUE : mib * MIB;
    }

    private static double doubleProperty(String key, double fallback) {
        try {
            return Double.parseDouble(System.getProperty(key, Double.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}