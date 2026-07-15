package com.zhongbai233.client_resource_diagnostics;

import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(ClientResourceDiagnostics.MOD_ID)
public final class ClientResourceDiagnostics {
    public static final String MOD_ID = "client_resource_diagnostics";

    public ClientResourceDiagnostics() {
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            DiagnosticsController.INSTANCE.start();
        }
    }
}