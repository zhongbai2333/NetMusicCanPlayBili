package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.gui.ModernTurntableScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class ModernTurntableClient {
    private ModernTurntableClient() {
    }

    public static void openScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new ModernTurntableScreen(pos));
    }
}
