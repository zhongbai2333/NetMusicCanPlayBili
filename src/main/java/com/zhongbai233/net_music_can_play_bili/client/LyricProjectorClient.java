package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.gui.LyricProjectorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class LyricProjectorClient {
    private LyricProjectorClient() {
    }

    public static void openScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new LyricProjectorScreen(pos));
    }
}
