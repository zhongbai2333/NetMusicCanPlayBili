package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.UUID;

/** 每个服务端存档/后端持久化一个 Pad 地图缓存作用域 UUID。 */
public final class PadMapScopeSavedData extends SavedData {
    private static final String NAME = "pad_map_scope";

    public static final Codec<PadMapScopeSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("worldScopeId").forGetter(data -> data.worldScopeId))
            .apply(instance, PadMapScopeSavedData::new));

    public static final SavedDataType<PadMapScopeSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, NAME),
            PadMapScopeSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final String worldScopeId;

    public PadMapScopeSavedData() {
        this(UUID.randomUUID().toString());
        setDirty();
    }

    private PadMapScopeSavedData(String worldScopeId) {
        this.worldScopeId = normalize(worldScopeId);
    }

    public static PadMapScopeSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public String worldScopeId() {
        return worldScopeId;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return value.trim();
    }
}
