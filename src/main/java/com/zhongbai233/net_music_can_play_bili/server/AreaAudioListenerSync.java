package com.zhongbai233.net_music_can_play_bili.server;

import com.zhongbai233.net_music_can_play_bili.compat.areacontrol.AreaControlAudioCompat;
import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import com.zhongbai233.net_music_can_play_bili.network.AreaAudioListenerPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks only listener region changes; output regions travel with their own snapshots. */
public final class AreaAudioListenerSync {
    private static final Map<UUID, AreaAudioZone> LAST_SENT = new ConcurrentHashMap<>();

    private AreaAudioListenerSync() {
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player, true);
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LAST_SENT.remove(player.getUUID());
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            sync(player, false);
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        LAST_SENT.clear();
    }

    private static void sync(ServerPlayer player, boolean force) {
        AreaAudioZone zone = AreaControlAudioCompat.zoneAt((net.minecraft.server.level.ServerLevel) player.level(),
                BlockPos.containing(player.position()));
        AreaAudioZone previous = LAST_SENT.put(player.getUUID(), zone);
        if (force || !zone.equals(previous)) {
            PacketDistributor.sendToPlayer(player, new AreaAudioListenerPacket(zone));
        }
    }
}
