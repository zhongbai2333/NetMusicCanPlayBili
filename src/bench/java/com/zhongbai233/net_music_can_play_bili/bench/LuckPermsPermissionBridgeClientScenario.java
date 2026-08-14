package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;

/** Keeps a real remote, non-owner player connected while the server validates permissions. */
final class LuckPermsPermissionBridgeClientScenario implements BenchClientScenario {
    private int ticks;

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        // The paired server waits for a stable remote connection before starting its scenario.
        // Keep this participant alive long enough for grant, command and revoke assertions.
        return ++ticks >= 300 ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }
}
