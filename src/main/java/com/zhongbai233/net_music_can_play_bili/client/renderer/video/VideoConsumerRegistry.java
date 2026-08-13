package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个视频播放实例的直接消费者：投影仪位置集合和 GUI 预览标记。
 *
 * <p>投影仪集合以不可变快照发布，使渲染线程可以稳定遍历，同时允许其他线程替换或解绑消费者。</p>
 */
final class VideoConsumerRegistry<T> {
    private final AtomicReference<List<T>> projectors = new AtomicReference<>(List.of());
    private volatile boolean guiConsumer;

    void replaceProjectors(Collection<? extends T> replacements) {
        if (replacements == null || replacements.isEmpty()) {
            projectors.set(List.of());
            return;
        }
        LinkedHashSet<T> distinct = new LinkedHashSet<>();
        for (T replacement : replacements) {
            if (replacement != null) {
                distinct.add(replacement);
            }
        }
        projectors.set(List.copyOf(distinct));
    }

    void addProjector(T projector) {
        if (projector == null) {
            return;
        }
        projectors.updateAndGet(current -> {
            if (current.contains(projector)) {
                return current;
            }
            ArrayList<T> updated = new ArrayList<>(current);
            updated.add(projector);
            return List.copyOf(updated);
        });
    }

    void removeProjector(T projector) {
        if (projector == null) {
            return;
        }
        projectors.updateAndGet(current -> {
            if (!current.contains(projector)) {
                return current;
            }
            ArrayList<T> updated = new ArrayList<>(current);
            updated.remove(projector);
            return List.copyOf(updated);
        });
    }

    boolean containsProjector(T projector) {
        return projector != null && projectors.get().contains(projector);
    }

    List<T> projectors() {
        return projectors.get();
    }

    boolean hasProjectors() {
        return !projectors.get().isEmpty();
    }

    int projectorCount() {
        return projectors.get().size();
    }

    void setGuiConsumer(boolean value) {
        guiConsumer = value;
    }

    boolean hasGuiConsumer() {
        return guiConsumer;
    }

    boolean hasDirectConsumer() {
        return guiConsumer || hasProjectors();
    }
}
