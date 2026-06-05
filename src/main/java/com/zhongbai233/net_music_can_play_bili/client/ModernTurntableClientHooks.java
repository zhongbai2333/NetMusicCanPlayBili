package com.zhongbai233.net_music_can_play_bili.client;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Client-only entry points guarded behind a physical-side check for common code
 * callers.
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