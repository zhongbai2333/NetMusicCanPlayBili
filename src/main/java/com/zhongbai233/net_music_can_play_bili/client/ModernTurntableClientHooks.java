package com.zhongbai233.net_music_can_play_bili.client;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * 仅客户端入口；供通用代码调用时由物理端检查保护。
 */
public final class ModernTurntableClientHooks {
    private ModernTurntableClientHooks() {
    }

    public static void openModernTurntableScreen(BlockPos pos) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            ClientOnly.openModernTurntableScreen(pos);
        }
    }

    private static final class ClientOnly {
        private ClientOnly() {
        }

        private static void openModernTurntableScreen(BlockPos pos) {
            ModernTurntableClient.openScreen(pos);
        }
    }
}