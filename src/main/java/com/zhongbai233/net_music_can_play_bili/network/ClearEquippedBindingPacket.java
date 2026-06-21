package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.server.MediaBindingCleanupService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ClearEquippedBindingPacket() implements CustomPacketPayload {
    public static final Type<ClearEquippedBindingPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("clear_equipped_binding"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClearEquippedBindingPacket> STREAM_CODEC = StreamCodec
            .unit(new ClearEquippedBindingPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClearEquippedBindingPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        boolean[] equipped = { false, false };
        EquippedMediaItems.forEachEquipped(player, stack -> {
            equipped[0] |= HolographicGlassesAbility.has(stack);
            equipped[1] |= HeadphoneAbility.has(stack);
        });
        boolean holographic = equipped[0];
        boolean headphones = equipped[1];
        if (!holographic && !headphones) {
            player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.equipment_bindings.need_equipped"));
            return;
        }

        var result = MediaBindingCleanupService.clearEquippedHeadBindings(player);
        int glassesCount = result.holographicCount();
        int headphoneCount = result.headphoneCount();
        if (holographic && headphones) {
            player.sendSystemMessage(glassesCount > 0 || headphoneCount > 0
                    ? Component.translatable("message.net_music_can_play_bili.equipment_bindings.cleared_both",
                            glassesCount, headphoneCount)
                    : Component.translatable("message.net_music_can_play_bili.equipment_bindings.none_both"));
        } else if (holographic) {
            player.sendSystemMessage(glassesCount > 0
                    ? Component.translatable("message.net_music_can_play_bili.equipment_bindings.cleared_glasses",
                            glassesCount)
                    : Component.translatable("message.net_music_can_play_bili.equipment_bindings.none_glasses"));
        } else {
            player.sendSystemMessage(headphoneCount > 0
                    ? Component.translatable("message.net_music_can_play_bili.equipment_bindings.cleared_headphones",
                            headphoneCount)
                    : Component.translatable("message.net_music_can_play_bili.equipment_bindings.none_headphones"));
        }
    }
}
