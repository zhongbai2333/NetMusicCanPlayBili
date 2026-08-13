package com.zhongbai233.net_music_can_play_bili.blockentity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Computes exact audience departures without depending on the server runtime. */
public final class ModernTurntableAudiencePolicy {
    private ModernTurntableAudiencePolicy() {
    }

    public static Set<UUID> departed(Set<UUID> previouslySynced, Set<UUID> currentlyNearby) {
        if (previouslySynced == null || previouslySynced.isEmpty()) {
            return Set.of();
        }
        Set<UUID> departed = new HashSet<>(previouslySynced);
        if (currentlyNearby != null && !currentlyNearby.isEmpty()) {
            departed.removeAll(currentlyNearby);
        }
        return Set.copyOf(departed);
    }
}
