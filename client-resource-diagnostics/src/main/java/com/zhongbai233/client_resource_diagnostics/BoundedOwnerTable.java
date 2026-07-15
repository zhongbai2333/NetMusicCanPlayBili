package com.zhongbai233.client_resource_diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BoundedOwnerTable {
    private static final int MAX_OWNERS = 256;
    private final Map<String, MutableStats> owners = new LinkedHashMap<>();
    private long totalBufferBytes;
    private long totalTextureBytes;
    private long totalDirtySections;

    synchronized void addResource(String owner, long bytes, boolean texture) {
        MutableStats stats = get(owner);
        if (texture) {
            stats.textureCount++;
            stats.textureBytes = saturatingAdd(stats.textureBytes, bytes);
            stats.texturePeak = Math.max(stats.texturePeak, stats.textureBytes);
        } else {
            stats.bufferCount++;
            stats.bufferBytes = saturatingAdd(stats.bufferBytes, bytes);
            stats.bufferPeak = Math.max(stats.bufferPeak, stats.bufferBytes);
        }
        if (texture) {
            totalTextureBytes = saturatingAdd(totalTextureBytes, bytes);
        } else {
            totalBufferBytes = saturatingAdd(totalBufferBytes, bytes);
        }
    }

    synchronized void removeResource(String owner, long bytes, boolean texture) {
        MutableStats stats = get(owner);
        if (texture) {
            stats.textureCount = Math.max(0, stats.textureCount - 1);
            stats.textureBytes = Math.max(0L, stats.textureBytes - bytes);
        } else {
            stats.bufferCount = Math.max(0, stats.bufferCount - 1);
            stats.bufferBytes = Math.max(0L, stats.bufferBytes - bytes);
        }
        if (texture) {
            totalTextureBytes = Math.max(0L, totalTextureBytes - bytes);
        } else {
            totalBufferBytes = Math.max(0L, totalBufferBytes - bytes);
        }
    }

    synchronized void addDirty(String owner, long sections) {
        MutableStats stats = get(owner);
        stats.dirtyCalls = saturatingAdd(stats.dirtyCalls, 1L);
        stats.dirtySections = saturatingAdd(stats.dirtySections, sections);
        totalDirtySections = saturatingAdd(totalDirtySections, sections);
    }

    synchronized Totals totals() {
        return new Totals(totalBufferBytes, totalTextureBytes, totalDirtySections);
    }

    synchronized List<OwnerSnapshot> snapshot() {
        List<OwnerSnapshot> result = new ArrayList<>(owners.size());
        owners.forEach((owner, s) -> result.add(new OwnerSnapshot(owner, s.bufferCount, s.bufferBytes,
                s.bufferPeak, s.textureCount, s.textureBytes, s.texturePeak, s.dirtyCalls, s.dirtySections)));
        result.sort(Comparator.comparingLong(OwnerSnapshot::score).reversed());
        return result;
    }

    synchronized void resetActivity() {
        owners.values().forEach(stats -> {
            stats.dirtyCalls = 0;
            stats.dirtySections = 0;
            stats.bufferPeak = stats.bufferBytes;
            stats.texturePeak = stats.textureBytes;
        });
        totalDirtySections = 0L;
    }

    private MutableStats get(String owner) {
        MutableStats found = owners.get(owner);
        if (found != null) {
            return found;
        }
        String key = owners.size() < MAX_OWNERS ? owner : "overflow";
        return owners.computeIfAbsent(key, ignored -> new MutableStats());
    }

    private static long saturatingAdd(long a, long b) {
        return b > 0L && a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    private static final class MutableStats {
        long bufferCount;
        long bufferBytes;
        long bufferPeak;
        long textureCount;
        long textureBytes;
        long texturePeak;
        long dirtyCalls;
        long dirtySections;
    }

    record OwnerSnapshot(String owner, long bufferCount, long bufferBytes, long bufferPeak,
            long textureCount, long textureBytes, long texturePeak, long dirtyCalls, long dirtySections) {
        long score() {
            return saturatingAdd(Math.max(bufferPeak, texturePeak),
                    Math.min(dirtySections, 1_000_000_000L) * 1024L);
        }
    }

    record Totals(long bufferBytes, long textureBytes, long dirtySections) {
    }
}