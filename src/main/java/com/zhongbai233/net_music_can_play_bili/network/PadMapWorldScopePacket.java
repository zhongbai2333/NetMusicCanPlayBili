package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 服务端下发 Pad 地图缓存作用域；scopeId 是缓存隔离主键，worldName 仅用于调试显示。 */
public record PadMapWorldScopePacket(String worldScopeId, String worldName) implements CustomPacketPayload {
    public static final Type<PadMapWorldScopePacket> TYPE = new Type<>(NetworkPayloadIds.id("pad_map_world_scope"));
    private static final int MAX_SCOPE_LENGTH = 256;

    public static final StreamCodec<RegistryFriendlyByteBuf, PadMapWorldScopePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PadMapWorldScopePacket decode(RegistryFriendlyByteBuf buffer) {
            return new PadMapWorldScopePacket(buffer.readUtf(MAX_SCOPE_LENGTH), buffer.readUtf(MAX_SCOPE_LENGTH));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, PadMapWorldScopePacket packet) {
            buffer.writeUtf(packet.worldScopeId(), MAX_SCOPE_LENGTH);
            buffer.writeUtf(packet.worldName(), MAX_SCOPE_LENGTH);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PadMapWorldScopePacket payload, IPayloadContext context) {
        context.enqueueWork(() -> PadMapClientCache.setServerWorldScope(payload.worldScopeId(), payload.worldName()));
    }
}
