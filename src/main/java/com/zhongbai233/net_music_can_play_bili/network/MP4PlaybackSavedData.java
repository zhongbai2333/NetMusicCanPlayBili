package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 世界级权威 MP4 播放进度，以稳定的 MP4 设备 ID 为键。 */
public final class MP4PlaybackSavedData extends SavedData {
    private static final String NAME = "mp4_playback";

    private static final Codec<Map<UUID, Entry>> ENTRIES_CODEC = Codec.unboundedMap(Codec.STRING, Entry.CODEC)
            .xmap(MP4PlaybackSavedData::decodeEntries, MP4PlaybackSavedData::encodeEntries);
    private static final Codec<Map<UUID, MP4DeviceStateStore.DeviceEntry>> DEVICES_CODEC = Codec
            .unboundedMap(Codec.STRING, DeviceEntryCodec.CODEC)
            .xmap(MP4PlaybackSavedData::decodeDevices, MP4PlaybackSavedData::encodeDevices);

    public static final Codec<MP4PlaybackSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ENTRIES_CODEC.optionalFieldOf("entries", Map.of()).forGetter(data -> data.entries),
            DEVICES_CODEC.optionalFieldOf("devices", Map.of()).forGetter(data -> data.devices)
    ).apply(instance, MP4PlaybackSavedData::new));

    public static final SavedDataType<MP4PlaybackSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, NAME),
            MP4PlaybackSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Entry> entries;
    private final Map<UUID, MP4DeviceStateStore.DeviceEntry> devices;

    public MP4PlaybackSavedData() {
        this(new HashMap<>(), new HashMap<>());
    }

    private MP4PlaybackSavedData(Map<UUID, Entry> entries, Map<UUID, MP4DeviceStateStore.DeviceEntry> devices) {
        this.entries = new HashMap<>(entries);
        this.devices = new HashMap<>(devices);
    }

    public static MP4PlaybackSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public Optional<Entry> get(UUID deviceId) {
        return deviceId == null ? Optional.empty() : Optional.ofNullable(entries.get(deviceId));
    }

    public Optional<MP4DeviceStateStore.DeviceEntry> device(UUID deviceId) {
        return deviceId == null ? Optional.empty() : Optional.ofNullable(devices.get(deviceId));
    }

    public long elapsedMillis(UUID deviceId, int queueIndex, long fallbackMillis) {
        Entry entry = entries.get(deviceId);
        return entry != null && entry.queueIndex() == queueIndex ? Math.max(0L, entry.elapsedMillis()) : fallbackMillis;
    }

    public void put(UUID deviceId, Entry entry) {
        if (deviceId == null || entry == null) {
            return;
        }
        entries.put(deviceId, entry.normalized());
        setDirty();
    }

    public void putDevice(UUID deviceId, MP4DeviceStateStore.DeviceEntry entry) {
        if (deviceId == null || entry == null) {
            return;
        }
        devices.put(deviceId, entry.normalized());
        setDirty();
    }

    public void record(UUID deviceId, int queueIndex, long elapsedMillis, int durationSeconds, int volumePerMille,
            String sessionId, boolean playing) {
        put(deviceId, new Entry(queueIndex, elapsedMillis, durationSeconds, volumePerMille,
                sessionId == null ? "" : sessionId, playing));
    }

    public void clear(UUID deviceId) {
        if (deviceId != null && entries.remove(deviceId) != null) {
            setDirty();
        }
        if (deviceId != null && devices.remove(deviceId) != null) {
            setDirty();
        }
    }

    private static Map<UUID, Entry> decodeEntries(Map<String, Entry> raw) {
        Map<UUID, Entry> decoded = new HashMap<>();
        raw.forEach((key, value) -> {
            try {
                decoded.put(UUID.fromString(key), value.normalized());
            } catch (IllegalArgumentException ignored) {
                // 忽略格式错误的外部数据，避免世界加载失败。
            }
        });
        return decoded;
    }

    private static Map<String, Entry> encodeEntries(Map<UUID, Entry> entries) {
        Map<String, Entry> encoded = new HashMap<>();
        entries.forEach((key, value) -> encoded.put(key.toString(), value.normalized()));
        return encoded;
    }

    private static Map<UUID, MP4DeviceStateStore.DeviceEntry> decodeDevices(Map<String, DeviceEntryCodec> raw) {
        Map<UUID, MP4DeviceStateStore.DeviceEntry> decoded = new HashMap<>();
        raw.forEach((key, value) -> {
            try {
                decoded.put(UUID.fromString(key), value.toDeviceEntry());
            } catch (IllegalArgumentException ignored) {
            }
        });
        return decoded;
    }

    private static Map<String, DeviceEntryCodec> encodeDevices(
            Map<UUID, MP4DeviceStateStore.DeviceEntry> devices) {
        Map<String, DeviceEntryCodec> encoded = new HashMap<>();
        devices.forEach((key, value) -> encoded.put(key.toString(), DeviceEntryCodec.from(value)));
        return encoded;
    }

    public record Entry(int queueIndex, long elapsedMillis, int durationSeconds, int volumePerMille,
            String sessionId, boolean playing) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("queueIndex", 0).forGetter(entry -> entry.queueIndex()),
            Codec.LONG.optionalFieldOf("elapsedMillis", 0L).forGetter(entry -> entry.elapsedMillis()),
            Codec.INT.optionalFieldOf("durationSeconds", 0).forGetter(entry -> entry.durationSeconds()),
            Codec.INT.optionalFieldOf("volumePerMille", 700).forGetter(entry -> entry.volumePerMille()),
            Codec.STRING.optionalFieldOf("sessionId", "").forGetter(entry -> entry.sessionId()),
            Codec.BOOL.optionalFieldOf("playing", false).forGetter(entry -> entry.playing())
        ).apply(instance, (queueIndex, elapsedMillis, durationSeconds, volumePerMille, sessionId, playing) ->
            new Entry(intValue(queueIndex, 0), longValue(elapsedMillis, 0L), intValue(durationSeconds, 0),
                intValue(volumePerMille, 700), sessionId, boolValue(playing))));

        Entry normalized() {
            int duration = Math.max(0, durationSeconds);
            long max = duration > 0 ? Math.max(0L, duration * 1000L - 50L) : Long.MAX_VALUE;
            return new Entry(Math.max(0, queueIndex), Math.max(0L, Math.min(max, elapsedMillis)), duration,
                    Math.max(0, Math.min(1000, volumePerMille)), sessionId == null ? "" : sessionId, playing);
        }
    }

    private record DeviceStateCodec(boolean playing, boolean shuffle, boolean videoEnabled, boolean landscape,
            int qualityIndex, int selectedQueueIndex, int queueScrollOffset, int volumePerMille, int repeatMode,
            boolean playlistOpen, boolean lyricsEnabled, int subtitleMode, boolean subtitleAiEnabled,
            int progressPerMille, boolean rotationHintShown) {
        private static final Codec<DeviceStateCodec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("playing", false).forGetter(state -> state.playing()),
                Codec.BOOL.optionalFieldOf("shuffle", false).forGetter(state -> state.shuffle()),
                Codec.BOOL.optionalFieldOf("videoEnabled", true).forGetter(state -> state.videoEnabled()),
                Codec.BOOL.optionalFieldOf("landscape", false).forGetter(state -> state.landscape()),
                Codec.INT.optionalFieldOf("qualityIndex", 5).forGetter(state -> state.qualityIndex()),
                Codec.INT.optionalFieldOf("selectedQueueIndex", 0).forGetter(state -> state.selectedQueueIndex()),
                Codec.INT.optionalFieldOf("queueScrollOffset", 0).forGetter(state -> state.queueScrollOffset()),
                Codec.INT.optionalFieldOf("volumePerMille", 1000).forGetter(state -> state.volumePerMille()),
                Codec.INT.optionalFieldOf("repeatMode", 0).forGetter(state -> state.repeatMode()),
                Codec.BOOL.optionalFieldOf("playlistOpen", false).forGetter(state -> state.playlistOpen()),
                Codec.BOOL.optionalFieldOf("lyricsEnabled", false).forGetter(state -> state.lyricsEnabled()),
                Codec.INT.optionalFieldOf("subtitleMode", 0).forGetter(state -> state.subtitleMode()),
                Codec.BOOL.optionalFieldOf("subtitleAiEnabled", false).forGetter(state -> state.subtitleAiEnabled()),
                Codec.INT.optionalFieldOf("progressPerMille", 0).forGetter(state -> state.progressPerMille()),
                Codec.BOOL.optionalFieldOf("rotationHintShown", false).forGetter(state -> state.rotationHintShown())
            ).apply(instance, (playing, shuffle, videoEnabled, landscape, qualityIndex, selectedQueueIndex,
                queueScrollOffset, volumePerMille, repeatMode, playlistOpen, lyricsEnabled, subtitleMode,
                subtitleAiEnabled, progressPerMille, rotationHintShown) -> new DeviceStateCodec(boolValue(playing),
                    boolValue(shuffle), boolValue(videoEnabled, true), boolValue(landscape), intValue(qualityIndex, 5),
                    intValue(selectedQueueIndex, 0), intValue(queueScrollOffset, 0),
                    intValue(volumePerMille, 1000), intValue(repeatMode, 0), boolValue(playlistOpen),
                    boolValue(lyricsEnabled), intValue(subtitleMode, 0), boolValue(subtitleAiEnabled),
                    intValue(progressPerMille, 0), boolValue(rotationHintShown))));

        static DeviceStateCodec from(MP4Item.State state) {
            MP4Item.State s = state == null ? MP4Item.State.DEFAULT : state;
            return new DeviceStateCodec(s.playing(), s.shuffle(), s.videoEnabled(), s.landscape(), s.qualityIndex(),
                    s.selectedQueueIndex(), s.queueScrollOffset(), s.volumePerMille(), s.repeatMode(),
                    s.playlistOpen(), s.lyricsEnabled(), s.subtitleMode(), s.subtitleAiEnabled(),
                    s.progressPerMille(), s.rotationHintShown());
        }

        MP4Item.State toState() {
            return new MP4Item.State(playing, shuffle, videoEnabled, landscape, qualityIndex, selectedQueueIndex,
                    queueScrollOffset, volumePerMille, repeatMode, playlistOpen, lyricsEnabled, subtitleMode,
                    subtitleAiEnabled, progressPerMille, rotationHintShown);
        }
    }

        private record DeviceEntryCodec(DeviceStateCodec state, long elapsedMillis, int durationSeconds, String sessionId,
            long updatedGameTime) {
        private static final Codec<DeviceEntryCodec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                DeviceStateCodec.CODEC.optionalFieldOf("state", DeviceStateCodec.from(MP4Item.State.DEFAULT))
                .forGetter(entry -> entry.state()),
            Codec.LONG.optionalFieldOf("elapsedMillis", 0L).forGetter(entry -> entry.elapsedMillis()),
            Codec.INT.optionalFieldOf("durationSeconds", 0).forGetter(entry -> entry.durationSeconds()),
            Codec.STRING.optionalFieldOf("sessionId", "").forGetter(entry -> entry.sessionId()),
            Codec.LONG.optionalFieldOf("updatedGameTime", 0L).forGetter(entry -> entry.updatedGameTime())
        ).apply(instance, (state, elapsedMillis, durationSeconds, sessionId, updatedGameTime) ->
            new DeviceEntryCodec(state, longValue(elapsedMillis, 0L), intValue(durationSeconds, 0), sessionId,
                longValue(updatedGameTime, 0L))));

        private MP4DeviceStateStore.DeviceEntry toDeviceEntry() {
            return new MP4DeviceStateStore.DeviceEntry(state.toState(), java.util.List.of(), elapsedMillis,
                durationSeconds, sessionId, updatedGameTime).normalized();
        }

        private static DeviceEntryCodec from(MP4DeviceStateStore.DeviceEntry entry) {
            MP4DeviceStateStore.DeviceEntry safe = entry == null
                    ? MP4DeviceStateStore.DeviceEntry.EMPTY
                    : entry.normalized();
            return new DeviceEntryCodec(DeviceStateCodec.from(safe.state()), safe.elapsedMillis(), safe.durationSeconds(),
                    safe.sessionId(), safe.updatedGameTime());
        }
    }

    private static boolean boolValue(Boolean value) {
        return boolValue(value, false);
    }

    private static boolean boolValue(Boolean value, boolean fallback) {
        return value == null ? fallback : value.booleanValue();
    }

    private static int intValue(Integer value, int fallback) {
        return value == null ? fallback : value.intValue();
    }

    private static long longValue(Long value, long fallback) {
        return value == null ? fallback : value.longValue();
    }
}