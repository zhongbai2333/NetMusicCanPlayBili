package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.PadClient;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record PadStateMirrorPacket(UUID deviceId, PadDocument document, long updatedGameTime)
        implements CustomPacketPayload {
    public static final Type<PadStateMirrorPacket> TYPE = new Type<>(NetworkPayloadIds.id("pad_state_mirror"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PadStateMirrorPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PadStateMirrorPacket decode(RegistryFriendlyByteBuf buffer) {
            return new PadStateMirrorPacket(buffer.readUUID(), PadStatePacket.readDocument(buffer), buffer.readLong());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, PadStateMirrorPacket packet) {
            buffer.writeUUID(packet.deviceId());
            PadStatePacket.writeDocument(buffer, packet.document());
            buffer.writeLong(packet.updatedGameTime());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PadStateMirrorPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> PadClient.receiveMirroredDocument(payload.deviceId(), payload.document(),
                payload.updatedGameTime()));
    }
}