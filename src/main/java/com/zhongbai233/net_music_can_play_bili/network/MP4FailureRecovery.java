package com.zhongbai233.net_music_can_play_bili.network;

import java.util.*;
import java.util.function.Consumer;

/** Owns one finite automatic failure traversal. Actual server paths use its callbacks for start/stop/notice. */
final class MP4FailureRecovery {
    static final int MAX_ATTEMPTS = 64;
    private final Map<UUID, Traversal> automatic = new LinkedHashMap<>();

    void beginAutomatic(UUID device, List<String> queue) {
        automatic.remove(device);
        if (automatic.size() >= 256) automatic.remove(automatic.keySet().iterator().next());
        automatic.put(device, new Traversal(queue));
    }
    void beginDiscovery(UUID device, List<String> queue) {
        if (!automatic.containsKey(device)) beginAutomatic(device, queue);
    }
    void cancel(UUID device) { automatic.remove(device); }
    void clear() { automatic.clear(); }

    void recover(UUID device, List<String> queue, int failedIndex, int repeatMode, boolean shuffle, int draw,
            Consumer<Integer> start, Runnable stop, Consumer<String> notice) {
        Traversal traversal = automatic.get(device);
        if (traversal == null) {
            stop.run();
            notice.accept("所选条目播放失败，已停止；请重试或选择其他条目。");
            return;
        }
        if (!traversal.queue.equals(queue) || failedIndex < 0 || failedIndex >= queue.size()) {
            cancel(device);
            stop.run();
            notice.accept("播放失败且队列已变化，已停止；请重新选曲。");
            return;
        }
        traversal.failed.add(failedIndex);
        int budget = Math.min(queue.size(), MAX_ATTEMPTS);
        List<Integer> candidates = new ArrayList<>();
        if (traversal.failed.size() < budget && repeatMode != 1) {
            for (int step = 1; step <= queue.size(); step++) {
                int next = failedIndex + step;
                if (next >= queue.size()) {
                    if (!shuffle && repeatMode != 2) break;
                    next %= queue.size();
                }
                if (!traversal.failed.contains(next)) candidates.add(next);
            }
        }
        if (candidates.isEmpty()) {
            cancel(device);
            stop.run();
            notice.accept("自动播放失败，已停止（无可用下一项或已达到 " + budget + " 项尝试上限）。");
            return;
        }
        int next = candidates.get(shuffle ? Math.floorMod(draw, candidates.size()) : 0);
        notice.accept("自动播放条目失败，已跳过；正在尝试第 " + (next + 1) + " 项（最多 " + budget + " 项）。");
        start.accept(next);
    }
    private static final class Traversal {
        final List<String> queue;
        final Set<Integer> failed = new HashSet<>();
        Traversal(List<String> queue) { this.queue = new ArrayList<>(queue); }
    }
}
