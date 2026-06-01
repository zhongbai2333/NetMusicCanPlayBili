package com.zhongbai233.net_music_can_play_bili.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class SpeakerClient {
    private SpeakerClient() {
    }

    public static void openScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new com.zhongbai233.net_music_can_play_bili.gui.SpeakerScreen(pos));
    }
}
