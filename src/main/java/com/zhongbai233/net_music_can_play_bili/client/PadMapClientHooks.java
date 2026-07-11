package com.zhongbai233.net_music_can_play_bili.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** Common-code-safe bridge to Pad map client state. */
public final class PadMapClientHooks {
    private PadMapClientHooks() {
    }

    public static void setServerWorldScope(String worldScopeId, String worldName) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            ClientOnly.setServerWorldScope(worldScopeId, worldName);
        }
    }

    private static final class ClientOnly {
        private ClientOnly() {
        }

        private static void setServerWorldScope(String worldScopeId, String worldName) {
            com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache
                    .setServerWorldScope(worldScopeId, worldName);
        }
    }
}