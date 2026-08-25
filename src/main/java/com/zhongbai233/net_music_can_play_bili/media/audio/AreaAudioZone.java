package com.zhongbai233.net_music_can_play_bili.media.audio;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-resolved acoustic zone. An unrestricted zone preserves vanilla NCPB
 * behaviour when AreaControl is absent or unavailable.
 */
public record AreaAudioZone(boolean isolated, UUID areaId) {
    public static final UUID WILDNESS_ID = new UUID(0L, 0L);
    private static final AreaAudioZone UNRESTRICTED = new AreaAudioZone(false, WILDNESS_ID);

    public AreaAudioZone {
        areaId = Objects.requireNonNull(areaId, "areaId");
    }

    public static AreaAudioZone unrestricted() {
        return UNRESTRICTED;
    }

    public static AreaAudioZone isolated(UUID areaId) {
        return new AreaAudioZone(true, Objects.requireNonNull(areaId, "areaId"));
    }

    public static AreaAudioZone wildness() {
        return isolated(WILDNESS_ID);
    }

    /** Strict identity comparison deliberately treats parent and child areas as separate rooms. */
    public boolean allows(AreaAudioZone listenerZone) {
        if (!isolated) {
            return true;
        }
        return listenerZone != null && listenerZone.isolated && areaId.equals(listenerZone.areaId);
    }
}
