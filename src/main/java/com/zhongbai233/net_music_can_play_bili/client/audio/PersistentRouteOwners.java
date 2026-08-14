package com.zhongbai233.net_music_can_play_bili.client.audio;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Tracks logical route ownership independently from short-lived audio relay instances. */
final class PersistentRouteOwners<K> {
    private final Map<K, K> sourceByOwner = new HashMap<>();
    private final Map<K, Set<K>> ownersBySource = new HashMap<>();

    synchronized Change<K> bind(K owner, K source) {
        if (owner == null || source == null) {
            throw new IllegalArgumentException("owner and source must not be null");
        }
        K previous = sourceByOwner.put(owner, source);
        if (previous != null && !previous.equals(source)) {
            removeOwner(previous, owner);
        }
        ownersBySource.computeIfAbsent(source, ignored -> new HashSet<>()).add(owner);
        return new Change<>(previous, source);
    }

    synchronized K unbind(K owner) {
        if (owner == null) {
            return null;
        }
        K previous = sourceByOwner.remove(owner);
        if (previous != null) {
            removeOwner(previous, owner);
        }
        return previous;
    }

    synchronized boolean hasOwners(K source) {
        Set<K> owners = source != null ? ownersBySource.get(source) : null;
        return owners != null && !owners.isEmpty();
    }

    synchronized void clear() {
        sourceByOwner.clear();
        ownersBySource.clear();
    }

    private void removeOwner(K source, K owner) {
        Set<K> owners = ownersBySource.get(source);
        if (owners == null) {
            return;
        }
        owners.remove(owner);
        if (owners.isEmpty()) {
            ownersBySource.remove(source);
        }
    }

    record Change<K>(K previousSource, K currentSource) {
    }
}
