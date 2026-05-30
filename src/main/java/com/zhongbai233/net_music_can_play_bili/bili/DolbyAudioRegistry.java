package com.zhongbai233.net_music_can_play_bili.bili;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class DolbyAudioRegistry {
    private static final int MAX_ACTIVE_TURNTABLES = 16;
    private static final AtomicInteger ANONYMOUS_COUNTER = new AtomicInteger();
    private static final ConcurrentMap<BlockPos, DolbyEntry> DOLBY_HANDLERS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<BlockPos, StereoEntry> STEREO_HANDLERS = new ConcurrentHashMap<>();

    private static volatile float[] fallbackMachinePos;
    private static volatile float[] listenerPos;

    public static void register(DolbyAudioHandler handler) {
        register(handler, null);
    }

    public static void register(DolbyAudioHandler handler, BlockPos pos) {
        if (handler == null) {
            return;
        }
        BlockPos key = keyFor(pos);
        DolbyEntry entry = new DolbyEntry(key, centerFor(pos), handler, System.currentTimeMillis());
        closeStereoAt(key);
        DolbyEntry old = DOLBY_HANDLERS.put(key, entry);
        if (old != null && old.handler() != handler) {
            old.cleanup();
        }
        enforceActiveLimit();
    }

    public static void unregister(DolbyAudioHandler handler) {
        if (handler == null) {
            return;
        }
        DOLBY_HANDLERS.entrySet().removeIf(entry -> entry.getValue().handler() == handler);
    }

    public static void registerStereo(StereoOpenALHandler handler) {
        registerStereo(handler, null);
    }

    public static void registerStereo(StereoOpenALHandler handler, BlockPos pos) {
        if (handler == null) {
            return;
        }
        BlockPos key = keyFor(pos);
        StereoEntry entry = new StereoEntry(key, centerFor(pos), handler, System.currentTimeMillis());
        closeDolbyAt(key);
        StereoEntry old = STEREO_HANDLERS.put(key, entry);
        if (old != null && old.handler() != handler) {
            old.cleanup();
        }
        enforceActiveLimit();
    }

    public static void unregisterStereo(StereoOpenALHandler handler) {
        if (handler == null) {
            return;
        }
        STEREO_HANDLERS.entrySet().removeIf(entry -> entry.getValue().handler() == handler);
    }

    public static void updatePositions(float[] listenerPos) {
        DolbyAudioRegistry.listenerPos = copy(listenerPos);
        if (listenerPos == null) {
            return;
        }
        for (DolbyEntry entry : DOLBY_HANDLERS.values()) {
            entry.handler().tick(entry.machinePos(), listenerPos);
        }
        for (StereoEntry entry : STEREO_HANDLERS.values()) {
            entry.handler().tick(entry.machinePos(), listenerPos);
        }
    }

    public static void updatePositions(float[] machinePos, float[] listenerPos) {
        updatePositions(listenerPos);
    }

    public static void setMachinePos(double x, double y, double z) {
        fallbackMachinePos = new float[] { (float) x, (float) y, (float) z };
    }

    public static float[] getMachinePos() {
        DolbyEntry dolby = firstValue(DOLBY_HANDLERS);
        if (dolby != null) {
            return copy(dolby.machinePos());
        }
        StereoEntry stereo = firstValue(STEREO_HANDLERS);
        if (stereo != null) {
            return copy(stereo.machinePos());
        }
        return copy(fallbackMachinePos);
    }

    public static float[] getListenerPos() {
        return copy(listenerPos);
    }

    public static boolean isActive() {
        return !DOLBY_HANDLERS.isEmpty() || !STEREO_HANDLERS.isEmpty();
    }

    public static List<String> describeActiveSources() {
        float[] listener = listenerPos;
        if (!isActive()) {
            return List.of("No active Dolby/OpenAL audio");
        }

        List<String> lines = new ArrayList<>();
        lines.add(String.format("Active OpenAL turntables: dolby=%d stereo=%d",
                DOLBY_HANDLERS.size(), STEREO_HANDLERS.size()));

        DOLBY_HANDLERS.values().stream()
                .sorted(Comparator.comparingLong(DolbyEntry::createdAtMillis))
                .forEach(entry -> {
                    lines.add(String.format("Dolby @ %s", fmtPos(entry.machinePos())));
                    for (String line : entry.handler().describeSources(entry.machinePos(), listener)) {
                        lines.add("  " + line);
                    }
                });
        STEREO_HANDLERS.values().stream()
                .sorted(Comparator.comparingLong(StereoEntry::createdAtMillis))
                .forEach(entry -> {
                    lines.add(String.format("Stereo @ %s", fmtPos(entry.machinePos())));
                    for (String line : entry.handler().describeState()) {
                        lines.add("  " + line);
                    }
                });
        return lines;
    }

    public static void cleanup() {
        for (DolbyEntry entry : DOLBY_HANDLERS.values()) {
            entry.cleanup();
        }
        for (StereoEntry entry : STEREO_HANDLERS.values()) {
            entry.cleanup();
        }
        DOLBY_HANDLERS.clear();
        STEREO_HANDLERS.clear();
    }

    private static void closeDolbyAt(BlockPos key) {
        DolbyEntry old = DOLBY_HANDLERS.remove(key);
        if (old != null) {
            old.cleanup();
        }
    }

    private static void closeStereoAt(BlockPos key) {
        StereoEntry old = STEREO_HANDLERS.remove(key);
        if (old != null) {
            old.cleanup();
        }
    }

    private static void enforceActiveLimit() {
        while (DOLBY_HANDLERS.size() + STEREO_HANDLERS.size() > MAX_ACTIVE_TURNTABLES) {
            AudioEntry oldest = oldestEntry();
            if (oldest == null) {
                return;
            }
            if (oldest instanceof DolbyEntry dolby) {
                if (DOLBY_HANDLERS.remove(dolby.pos(), dolby)) {
                    dolby.cleanup();
                }
            } else if (oldest instanceof StereoEntry stereo) {
                if (STEREO_HANDLERS.remove(stereo.pos(), stereo)) {
                    stereo.cleanup();
                }
            }
        }
    }

    private static AudioEntry oldestEntry() {
        AudioEntry oldest = null;
        for (AudioEntry entry : DOLBY_HANDLERS.values()) {
            oldest = older(oldest, entry);
        }
        for (AudioEntry entry : STEREO_HANDLERS.values()) {
            oldest = older(oldest, entry);
        }
        return oldest;
    }

    private static AudioEntry older(AudioEntry current, AudioEntry candidate) {
        if (current == null || candidate.createdAtMillis() < current.createdAtMillis()) {
            return candidate;
        }
        return current;
    }

    private static <T> T firstValue(Map<?, T> map) {
        for (T value : map.values()) {
            return value;
        }
        return null;
    }

    private static BlockPos keyFor(BlockPos pos) {
        if (pos != null) {
            return copyPos(pos);
        }
        int id = ANONYMOUS_COUNTER.getAndIncrement();
        return new BlockPos(Integer.MIN_VALUE, 0, id);
    }

    private static BlockPos copyPos(BlockPos pos) {
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static float[] centerFor(BlockPos pos) {
        if (pos != null) {
            return new float[] { pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f };
        }
        float[] fallback = fallbackMachinePos;
        return fallback != null ? copy(fallback) : new float[] { 0.0f, 0.0f, 0.0f };
    }

    private static float[] copy(float[] pos) {
        if (pos == null) {
            return null;
        }
        return new float[] { pos[0], pos[1], pos[2] };
    }

    private static String fmtPos(float[] pos) {
        if (pos == null) {
            return "(null)";
        }
        return String.format("(%.2f, %.2f, %.2f)", pos[0], pos[1], pos[2]);
    }

    private interface AudioEntry {
        BlockPos pos();

        float[] machinePos();

        long createdAtMillis();

        void cleanup();
    }

    private record DolbyEntry(BlockPos pos, float[] machinePos, DolbyAudioHandler handler, long createdAtMillis)
            implements AudioEntry {
        @Override
        public void cleanup() {
            handler.cleanup();
        }
    }

    private record StereoEntry(BlockPos pos, float[] machinePos, StereoOpenALHandler handler, long createdAtMillis)
            implements AudioEntry {
        @Override
        public void cleanup() {
            handler.cleanup();
        }
    }
}
