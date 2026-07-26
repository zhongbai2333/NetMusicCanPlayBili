package com.zhongbai233.net_music_can_play_bili.client;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * 仅客户端入口；供通用代码调用时由物理端检查保护。
 */
public final class LiveStreamerClientHooks {
    private LiveStreamerClientHooks() {
    }

    public static void openLiveStreamerScreen(BlockPos pos) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            ClientOnly.openLiveStreamerScreen(pos);
        }
    }

    private static final class ClientOnly {
        private ClientOnly() {
        }

        private static void openLiveStreamerScreen(BlockPos pos) {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.zhongbai233.net_music_can_play_bili.gui.LiveStreamerScreen(pos));
        }
    }
}
