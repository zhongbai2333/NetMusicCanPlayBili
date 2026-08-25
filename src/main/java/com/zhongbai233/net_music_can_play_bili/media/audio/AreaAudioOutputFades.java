package com.zhongbai233.net_music_can_play_bili.media.audio;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe, Minecraft-independent zone/fade model for a collection of output keys. */
public final class AreaAudioOutputFades<K> {
    private final ConcurrentMap<K, AreaAudioZone> zones = new ConcurrentHashMap<>();
    private final ConcurrentMap<K, AreaAudioBoundaryEnvelope> fades = new ConcurrentHashMap<>();
    private volatile AreaAudioZone listenerZone = AreaAudioZone.unrestricted();

    public void acceptListener(AreaAudioZone zone) {
        listenerZone = normalized(zone);
    }

    public void set(K outputKey, AreaAudioZone zone) {
        zones.put(Objects.requireNonNull(outputKey, "outputKey"), normalized(zone));
    }

    public void remove(K outputKey) {
        if (outputKey != null) {
            zones.remove(outputKey);
            fades.remove(outputKey);
        }
    }

    public float gain(K outputKey, long nowNanos) {
        if (outputKey == null) {
            return 1.0F;
        }
        AreaAudioZone output = zones.get(outputKey);
        if (output == null) {
            return 1.0F;
        }
        return fades.computeIfAbsent(outputKey, ignored -> new AreaAudioBoundaryEnvelope())
                .gain(output.allows(listenerZone), nowNanos);
    }

    public void clear() {
        listenerZone = AreaAudioZone.unrestricted();
        zones.clear();
        fades.clear();
    }

    private static AreaAudioZone normalized(AreaAudioZone zone) {
        return zone != null ? zone : AreaAudioZone.unrestricted();
    }
}
