package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMapSettings;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerMode;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** World-level authoritative Pad documents keyed by stable Pad device id. */
public final class PadDocumentSavedData extends SavedData {
    private static final String NAME = "pad_documents";

    private static final Codec<Map<UUID, PadDocument>> DOCUMENTS_CODEC = Codec
            .unboundedMap(Codec.STRING, DocumentCodec.CODEC)
            .xmap(PadDocumentSavedData::decodeDocuments, PadDocumentSavedData::encodeDocuments);

    public static final Codec<PadDocumentSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DOCUMENTS_CODEC.optionalFieldOf("documents", Map.of()).forGetter(data -> data.documents))
            .apply(instance, PadDocumentSavedData::new));

    public static final SavedDataType<PadDocumentSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, NAME),
            PadDocumentSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, PadDocument> documents;

    public PadDocumentSavedData() {
        this(new HashMap<>());
    }

    private PadDocumentSavedData(Map<UUID, PadDocument> documents) {
        this.documents = new HashMap<>(documents);
    }

    public static PadDocumentSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public Optional<PadDocument> document(UUID deviceId) {
        return deviceId == null ? Optional.empty() : Optional.ofNullable(documents.get(deviceId));
    }

    public void put(UUID deviceId, PadDocument document) {
        if (deviceId == null || document == null) {
            return;
        }
        documents.put(deviceId, document.copyWithLocked(false));
        setDirty();
    }

    private static Map<UUID, PadDocument> decodeDocuments(Map<String, DocumentCodec> raw) {
        Map<UUID, PadDocument> decoded = new HashMap<>();
        raw.forEach((key, value) -> {
            try {
                decoded.put(UUID.fromString(key), value.toDocument().copyWithLocked(false));
            } catch (IllegalArgumentException ignored) {
            }
        });
        return decoded;
    }

    private static Map<String, DocumentCodec> encodeDocuments(Map<UUID, PadDocument> documents) {
        Map<String, DocumentCodec> encoded = new HashMap<>();
        documents.forEach((key, value) -> encoded.put(key.toString(), DocumentCodec.from(value)));
        return encoded;
    }

    private record MapSettingsCodec(String dimension, int centerX, int centerZ, int radiusBlocks, float zoom,
            boolean autoFollowPlayer) {
        private static final Codec<MapSettingsCodec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("dimension", PadMapSettings.DEFAULT.dimension())
                .forGetter(value -> value.dimension()),
            Codec.INT.optionalFieldOf("centerX", 0).forGetter(value -> value.centerX()),
            Codec.INT.optionalFieldOf("centerZ", 0).forGetter(value -> value.centerZ()),
            Codec.INT.optionalFieldOf("radiusBlocks", 64).forGetter(value -> value.radiusBlocks()),
            Codec.FLOAT.optionalFieldOf("zoom", 1.0F).forGetter(value -> value.zoom()),
            Codec.BOOL.optionalFieldOf("autoFollowPlayer", true).forGetter(value -> value.autoFollowPlayer()))
            .apply(instance, (dimension, centerX, centerZ, radiusBlocks, zoom, autoFollowPlayer) -> new MapSettingsCodec(
                stringValue(dimension, PadMapSettings.DEFAULT.dimension()),
                intValue(centerX, 0),
                intValue(centerZ, 0),
                intValue(radiusBlocks, 64),
                floatValue(zoom, 1.0F),
                boolValue(autoFollowPlayer, true))));

        private PadMapSettings toSettings() {
            return new PadMapSettings(dimension, centerX, centerZ, radiusBlocks, zoom, autoFollowPlayer);
        }

        private static MapSettingsCodec from(PadMapSettings settings) {
            PadMapSettings safe = settings == null ? PadMapSettings.DEFAULT : settings;
            return new MapSettingsCodec(safe.dimension(), safe.centerX(), safe.centerZ(), safe.radiusBlocks(),
                    safe.zoom(), safe.autoFollowPlayer());
        }
    }

    private record MediaEntryCodec(int mediaId, ItemStack disc) {
        private static final Codec<MediaEntryCodec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("mediaId", 0).forGetter(value -> value.mediaId()),
                ItemStack.OPTIONAL_CODEC.optionalFieldOf("disc", ItemStack.EMPTY).forGetter(value -> value.disc()))
                .apply(instance, (mediaId, disc) -> new MediaEntryCodec(intValue(mediaId, 0), itemStackValue(disc))));

        private PadMediaEntry toEntry() {
            return new PadMediaEntry(mediaId, PadItem.isNetMusicDisc(disc) ? disc : ItemStack.EMPTY);
        }

        private static MediaEntryCodec from(PadMediaEntry entry) {
            return new MediaEntryCodec(entry.mediaId(), entry.disc());
        }
    }

    private record TriggerPointCodec(String pointId, String name, double x, double y, double z, int radiusBlocks,
            int mediaId, String triggerMode, boolean loop, int volumePerMille, boolean visible) {
        private static final Codec<TriggerPointCodec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("pointId", "").forGetter(value -> value.pointId()),
            Codec.STRING.optionalFieldOf("name", "").forGetter(value -> value.name()),
            Codec.DOUBLE.optionalFieldOf("x", 0.0D).forGetter(value -> value.x()),
            Codec.DOUBLE.optionalFieldOf("y", 0.0D).forGetter(value -> value.y()),
            Codec.DOUBLE.optionalFieldOf("z", 0.0D).forGetter(value -> value.z()),
            Codec.INT.optionalFieldOf("radiusBlocks", 8).forGetter(value -> value.radiusBlocks()),
            Codec.INT.optionalFieldOf("mediaId", 0).forGetter(value -> value.mediaId()),
            Codec.STRING.optionalFieldOf("triggerMode", PadTriggerMode.MANUAL.name())
                .forGetter(value -> value.triggerMode()),
            Codec.BOOL.optionalFieldOf("loop", false).forGetter(value -> value.loop()),
            Codec.INT.optionalFieldOf("volumePerMille", 1000).forGetter(value -> value.volumePerMille()),
            Codec.BOOL.optionalFieldOf("visible", true).forGetter(value -> value.visible()))
            .apply(instance,
                (pointId, name, x, y, z, radiusBlocks, mediaId, triggerMode, loop, volumePerMille, visible) -> new TriggerPointCodec(
                    stringValue(pointId, ""),
                    stringValue(name, ""),
                    doubleValue(x, 0.0D),
                    doubleValue(y, 0.0D),
                    doubleValue(z, 0.0D),
                    intValue(radiusBlocks, 8),
                    intValue(mediaId, 0),
                    stringValue(triggerMode, PadTriggerMode.MANUAL.name()),
                    boolValue(loop),
                    intValue(volumePerMille, 1000),
                    boolValue(visible, true))));

        private PadTriggerPoint toPoint() {
            return new PadTriggerPoint(parseUuid(pointId), name, x, y, z, radiusBlocks, mediaId,
                    PadTriggerMode.byName(triggerMode), loop, volumePerMille, visible);
        }

        private static TriggerPointCodec from(PadTriggerPoint point) {
            return new TriggerPointCodec(point.pointId().toString(), point.name(), point.x(), point.y(), point.z(),
                    point.radiusBlocks(), point.mediaId(), point.triggerMode().name(), point.loop(),
                    point.volumePerMille(), point.visible());
        }
    }

    private record DocumentCodec(String title, String author, long updatedAtMillis, long sequence,
            MapSettingsCodec mapSettings, List<MediaEntryCodec> mediaEntries, List<TriggerPointCodec> triggerPoints) {
        private static final Codec<DocumentCodec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("title", "").forGetter(value -> value.title()),
            Codec.STRING.optionalFieldOf("author", "").forGetter(value -> value.author()),
            Codec.LONG.optionalFieldOf("updatedAtMillis", 0L).forGetter(value -> value.updatedAtMillis()),
            Codec.LONG.optionalFieldOf("sequence", 0L).forGetter(value -> value.sequence()),
                MapSettingsCodec.CODEC.optionalFieldOf("mapSettings", MapSettingsCodec.from(PadMapSettings.DEFAULT))
                .forGetter(value -> value.mapSettings()),
                MediaEntryCodec.CODEC.listOf().optionalFieldOf("mediaEntries", List.of())
                .forGetter(value -> value.mediaEntries()),
                TriggerPointCodec.CODEC.listOf().optionalFieldOf("triggerPoints", List.of())
                .forGetter(value -> value.triggerPoints()))
            .apply(instance, (title, author, updatedAtMillis, sequence, mapSettings, mediaEntries, triggerPoints) -> new DocumentCodec(
                stringValue(title, ""),
                stringValue(author, ""),
                longValue(updatedAtMillis, 0L),
                longValue(sequence, 0L),
                mapSettings == null ? MapSettingsCodec.from(PadMapSettings.DEFAULT) : mapSettings,
                mediaEntries == null ? List.of() : List.copyOf(mediaEntries),
                triggerPoints == null ? List.of() : List.copyOf(triggerPoints))));

        private PadDocument toDocument() {
            return new PadDocument(title, author, false, updatedAtMillis, sequence, mapSettings.toSettings(),
                    mediaEntries.stream().map(value -> value.toEntry()).toList(),
                    triggerPoints.stream().map(value -> value.toPoint()).toList());
        }

        private static DocumentCodec from(PadDocument document) {
            PadDocument safe = document == null ? PadDocument.DEFAULT : document;
            return new DocumentCodec(safe.title(), safe.author(), safe.updatedAtMillis(), safe.sequence(),
                    MapSettingsCodec.from(safe.mapSettings()),
                    safe.mediaEntries().stream().map(MediaEntryCodec::from).toList(),
                    safe.triggerPoints().stream().map(TriggerPointCodec::from).toList());
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return UUID.randomUUID();
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

    private static float floatValue(Float value, float fallback) {
        return value == null ? fallback : value.floatValue();
    }

    private static double doubleValue(Double value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    private static String stringValue(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static ItemStack itemStackValue(ItemStack value) {
        return value == null ? ItemStack.EMPTY : value;
    }
}