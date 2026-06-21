package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.link.MediaBindingData.MediaSource;
import com.zhongbai233.net_music_can_play_bili.menu.MediaToolBindingMenu;
import com.zhongbai233.net_music_can_play_bili.server.MediaBindingCleanupService;
import com.zhongbai233.net_music_can_play_bili.server.MediaEquipmentBindingService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MediaToolConfirmBindingPacket() implements CustomPacketPayload {
    public static final Type<MediaToolConfirmBindingPacket> TYPE = new Type<>(
            NetworkPayloadIds.id("media_tool_confirm_binding"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MediaToolConfirmBindingPacket> STREAM_CODEC = StreamCodec
            .unit(new MediaToolConfirmBindingPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MediaToolConfirmBindingPacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof MediaToolBindingMenu menu)) {
            return;
        }
        ItemStack equipment = menu.getSlot(MediaToolBindingMenu.INPUT_SLOT).getItem();
        if (equipment.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.media_tool.need_equipment_input").withStyle(ChatFormatting.RED));
            return;
        }
        MediaSource source = menu.targetSource(player);
        if (source == null) {
            var result = MediaBindingCleanupService.clearEquipmentBindings(player, equipment);
            player.sendSystemMessage(Component.translatable(
                    result.total() > 0
                            ? "message.net_music_can_play_bili.media_tool.equipment_unlinked"
                            : "message.net_music_can_play_bili.media_tool.equipment_no_links",
                    result.holographicCount(), result.headphoneCount()).withStyle(ChatFormatting.GOLD));
            if (result.total() > 0) {
                menu.confirmBinding(player);
            }
            menu.broadcastChanges();
            return;
        }
        var result = MediaEquipmentBindingService.bind(player, equipment, source);
        if (result.bound() && menu.confirmBinding(player)) {
            player.getInventory().setChanged();
            menu.refreshTargetBindingStats(player);
        }
    }
}
