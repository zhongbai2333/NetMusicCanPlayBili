package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackApproachPredictor;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Source-keyed spatial endpoint index; it contains no block-entity references. */
public final class AudioEndpointIndex {
    private final Map<PlaybackSourceId, Map<UUID, IndexedAudioEndpoint>> bySource = new ConcurrentHashMap<>();
    private final Map<UUID, IndexedAudioEndpoint> byEndpoint = new ConcurrentHashMap<>();

    public void upsert(IndexedAudioEndpoint endpoint) {
        if (endpoint == null) {
            return;
        }
        IndexedAudioEndpoint previous = byEndpoint.compute(endpoint.endpointId(), (ignored, current) ->
                current == null || endpoint.revision() >= current.revision() ? endpoint : current);
        if (previous != endpoint) {
            return;
        }
        bySource.values().forEach(endpoints -> endpoints.remove(endpoint.endpointId()));
        bySource.computeIfAbsent(endpoint.sourceId(), ignored -> new ConcurrentHashMap<>())
                .put(endpoint.endpointId(), endpoint);
        bySource.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public void replaceSource(PlaybackSourceId sourceId, Collection<IndexedAudioEndpoint> endpoints) {
        removeSource(sourceId);
        if (endpoints != null) {
            endpoints.stream().filter(endpoint -> sourceId != null && sourceId.equals(endpoint.sourceId()))
                    .forEach(this::upsert);
        }
    }

    public void remove(UUID endpointId) {
        IndexedAudioEndpoint removed = endpointId != null ? byEndpoint.remove(endpointId) : null;
        if (removed == null) {
            return;
        }
        Map<UUID, IndexedAudioEndpoint> endpoints = bySource.get(removed.sourceId());
        if (endpoints != null) {
            endpoints.remove(endpointId);
            if (endpoints.isEmpty()) {
                bySource.remove(removed.sourceId(), endpoints);
            }
        }
    }

    public void removeSource(PlaybackSourceId sourceId) {
        Map<UUID, IndexedAudioEndpoint> removed = sourceId != null ? bySource.remove(sourceId) : null;
        if (removed != null) {
            removed.forEach(byEndpoint::remove);
        }
    }

    public List<IndexedAudioEndpoint> endpointsFor(PlaybackSourceId sourceId) {
        Map<UUID, IndexedAudioEndpoint> endpoints = sourceId != null ? bySource.get(sourceId) : null;
        return endpoints != null ? List.copyOf(endpoints.values()) : List.of();
    }

    public Set<UUID> audibleDemands(PlaybackSourceId sourceId, String dimension,
            double listenerX, double listenerY, double listenerZ) {
        if (sourceId == null || dimension == null) {
            return Set.of();
        }
        Map<UUID, IndexedAudioEndpoint> endpoints = bySource.get(sourceId);
        if (endpoints == null) {
            return Set.of();
        }
        Set<UUID> result = ConcurrentHashMap.newKeySet();
        for (IndexedAudioEndpoint endpoint : endpoints.values()) {
            if (dimension.equals(endpoint.dimension())
                    && endpoint.hasAudibleDemand(listenerX, listenerY, listenerZ)) {
                result.add(endpoint.endpointId());
            }
        }
        return Set.copyOf(result);
    }

    public Set<UUID> anticipatedDemands(PlaybackSourceId sourceId, String dimension,
            double listenerX, double listenerY, double listenerZ,
            double velocityX, double velocityY, double velocityZ) {
        if (sourceId == null || dimension == null) {
            return Set.of();
        }
        Map<UUID, IndexedAudioEndpoint> endpoints = bySource.get(sourceId);
        if (endpoints == null) {
            return Set.of();
        }
        Set<UUID> result = ConcurrentHashMap.newKeySet();
        for (IndexedAudioEndpoint endpoint : endpoints.values()) {
            AudioPlaybackRange.Profile profile = AudioPlaybackRange.profile(endpoint.configuredDistance(),
                    endpoint.rangeScale(), endpoint.outputGain());
            if (dimension.equals(endpoint.dimension()) && profile.outputGain() > 0.0F
                    && PlaybackApproachPredictor.willEnterSphere(listenerX, listenerY, listenerZ,
                            velocityX, velocityY, velocityZ, endpoint.x(), endpoint.y(), endpoint.z(),
                            profile.fadeEndDistance())) {
                result.add(endpoint.endpointId());
            }
        }
        return Set.copyOf(result);
    }

    public List<IndexedAudioEndpoint> snapshot() {
        return new ArrayList<>(byEndpoint.values());
    }

    public void clear() {
        bySource.clear();
        byEndpoint.clear();
    }
}
