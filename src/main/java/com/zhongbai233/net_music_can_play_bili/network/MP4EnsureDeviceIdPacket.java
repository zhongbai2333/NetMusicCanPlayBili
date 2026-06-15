package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** 在聚焦界面接受操作前，确保手持 MP4 拥有服务端权威设备 ID。 */
public record MP4EnsureDeviceIdPacket(InteractionHand hand) implements CustomPacketPayload {
    public static final Type<MP4EnsureDeviceIdPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, "mp4_ensure_device_id"));

    private static final StreamCodec<RegistryFriendlyByteBuf, InteractionHand> HAND_CODEC = new StreamCodec<>() {
        @Override
        public InteractionHand decode(RegistryFriendlyByteBuf buffer) {
            return buffer.readBoolean() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, InteractionHand value) {
            buffer.writeBoolean(value == InteractionHand.OFF_HAND);
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, MP4EnsureDeviceIdPacket> STREAM_CODEC = StreamCodec
        .composite(HAND_CODEC, packet -> packet.hand(), hand -> new MP4EnsureDeviceIdPacket(hand));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MP4EnsureDeviceIdPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = player.getItemInHand(payload.hand());
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        UUID deviceId = MP4Item.getOrCreateDeviceId(stack);
        ServerLevel level = (ServerLevel) player.level();
        MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        player.getInventory().setChanged();
        PacketDistributor.sendToPlayer(player, new MP4DeviceIdPacket(payload.hand(), deviceId));
        PacketDistributor.sendToPlayer(player, MP4OpenStatePacket.fromStack(level, payload.hand(), deviceId, stack));
    }
}
