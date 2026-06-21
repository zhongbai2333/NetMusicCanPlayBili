package com.zhongbai233.net_music_can_play_bili.server;

import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.link.MediaBindingData.MediaSource;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class MediaEquipmentBindingService {
    private MediaEquipmentBindingService() {
    }

    public static BindResult bind(ServerPlayer player, ItemStack equipment, MediaSource source) {
        if (player == null || equipment.isEmpty() || source == null) {
            return BindResult.unhandled();
        }
        return switch (source.kind()) {
            case MP4 -> bindMp4(player, equipment, source);
            case TURNTABLE -> bindTurntable(player, equipment, source);
            case VIDEO_PROJECTOR -> BindResult.unhandled();
        };
    }

    private static BindResult bindMp4(ServerPlayer player, ItemStack equipment, MediaSource source) {
        boolean bound = false;
        boolean handled = false;
        if (HeadphoneAbility.has(equipment)) {
            handled = true;
            AudioLinkData.writeHeadphoneMp4(equipment, source.mp4DeviceId());
            AudioLinkIndex.updatePlayerHeadphones(player);
            player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.headphones.mp4_linked").withStyle(ChatFormatting.GOLD));
            bound = true;
        }
        if (HolographicGlassesAbility.has(equipment)) {
            handled = true;
            boolean linked = HolographicGlassesItem.addOrUpdateBoundMedia(equipment, source);
            player.sendSystemMessage(linked
                    ? Component.translatable("message.net_music_can_play_bili.holographic_glasses.media_linked_count",
                            HolographicGlassesItem.readScreenBindings(equipment).size(),
                            HolographicGlassesItem.MAX_BOUND_MEDIA)
                            .withStyle(ChatFormatting.GOLD)
                    : Component.translatable("message.net_music_can_play_bili.holographic_glasses.media_slots_full")
                            .withStyle(ChatFormatting.RED));
            if (linked) {
                MP4PlaybackSyncManager.stopExternalPlaybackForLinkedHeadphones(player, source.mp4DeviceId());
                bound = true;
            }
        }
        return finish(player, bound, handled);
    }

    private static BindResult bindTurntable(ServerPlayer player, ItemStack equipment, MediaSource source) {
        if (!(player.level() instanceof ServerLevel level) || source.pos() == null) {
            return BindResult.unhandled();
        }
        boolean bound = false;
        boolean handled = false;
        if (HeadphoneAbility.has(equipment)) {
            handled = true;
            if (AudioLinkIndex.hasSpeakerLinkedTo(level, source.pos())) {
                player.sendSystemMessage(Component.translatable(
                        "message.net_music_can_play_bili.headphones.turntable_has_speaker")
                        .withStyle(ChatFormatting.RED));
            } else {
                AudioLinkData.writeHeadphoneTurntable(equipment, source.pos());
                AudioLinkIndex.updatePlayerHeadphones(player);
                player.sendSystemMessage(Component.translatable(
                        "message.net_music_can_play_bili.headphones.turntable_linked",
                        source.pos().getX(), source.pos().getY(), source.pos().getZ()).withStyle(ChatFormatting.GOLD));
                bound = true;
            }
        }
        if (HolographicGlassesAbility.has(equipment)) {
            handled = true;
            bound |= bindHolographic(player, equipment, source,
                    "message.net_music_can_play_bili.holographic_glasses.turntable_linked_count");
        }
        return finish(player, bound, handled);
    }

    private static boolean bindHolographic(ServerPlayer player, ItemStack equipment, MediaSource source,
            String successKey) {
        if (source.pos() == null) {
            return false;
        }
        boolean linked = HolographicGlassesItem.addOrUpdateBoundMedia(equipment, source);
        player.sendSystemMessage(linked
                ? Component.translatable(successKey, source.pos().getX(), source.pos().getY(), source.pos().getZ(),
                        HolographicGlassesItem.readScreenBindings(equipment).size(),
                        HolographicGlassesItem.MAX_BOUND_MEDIA).withStyle(ChatFormatting.GOLD)
                : Component.translatable("message.net_music_can_play_bili.holographic_glasses.media_slots_full")
                        .withStyle(ChatFormatting.RED));
        return linked;
    }

    private static BindResult finish(ServerPlayer player, boolean bound, boolean handled) {
        if (!bound && !handled) {
            player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.media_tool.need_equipment_input").withStyle(ChatFormatting.RED));
        }
        return new BindResult(bound, handled);
    }

    public record BindResult(boolean bound, boolean handledAbility) {
        private static BindResult unhandled() {
            return new BindResult(false, false);
        }
    }
}
