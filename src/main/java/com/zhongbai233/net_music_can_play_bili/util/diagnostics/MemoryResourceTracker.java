package com.zhongbai233.net_music_can_play_bili.util.diagnostics;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks allocations explicitly owned by this mod without retaining resource
 * objects.
 */
public final class MemoryResourceTracker {
    private static final boolean DIAGNOSTICS_ENABLED = Boolean.parseBoolean(
            System.getProperty("ncpb.memory.diagnostics", "false"));
    private static final boolean PROTECTION_ENABLED = Boolean.parseBoolean(
            System.getProperty("ncpb.memory.protection", "true"));
    private static final boolean TRACKING_ENABLED = DIAGNOSTICS_ENABLED || PROTECTION_ENABLED;

    public enum Category {
        DECODER_NV12("decoderNv12"),
        TEXTURE_STAGING("textureStaging"),
        AUDIO_STAGING("audioStaging"),
        GPU_PBO("gpuPbo");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Usage(long currentBytes, long peakBytes, long allocations, long frees) {
    }

    private static final Map<Category, Counter> COUNTERS = new EnumMap<>(Category.class);
    private static final Usage EMPTY_USAGE = new Usage(0L, 0L, 0L, 0L);

    static {
        if (TRACKING_ENABLED) {
            for (Category category : Category.values()) {
                COUNTERS.put(category, new Counter());
            }
        }
    }

    private MemoryResourceTracker() {
    }

    public static boolean enabled() {
        return DIAGNOSTICS_ENABLED;
    }

    public static boolean trackingEnabled() {
        return TRACKING_ENABLED;
    }

    public static void allocated(Category category, long bytes) {
        if (!TRACKING_ENABLED || bytes <= 0L) {
            return;
        }
        Counter counter = COUNTERS.get(category);
        long current = counter.current.addAndGet(bytes);
        counter.allocations.incrementAndGet();
        counter.peak.accumulateAndGet(current, Math::max);
    }

    public static void freed(Category category, long bytes) {
        if (!TRACKING_ENABLED || bytes <= 0L) {
            return;
        }
        Counter counter = COUNTERS.get(category);
        long current = counter.current.addAndGet(-bytes);
        counter.frees.incrementAndGet();
        if (current < 0L) {
            counter.current.compareAndSet(current, 0L);
        }
    }

    public static Usage usage(Category category) {
        if (!TRACKING_ENABLED) {
            return EMPTY_USAGE;
        }
        Counter counter = COUNTERS.get(category);
        return new Usage(counter.current.get(), counter.peak.get(), counter.allocations.get(), counter.frees.get());
    }

    private static final class Counter {
        private final AtomicLong current = new AtomicLong();
        private final AtomicLong peak = new AtomicLong();
        private final AtomicLong allocations = new AtomicLong();
        private final AtomicLong frees = new AtomicLong();
    }
}