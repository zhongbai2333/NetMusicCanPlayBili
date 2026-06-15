package com.zhongbai233.net_music_can_play_bili.client;

import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * 可由通用代码安全调用的 MP4 客户端入口。
 */
public final class MP4ClientHooks {
    private MP4ClientHooks() {
    }

    public static void openFocusScreen(InteractionHand hand) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            ClientOnly.openFocusScreen(hand);
        }
    }

    public static int selectedQueueIndex(net.minecraft.world.item.ItemStack stack) {
        return FMLEnvironment.getDist() == Dist.CLIENT ? ClientOnly.selectedQueueIndex(stack) : 0;
    }

    private static final class ClientOnly {
        private ClientOnly() {
        }

        private static void openFocusScreen(InteractionHand hand) {
            MP4Client.openFocusScreen(hand);
        }

        private static int selectedQueueIndex(net.minecraft.world.item.ItemStack stack) {
            return MP4Client.cachedStateFor(stack).selectedQueueIndex();
        }
    }
}
