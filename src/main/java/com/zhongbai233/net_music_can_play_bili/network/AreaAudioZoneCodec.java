package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Compact wire codec shared by every payload that carries an acoustic zone. */
public final class AreaAudioZoneCodec {
    private AreaAudioZoneCodec() {
    }

    public static AreaAudioZone decode(RegistryFriendlyByteBuf buffer) {
        return buffer.readBoolean()
                ? AreaAudioZone.isolated(buffer.readUUID())
                : AreaAudioZone.unrestricted();
    }

    public static void encode(RegistryFriendlyByteBuf buffer, AreaAudioZone zone) {
        AreaAudioZone normalized = zone != null ? zone : AreaAudioZone.unrestricted();
        buffer.writeBoolean(normalized.isolated());
        if (normalized.isolated()) {
            buffer.writeUUID(normalized.areaId());
        }
    }
}
