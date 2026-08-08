package com.zhongbai233.net_music_can_play_bili.client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** source到虚拟媒体consumer的一对多无重复关系。 */
public final class MediaConsumerRegistry<K> {
    private final Map<K, Set<K>> consumers = new ConcurrentHashMap<>();

    public void register(K source, K consumer) {
        if (source != null && consumer != null) {
            unregister(consumer);
            consumers.computeIfAbsent(source, ignored -> ConcurrentHashMap.newKeySet()).add(consumer);
        }
    }

    public void unregister(K consumer) {
        if (consumer == null) return;
        consumers.entrySet().removeIf(entry -> {
            entry.getValue().remove(consumer);
            return entry.getValue().isEmpty();
        });
    }

    public List<K> consumersFor(K source) {
        Set<K> values = consumers.get(source);
        return values == null ? List.of() : List.copyOf(values);
    }

    public void clear() {
        consumers.clear();
    }
}