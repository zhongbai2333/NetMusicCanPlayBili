package com.zhongbai233.net_music_can_play_bili.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Counts actual server event ticks, not wall-clock time. Retained retries do not renew the budget. */
final class UnknownPlaybackBudget {
    private final long maximumTicks;
    private final Map<UUID, Entry> entries = new HashMap<>();
    UnknownPlaybackBudget(long maximumTicks) { this.maximumTicks = Math.max(1L, maximumTicks); }
    boolean tick(UUID source, String session) {
        Entry old = entries.get(source);
        long ticks = old != null && old.session.equals(session) ? old.ticks + 1L : 1L;
        entries.put(source, new Entry(session, ticks));
        return ticks >= maximumTicks;
    }
    void retain(java.util.Set<UUID> activeSources) { entries.keySet().retainAll(activeSources); }
    void remove(UUID source) { entries.remove(source); }
    void clear() { entries.clear(); }
    private record Entry(String session, long ticks) { }
}
