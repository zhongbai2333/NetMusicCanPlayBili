package com.zhongbai233.net_music_can_play_bili.server;

import java.util.LinkedHashMap;

/** Latest-request ownership for deferred actions. A completed old request cannot clear a newer one. */
public final class DeferredRequestGate<K> {
    private final LinkedHashMap<K, Long> requests = new LinkedHashMap<>();
    private final int capacity;
    private long generation;
    public DeferredRequestGate(int capacity) { this.capacity = capacity; }
    public synchronized long begin(K key) {
        long ticket = ++generation;
        requests.remove(key);
        if (requests.size() >= capacity) requests.remove(requests.keySet().iterator().next());
        requests.put(key, ticket);
        return ticket;
    }
    public synchronized boolean current(K key, long ticket) { return Long.valueOf(ticket).equals(requests.get(key)); }
    public synchronized void complete(K key, long ticket) { requests.remove(key, ticket); }
    public synchronized void clear() { requests.clear(); }
}
