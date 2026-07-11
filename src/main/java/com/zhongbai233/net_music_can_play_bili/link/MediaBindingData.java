package com.zhongbai233.net_music_can_play_bili.link;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MediaBindingData {
    public static final String TAG_SOURCE_KIND = "source_kind";
    public static final String TAG_MP4_DEVICE_ID = "mp4_device_id";
    public static final String TAG_MEDIA_DEVICE_ID = "media_device_id";
    public static final String TAG_DIMENSION = "dimension";
    public static final String TAG_POS_X = "x";
    public static final String TAG_POS_Y = "y";
    public static final String TAG_POS_Z = "z";

    private MediaBindingData() {
    }

    public static MediaSource mp4(UUID deviceId) {
        return MediaSource.mp4(deviceId);
    }

    public static MediaSource pad(UUID deviceId) {
        return MediaSource.pad(deviceId);
    }

    public static MediaSource projector(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        return MediaSource.projector(level.dimension(), pos);
    }

    public static MediaSource turntable(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        return MediaSource.turntable(level.dimension(), pos);
    }

    public static void writeSource(CompoundTag tag, MediaSource source) {
        if (tag == null || source == null) {
            return;
        }
        tag.putString(TAG_SOURCE_KIND, source.kind().serializedName());
        if (source.isMediaDevice() && source.deviceId() != null) {
            tag.putString(TAG_MP4_DEVICE_ID, source.deviceId().toString());
            tag.putString(TAG_MEDIA_DEVICE_ID, source.deviceId().toString());
        } else if (source.isBlockSource() && source.dimension() != null && source.pos() != null) {
            tag.putString(TAG_DIMENSION, source.dimension().toString());
            tag.putInt(TAG_POS_X, source.pos().getX());
            tag.putInt(TAG_POS_Y, source.pos().getY());
            tag.putInt(TAG_POS_Z, source.pos().getZ());
        }
    }

    public static MediaSource readSource(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        SourceKind kind = SourceKind.byName(tag.getString(TAG_SOURCE_KIND).orElse(""));
        if (kind == SourceKind.MP4 || kind == SourceKind.PAD) {
            UUID deviceId = parseUuid(tag.getString(TAG_MEDIA_DEVICE_ID).orElse(""));
            if (deviceId == null) {
                deviceId = parseUuid(tag.getString(TAG_MP4_DEVICE_ID).orElse(""));
            }
            if (deviceId == null) {
                return null;
            }
            return kind == SourceKind.PAD ? MediaSource.pad(deviceId) : MediaSource.mp4(deviceId);
        }
        if (kind == SourceKind.VIDEO_PROJECTOR || kind == SourceKind.TURNTABLE) {
            ResourceKey<Level> dimension = parseDimension(tag.getString(TAG_DIMENSION).orElse(""));
            if (dimension == null) {
                return null;
            }
            BlockPos pos = new BlockPos(
                    tag.getInt(TAG_POS_X).orElse(0),
                    tag.getInt(TAG_POS_Y).orElse(0),
                    tag.getInt(TAG_POS_Z).orElse(0));
            return kind == SourceKind.VIDEO_PROJECTOR
                    ? MediaSource.projector(dimension, pos)
                    : MediaSource.turntable(dimension, pos);
        }
        return null;
    }

    public static int indexOf(List<MediaSource> sources, MediaSource source) {
        if (sources == null || source == null) {
            return -1;
        }
        for (int i = 0; i < sources.size(); i++) {
            if (source.equals(sources.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public static boolean contains(List<MediaSource> sources, MediaSource source) {
        return indexOf(sources, source) >= 0;
    }

    public static List<UUID> mp4DeviceIds(List<MediaSource> sources) {
        return mediaDeviceIds(sources);
    }

    public static List<UUID> mediaDeviceIds(List<MediaSource> sources) {
        List<UUID> result = new ArrayList<>();
        if (sources != null) {
            for (MediaSource source : sources) {
                if (source != null && source.isMediaDevice() && source.deviceId() != null) {
                    result.add(source.deviceId());
                }
            }
        }
        return List.copyOf(result);
    }

    public static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static ResourceKey<Level> parseDimension(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int separator = value.lastIndexOf(" / ");
        if (separator >= 0) {
            value = value.substring(separator + 3, value.endsWith("]") ? value.length() - 1 : value.length());
        }
        Identifier id = Identifier.tryParse(value);
        return id != null ? ResourceKey.create(Registries.DIMENSION, id) : null;
    }

    public enum SourceKind {
        MP4("mp4"),
        PAD("pad"),
        VIDEO_PROJECTOR("video_projector"),
        TURNTABLE("turntable");

        private final String serializedName;

        SourceKind(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        static SourceKind byName(String value) {
            for (SourceKind kind : values()) {
                if (kind.serializedName.equals(value)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public record MediaSource(SourceKind kind, UUID deviceId, ResourceKey<Level> dimension, BlockPos pos) {
        public static MediaSource mp4(UUID deviceId) {
            return deviceId != null ? new MediaSource(SourceKind.MP4, deviceId, null, null) : null;
        }

        public static MediaSource pad(UUID deviceId) {
            return deviceId != null ? new MediaSource(SourceKind.PAD, deviceId, null, null) : null;
        }

        public static MediaSource projector(ResourceKey<Level> dimension, BlockPos pos) {
            return dimension != null && pos != null
                    ? new MediaSource(SourceKind.VIDEO_PROJECTOR, null, dimension, pos.immutable())
                    : null;
        }

        public static MediaSource turntable(ResourceKey<Level> dimension, BlockPos pos) {
            return dimension != null && pos != null
                    ? new MediaSource(SourceKind.TURNTABLE, null, dimension, pos.immutable())
                    : null;
        }

        public boolean isMp4() {
            return kind == SourceKind.MP4;
        }

        public boolean isPad() {
            return kind == SourceKind.PAD;
        }

        public boolean isMediaDevice() {
            return isMp4() || isPad();
        }

        public UUID mp4DeviceId() {
            return isMp4() ? deviceId : null;
        }

        public boolean isProjector() {
            return kind == SourceKind.VIDEO_PROJECTOR;
        }

        public boolean isTurntable() {
            return kind == SourceKind.TURNTABLE;
        }

        public boolean isBlockSource() {
            return isProjector() || isTurntable();
        }

        public String shortName() {
            if (isMp4() && deviceId != null) {
                String value = deviceId.toString();
                return "MP4 " + (value.length() > 8 ? value.substring(0, 8) : value);
            }
            if (isPad() && deviceId != null) {
                String value = deviceId.toString();
                return "Pad " + (value.length() > 8 ? value.substring(0, 8) : value);
            }
            if (isProjector() && pos != null) {
                return "投影仪 " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
            }
            if (isTurntable() && pos != null) {
                return "唱片机 " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
            }
            return "媒体源";
        }
    }
}
