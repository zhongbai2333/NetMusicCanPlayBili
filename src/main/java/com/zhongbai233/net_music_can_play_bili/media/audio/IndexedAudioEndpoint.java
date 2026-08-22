package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;

import java.util.Objects;
import java.util.UUID;

/** Chunk-independent description of one spatial audio output. */
public record IndexedAudioEndpoint(UUID endpointId, PlaybackSourceId sourceId, String dimension,
        double x, double y, double z, float configuredDistance, float rangeScale,
        float outputGain, Kind kind, long revision) {
    public IndexedAudioEndpoint {
        endpointId = Objects.requireNonNull(endpointId, "endpointId");
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        dimension = Objects.requireNonNull(dimension, "dimension").trim();
        kind = Objects.requireNonNull(kind, "kind");
        if (dimension.isEmpty() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || revision < 0L) {
            throw new IllegalArgumentException("invalid indexed audio endpoint");
        }
        configuredDistance = AudioPlaybackRange.normalizeConfiguredDistance(configuredDistance);
        rangeScale = AudioPlaybackRange.clampVolume(rangeScale);
        outputGain = AudioPlaybackRange.clampVolume(outputGain);
    }

    public AudioPlaybackRange.SphereResult evaluate(double listenerX, double listenerY, double listenerZ,
            boolean previouslyActive) {
        double dx = listenerX - x;
        double dy = listenerY - y;
        double dz = listenerZ - z;
        return AudioPlaybackRange.evaluateSphere((float) Math.sqrt(dx * dx + dy * dy + dz * dz),
                configuredDistance, rangeScale, outputGain, previouslyActive);
    }

    public boolean hasAudibleDemand(double listenerX, double listenerY, double listenerZ) {
        return evaluate(listenerX, listenerY, listenerZ, false).audible();
    }

    public enum Kind {
        SOURCE,
        SPEAKER,
        CONSOLE,
        HEADPHONES,
        HANDHELD
    }
}
