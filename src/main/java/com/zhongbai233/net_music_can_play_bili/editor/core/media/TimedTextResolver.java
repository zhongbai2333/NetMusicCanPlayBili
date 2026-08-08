package com.zhongbai233.net_music_can_play_bili.editor.core.media;

import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;

import java.util.ArrayList;
import java.util.List;

/** 从按游戏 tick 排序的文本轨中解析当前行。 */
public final class TimedTextResolver {
    private TimedTextResolver() {
    }

    public static int keyAt(int[] sortedTicks, int tick) {
        if (sortedTicks == null || sortedTicks.length == 0 || tick < 0) {
            return Integer.MIN_VALUE;
        }
        int low = 0;
        int high = sortedTicks.length - 1;
        int selected = sortedTicks[0];
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int candidate = sortedTicks[middle];
            if (candidate <= tick) {
                selected = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return selected;
    }

    public static Window window(Int2ObjectSortedMap<String> lines, int tick, int before, int after) {
        return window(lines, tick, before, after, tick);
    }

    /** 行选择使用整数 tick，滚动进度使用连续 tick，避免 20 Hz 时间线造成动画阶梯。 */
    public static Window window(Int2ObjectSortedMap<String> lines, int tick, int before, int after,
            float continuousTick) {
        if (lines == null || lines.isEmpty()) {
            return Window.empty();
        }
        int currentKey = keyAt(lines.keySet().toIntArray(), tick);
        if (currentKey == Integer.MIN_VALUE) {
            currentKey = lines.firstIntKey();
        }
        List<String> past = new ArrayList<>();
        int[] keys = lines.keySet().toIntArray();
        for (int i = keys.length - 1; i >= 0 && past.size() < Math.max(0, before); i--) {
            if (keys[i] >= currentKey) {
                continue;
            }
            String value = lines.get(keys[i]);
            if (value != null && !value.isBlank()) {
                past.add(0, value);
            }
        }
        List<String> future = new ArrayList<>();
        int nextKey = -1;
        for (int key : keys) {
            if (key <= currentKey) {
                continue;
            }
            String value = lines.get(key);
            if (value != null && !value.isBlank()) {
                if (nextKey < 0) {
                    nextKey = key;
                }
                if (future.size() < Math.max(0, after)) {
                    future.add(value);
                }
            }
        }
        float progress = scrollProgress(currentKey, nextKey, continuousTick);
        return new Window(lines.getOrDefault(currentKey, ""), List.copyOf(past), List.copyOf(future),
                currentKey, nextKey, progress);
    }

    public static float scrollProgress(int currentKey, int nextKey, int tick) {
        return scrollProgress(currentKey, nextKey, (float) tick);
    }

    public static float scrollProgress(int currentKey, int nextKey, float tick) {
        if (nextKey <= currentKey || tick < currentKey) {
            return 1.0F;
        }
        long gapMillis = Math.max(1L, (long) (nextKey - currentKey) * 50L);
        long duration = Math.min(500L, Math.max(120L, gapMillis * 2L / 3L));
        float elapsed = Math.max(0.0F, (tick - currentKey) * 50.0F);
        float raw = Math.clamp(elapsed / duration, 0.0F, 1.0F);
        return 1.0F - (float) Math.pow(1.0F - raw, 3.0F);
    }

    public record Window(String current, List<String> past, List<String> future,
            int currentKey, int nextKey, float progress) {
        public static Window empty() {
            return new Window("", List.of(), List.of(), Integer.MIN_VALUE, -1, 1.0F);
        }
    }
}