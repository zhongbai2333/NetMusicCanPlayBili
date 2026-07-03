package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

record PadGuiViewState(UUID deviceId, PadDocument document, PadMapSnapshot map, float playerX, float playerZ,
        float playerYaw, int ticks, long focusRevision) {
    static PadGuiViewState capture(UUID deviceId) {
        return capture(deviceId, 1.0F);
    }

    static PadGuiViewState capture(UUID deviceId, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack stack = itemStack(minecraft, deviceId);
        PadDocument document = stack.getItem() instanceof PadItem ? PadItem.readDocument(stack) : PadDocument.DEFAULT;
        float clampedPartial = Math.max(0.0F, Math.min(1.0F, partialTick));
        float playerX = minecraft.player != null
                ? (float) (minecraft.player.xo + (minecraft.player.getX() - minecraft.player.xo) * clampedPartial)
                : 0.0F;
        float playerZ = minecraft.player != null
                ? (float) (minecraft.player.zo + (minecraft.player.getZ() - minecraft.player.zo) * clampedPartial)
                : 0.0F;
        float playerYaw = minecraft.player != null ? minecraft.player.getYRot() : 0.0F;
        int ticks = minecraft.player != null ? minecraft.player.tickCount : 0;
        int mapCenterX = minecraft.player != null ? minecraft.player.blockPosition().getX() : 0;
        int mapCenterZ = minecraft.player != null ? minecraft.player.blockPosition().getZ() : 0;
        PadMapSnapshot map = PadMapClientCache.snapshot(mapCenterX, mapCenterZ);
        return new PadGuiViewState(deviceId, document, map, playerX, playerZ, playerYaw, ticks,
                PadFocusState.revision());
    }

    private static ItemStack itemStack(Minecraft minecraft, UUID deviceId) {
        if (minecraft.player == null) {
            return ItemStack.EMPTY;
        }
        ItemStack main = minecraft.player.getMainHandItem();
        if (main.getItem() instanceof PadItem && (deviceId == null || deviceId.equals(PadItem.readDeviceId(main)))) {
            return main;
        }
        ItemStack offhand = minecraft.player.getOffhandItem();
        if (offhand.getItem() instanceof PadItem
                && (deviceId == null || deviceId.equals(PadItem.readDeviceId(offhand)))) {
            return offhand;
        }
        return ItemStack.EMPTY;
    }
}