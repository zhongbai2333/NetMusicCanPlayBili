package com.zhongbai233.net_music_can_play_bili.client;

import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** 可由通用代码安全调用的 Pad 客户端入口。 */
public final class PadClientHooks {
    private PadClientHooks() {
    }

    public static void openFocusScreen(InteractionHand hand) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            ClientOnly.openFocusScreen(hand);
        }
    }

    private static final class ClientOnly {
        private ClientOnly() {
        }

        private static void openFocusScreen(InteractionHand hand) {
            PadClient.openFocusScreen(hand);
        }
    }
}