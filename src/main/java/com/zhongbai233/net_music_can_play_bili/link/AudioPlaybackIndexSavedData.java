package com.zhongbai233.net_music_can_play_bili.link;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.media.audio.IndexedAudioEndpoint;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persistent source and endpoint index that survives chunk unloads and server restarts. */
public final class AudioPlaybackIndexSavedData extends SavedData {
    private static final String NAME = "audio_playback_index";
    private static final Codec<Map<Long, SourceEntry>> SOURCES_CODEC = Codec
            .unboundedMap(Codec.STRING, SourceEntry.CODEC)
            .xmap(AudioPlaybackIndexSavedData::decodeLongKeys, AudioPlaybackIndexSavedData::encodeLongKeys);
    private static final Codec<Map<UUID, EndpointEntry>> ENDPOINTS_CODEC = Codec
            .unboundedMap(Codec.STRING, EndpointEntry.CODEC)
            .xmap(AudioPlaybackIndexSavedData::decodeUuidKeys, AudioPlaybackIndexSavedData::encodeUuidKeys);

    public static final Codec<AudioPlaybackIndexSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            SOURCES_CODEC.optionalFieldOf("sources", Map.of()).forGetter(data -> data.sources),
            ENDPOINTS_CODEC.optionalFieldOf("endpoints", Map.of()).forGetter(data -> data.endpoints))
            .apply(instance, AudioPlaybackIndexSavedData::new));

    public static final SavedDataType<AudioPlaybackIndexSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, NAME),
            AudioPlaybackIndexSavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<Long, SourceEntry> sources;
    private final Map<UUID, EndpointEntry> endpoints;

    public AudioPlaybackIndexSavedData() {
        this(new HashMap<>(), new HashMap<>());
    }

    private AudioPlaybackIndexSavedData(Map<Long, SourceEntry> sources, Map<UUID, EndpointEntry> endpoints) {
        this.sources = new HashMap<>(sources);
        this.endpoints = new HashMap<>(endpoints);
    }

    public static AudioPlaybackIndexSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized void upsertSource(BlockPos pos, PlaybackSourceId sourceId, SourceKind kind) {
        if (pos == null || sourceId == null || kind == null) {
            return;
        }
        long packed = pos.asLong();
        SourceEntry next = new SourceEntry(sourceId.value().toString(), packed, kind.name());
        SourceEntry previous = sources.put(packed, next);
        boolean changed = !next.equals(previous);
        for (var entry : endpoints.entrySet()) {
            EndpointEntry endpoint = entry.getValue();
            if (endpoint.sourcePos() == packed && !sourceId.value().toString().equals(endpoint.sourceId())) {
                entry.setValue(endpoint.withSourceId(sourceId.value().toString()));
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
    }

    public synchronized Optional<SourceEntry> sourceAt(BlockPos pos) {
        return pos != null ? Optional.ofNullable(sources.get(pos.asLong())) : Optional.empty();
    }

    public synchronized void removeSource(BlockPos pos) {
        if (pos == null) {
            return;
        }
        long packed = pos.asLong();
        SourceEntry removed = sources.remove(packed);
        boolean endpointsRemoved = endpoints.entrySet().removeIf(entry -> entry.getValue().sourcePos() == packed);
        if (removed != null || endpointsRemoved) {
            setDirty();
        }
    }

    public synchronized void upsertEndpoint(EndpointEntry endpoint) {
        if (endpoint == null) {
            return;
        }
        EndpointEntry current = endpoints.get(endpoint.endpointId());
        if (current != null && current.revision() > endpoint.revision()) {
            return;
        }
        String sourceId = sourceAt(BlockPos.of(endpoint.sourcePos()))
                .map(source -> source.sourceId()).orElse(endpoint.sourceId());
        EndpointEntry normalized = endpoint.withSourceId(sourceId);
        if (!normalized.equals(current)) {
            endpoints.put(normalized.endpointId(), normalized);
            setDirty();
        }
    }

    public synchronized void removeEndpoint(UUID endpointId) {
        if (endpointId != null && endpoints.remove(endpointId) != null) {
            setDirty();
        }
    }

    public synchronized void removeEndpointAt(BlockPos endpointPos) {
        if (endpointPos != null && endpoints.entrySet().removeIf(
                entry -> entry.getValue().endpointPos() == endpointPos.asLong())) {
            setDirty();
        }
    }

    public synchronized List<EndpointEntry> endpointsFor(PlaybackSourceId sourceId) {
        if (sourceId == null) {
            return List.of();
        }
        String id = sourceId.value().toString();
        return endpoints.values().stream().filter(endpoint -> id.equals(endpoint.sourceId())).toList();
    }

    public synchronized List<EndpointEntry> endpointSnapshot() {
        return List.copyOf(endpoints.values());
    }

    private static Map<Long, SourceEntry> decodeLongKeys(Map<String, SourceEntry> raw) {
        Map<Long, SourceEntry> result = new HashMap<>();
        raw.forEach((key, value) -> {
            try {
                result.put(Long.parseLong(key), value);
            } catch (NumberFormatException ignored) {
            }
        });
        return result;
    }

    private static Map<String, SourceEntry> encodeLongKeys(Map<Long, SourceEntry> raw) {
        Map<String, SourceEntry> result = new HashMap<>();
        raw.forEach((key, value) -> result.put(Long.toString(key), value));
        return result;
    }

    private static Map<UUID, EndpointEntry> decodeUuidKeys(Map<String, EndpointEntry> raw) {
        Map<UUID, EndpointEntry> result = new HashMap<>();
        raw.forEach((key, value) -> {
            try {
                result.put(UUID.fromString(key), value);
            } catch (IllegalArgumentException ignored) {
            }
        });
        return result;
    }

    private static Map<String, EndpointEntry> encodeUuidKeys(Map<UUID, EndpointEntry> raw) {
        Map<String, EndpointEntry> result = new HashMap<>();
        raw.forEach((key, value) -> result.put(key.toString(), value));
        return result;
    }

    public enum SourceKind {
        TURNTABLE,
        LIVE_STREAMER
    }

    public record SourceEntry(String sourceId, long pos, String kind) {
        public static final Codec<SourceEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("sourceId").forGetter(entry -> entry.sourceId()),
                Codec.LONG.fieldOf("pos").forGetter(entry -> entry.pos()),
                Codec.STRING.fieldOf("kind").forGetter(entry -> entry.kind()))
                .apply(instance, (sourceId, pos, kind) -> new SourceEntry(
                        Objects.requireNonNull(sourceId, "sourceId"),
                        Objects.requireNonNull(pos, "pos").longValue(),
                        Objects.requireNonNull(kind, "kind"))));

        public PlaybackSourceId playbackSourceId() {
            return PlaybackSourceId.parse(sourceId).orElseThrow();
        }
    }

    public record EndpointEntry(UUID endpointId, String sourceId, long sourcePos, long endpointPos,
            int channelIndex, float volume, boolean autoMixJoc, float maxDistance, long revision) {
        public static final Codec<EndpointEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("endpointId").forGetter(entry -> entry.endpointId().toString()),
                Codec.STRING.optionalFieldOf("sourceId", "").forGetter(entry -> entry.sourceId()),
                Codec.LONG.fieldOf("sourcePos").forGetter(entry -> entry.sourcePos()),
                Codec.LONG.fieldOf("endpointPos").forGetter(entry -> entry.endpointPos()),
                Codec.INT.optionalFieldOf("channelIndex", -1).forGetter(entry -> entry.channelIndex()),
                Codec.FLOAT.optionalFieldOf("volume", 1.0F).forGetter(entry -> entry.volume()),
                Codec.BOOL.optionalFieldOf("autoMixJoc", false).forGetter(entry -> entry.autoMixJoc()),
                Codec.FLOAT.optionalFieldOf("maxDistance", 64.0F).forGetter(entry -> entry.maxDistance()),
                Codec.LONG.optionalFieldOf("revision", 0L).forGetter(entry -> entry.revision()))
                .apply(instance, (endpointId, sourceId, sourcePos, endpointPos, channelIndex, volume,
                        autoMixJoc, maxDistance, revision) -> new EndpointEntry(
                                UUID.fromString(endpointId), sourceId, sourcePos, endpointPos,
                                channelIndex, volume, autoMixJoc, maxDistance, revision)));

        public EndpointEntry {
            if (endpointId == null || sourceId == null || revision < 0L) {
                throw new IllegalArgumentException("invalid persistent audio endpoint");
            }
            volume = Math.clamp(volume, 0.0F, 2.0F);
            maxDistance = Math.clamp(maxDistance, 1.0F, 256.0F);
        }

        EndpointEntry withSourceId(String value) {
            return new EndpointEntry(endpointId, value != null ? value : "", sourcePos, endpointPos,
                    channelIndex, volume, autoMixJoc, maxDistance, revision);
        }

        public Optional<IndexedAudioEndpoint> toIndexed(String dimension) {
            PlaybackSourceId parsed = PlaybackSourceId.parse(sourceId).orElse(null);
            if (parsed == null) {
                return Optional.empty();
            }
            BlockPos pos = BlockPos.of(endpointPos);
            return Optional.of(new IndexedAudioEndpoint(endpointId, parsed, dimension,
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                    maxDistance, volume, volume, IndexedAudioEndpoint.Kind.SPEAKER, revision));
        }
    }
}
