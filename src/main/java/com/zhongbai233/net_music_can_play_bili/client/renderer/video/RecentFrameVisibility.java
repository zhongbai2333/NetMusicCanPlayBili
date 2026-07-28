package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/** 记录对象最近一次实际提交的渲染帧，避免把持久管理状态误当成逐帧可见性。 */
final class RecentFrameVisibility<K> {
    private final Map<K, Long> submittedFrames = new ConcurrentHashMap<>();
    private final AtomicLong frameSequence = new AtomicLong();
    private final long toleratedFrameAge;

    RecentFrameVisibility(long toleratedFrameAge) {
        this.toleratedFrameAge = Math.max(0L, toleratedFrameAge);
    }

    void beginFrame() {
        frameSequence.incrementAndGet();
    }

    void markSubmitted(K key) {
        if (key != null) {
            submittedFrames.put(key, frameSequence.get());
        }
    }

    boolean wasRecentlySubmitted(K key) {
        if (key == null) {
            return false;
        }
        Long submittedFrame = submittedFrames.get(key);
        if (submittedFrame == null) {
            return false;
        }
        long age = frameSequence.get() - submittedFrame;
        return age >= 0L && age <= toleratedFrameAge;
    }

    void remove(K key) {
        if (key != null) {
            submittedFrames.remove(key);
        }
    }

    void removeIf(Predicate<K> predicate) {
        if (predicate != null) {
            submittedFrames.keySet().removeIf(predicate);
        }
    }

    void clear() {
        submittedFrames.clear();
    }
}