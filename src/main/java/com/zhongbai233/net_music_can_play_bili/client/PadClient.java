package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.gui.PadFocusScreen;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public final class PadClient {
    private PadClient() {
    }

    public static void openFocusScreen(InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || hand == null || hand == InteractionHand.OFF_HAND) {
            return;
        }
        ItemStack stack = minecraft.player.getItemInHand(hand);
        if (!(stack.getItem() instanceof PadItem)) {
            return;
        }
        minecraft.setScreen(new PadFocusScreen(hand));
    }
}