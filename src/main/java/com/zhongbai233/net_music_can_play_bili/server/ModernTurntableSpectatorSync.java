package com.zhongbai233.net_music_can_play_bili.server;

import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 为不会激活方块实体 ticker 的旁观玩家补发正在播放的唱片机状态。 */
@EventBusSubscriber
public final class ModernTurntableSpectatorSync {
    private static final int SYNC_INTERVAL_TICKS = 20;

    private ModernTurntableSpectatorSync() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server == null || server.getTickCount() % SYNC_INTERVAL_TICKS != 0) {
            return;
        }
        for (var level : server.getAllLevels()) {
            ModernTurntableBlockEntity.syncLoadedTurntablesToSpectators(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ModernTurntableBlockEntity.clearLoadedServerTurntables();
    }
}