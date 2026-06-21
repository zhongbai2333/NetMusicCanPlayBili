package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/** 客户端本地的全息眼镜隐私保护状态。 */
public final class HolographicGlassesClient {
    private HolographicGlassesClient() {
    }

    public static boolean active() {
        Player player = Minecraft.getInstance().player;
        return player != null && HolographicGlassesAbility.has(EquippedMediaItems.firstHolographicGlasses(player));
    }

    public static boolean shouldHideProjectorVideos() {
        return active();
    }

    public static UUID boundMp4DeviceId() {
        List<HolographicGlassesItem.ScreenBinding> bindings = screenBindings();
        return bindings.isEmpty() ? null : bindings.getFirst().deviceId();
    }

    public static List<HolographicGlassesItem.ScreenBinding> screenBindings() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return List.of();
        }
        ItemStack head = EquippedMediaItems.firstHolographicGlasses(player);
        if (!HolographicGlassesAbility.has(head)) {
            return List.of();
        }
        return HolographicGlassesItem.readScreenBindings(head);
    }

    public static boolean handlesTurntable(BlockPos turntablePos) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceKey<Level> dimension = minecraft.level != null ? minecraft.level.dimension() : null;
        return handlesTurntable(dimension, turntablePos);
    }

    public static boolean handlesTurntable(ResourceKey<Level> dimension, BlockPos turntablePos) {
        if (dimension == null || turntablePos == null) {
            return false;
        }
        for (HolographicGlassesItem.ScreenBinding binding : screenBindings()) {
            if (binding.source() != null && binding.source().isTurntable()
                    && dimension.equals(binding.source().dimension())
                    && turntablePos.equals(binding.source().pos())) {
                return true;
            }
        }
        return false;
    }

    public static HolographicGlassesItem.ScreenConfig screenConfig() {
        List<HolographicGlassesItem.ScreenBinding> bindings = screenBindings();
        return bindings.isEmpty() ? HolographicGlassesItem.defaultScreenConfig() : bindings.getFirst().config();
    }
}
