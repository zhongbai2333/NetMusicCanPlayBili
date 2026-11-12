package com.zhongbai233.net_music_can_play_bili.network;

import java.util.List;
import java.util.Objects;

/** Pure queue reconciliation and playback-completion decisions for MP4 sessions. */
final class MP4PlaybackQueuePolicy {
    private MP4PlaybackQueuePolicy() {
    }

    static Reconciliation reconcile(int currentIndex, String currentSourceUrl, List<String> queueSourceUrls) {
        List<String> safeQueue = queueSourceUrls != null ? queueSourceUrls : List.of();
        int matchedIndex = currentIndex >= 0 && currentIndex < safeQueue.size()
                && Objects.equals(currentSourceUrl, safeQueue.get(currentIndex))
                        ? currentIndex : indexOfSourceUrl(safeQueue, currentSourceUrl);
        if (matchedIndex >= 0) {
            return new Reconciliation(matchedIndex == currentIndex ? ReconcileAction.KEEP : ReconcileAction.REMAP,
                    matchedIndex);
        }
        int selectedIndex = safeQueue.isEmpty() ? 0 : clamp(currentIndex, 0, safeQueue.size() - 1);
        return new Reconciliation(ReconcileAction.STOP, selectedIndex);
    }

    static Reconciliation reconcile(int currentIndex, String currentSourceUrl, List<String> queueSourceUrls,
            int removedIndex) {
        if (removedIndex < 0) {
            return reconcile(currentIndex, currentSourceUrl, queueSourceUrls);
        }
        List<String> queue = queueSourceUrls != null ? queueSourceUrls : List.of();
        int nextIndex = currentIndex - (removedIndex < currentIndex ? 1 : 0);
        if (removedIndex != currentIndex && nextIndex >= 0 && nextIndex < queue.size()
                && Objects.equals(currentSourceUrl, queue.get(nextIndex))) {
            return new Reconciliation(nextIndex == currentIndex ? ReconcileAction.KEEP : ReconcileAction.REMAP,
                    nextIndex);
        }
        return new Reconciliation(ReconcileAction.STOP,
                queue.isEmpty() ? 0 : clamp(nextIndex, 0, queue.size() - 1));
    }

    /** Random mode draws from every other occurrence, allowing repeats across successive draws. */
    static Completion completion(int currentIndex, int queueSize, int repeatMode, boolean shuffle, int draw) {
        if (shuffle && repeatMode != 1 && queueSize > 0) {
            return Completion.advance(randomOther(currentIndex, queueSize, draw));
        }
        return completion(currentIndex, queueSize, repeatMode);
    }

    static int manualMove(int currentIndex, int queueSize, int delta, boolean shuffle, int draw) {
        if (queueSize <= 0) {
            return 0;
        }
        return shuffle ? randomOther(currentIndex, queueSize, draw)
                : clamp(currentIndex + delta, 0, queueSize - 1);
    }

    private static int randomOther(int currentIndex, int queueSize, int draw) {
        if (queueSize <= 1) {
            return 0;
        }
        int current = clamp(currentIndex, 0, queueSize - 1);
        int next = Math.floorMod(draw, queueSize - 1);
        return next >= current ? next + 1 : next;
    }

    static Completion completion(int currentIndex, int queueSize, int repeatMode) {
        if (queueSize <= 0) {
            return Completion.stop();
        }
        if (repeatMode == 1) {
            return Completion.advance(clamp(currentIndex, 0, queueSize - 1));
        }
        if (currentIndex < queueSize - 1) {
            return Completion.advance(currentIndex + 1);
        }
        if (repeatMode == 2) {
            return Completion.advance(0);
        }
        return Completion.stop();
    }

    private static int indexOfSourceUrl(List<String> queueSourceUrls, String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return -1;
        }
        for (int index = 0; index < queueSourceUrls.size(); index++) {
            if (Objects.equals(sourceUrl, queueSourceUrls.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    enum ReconcileAction {
        KEEP,
        REMAP,
        STOP
    }

    record Reconciliation(ReconcileAction action, int selectedIndex) {
        Reconciliation {
            Objects.requireNonNull(action, "action");
            selectedIndex = Math.max(0, selectedIndex);
        }
    }

    record Completion(boolean shouldAdvance, int nextIndex) {
        private static Completion advance(int nextIndex) {
            return new Completion(true, nextIndex);
        }

        private static Completion stop() {
            return new Completion(false, -1);
        }
    }
}
