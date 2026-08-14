package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.github.tartaricacid.netmusic.network.NetworkHandler;
import com.github.tartaricacid.netmusic.network.message.MusicToClientMessage;
import com.zhongbai233.net_music_can_play_bili.compat.minecartrevolution.MinecartTurntableCompat;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.network.ModernTurntableStopPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Publishes turntable playback and stop messages to nearby listeners and spectators. */
final class ModernTurntableAudienceSync {
    private ModernTurntableAudienceSync() {
    }

    static Set<UUID> syncNearbyPlayers(ServerLevel serverLevel, Level anchorLevel, BlockPos sourcePos,
            Set<UUID> previouslySynced, String playUrl, String rawUrl, String songName, String sessionId,
            long elapsedMillis, long durationMillis, int remainingSeconds, int range) {
        AABB bounds = new AABB(sourcePos).inflate(range);
        Set<UUID> nearby = new HashSet<>();
        String synchronizedUrl = synchronizedUrl(anchorLevel, playUrl, sessionId, elapsedMillis, durationMillis);
        for (ServerPlayer player : serverLevel.getEntitiesOfClass(ServerPlayer.class, bounds)) {
            nearby.add(player.getUUID());
            sendPlayback(player, sourcePos, synchronizedUrl, rawUrl, playUrl, songName, remainingSeconds);
        }
        stopDeparted(serverLevel, sourcePos, sessionId, previouslySynced, nearby);
        return nearby;
    }

    static Set<UUID> syncNearbySpectators(ServerLevel serverLevel, Level anchorLevel, BlockPos sourcePos,
            Set<UUID> previouslySynced, String playUrl, String rawUrl, String songName, String sessionId,
            long elapsedMillis, long durationMillis, int remainingSeconds, int range) {
        AABB bounds = new AABB(sourcePos).inflate(range);
        Set<UUID> nearbySpectators = new HashSet<>();
        Set<UUID> retained = new HashSet<>(previouslySynced);
        String synchronizedUrl = synchronizedUrl(anchorLevel, playUrl, sessionId, elapsedMillis, durationMillis);
        for (ServerPlayer player : serverLevel.players()) {
            if (player.isSpectator() && bounds.contains(player.position())) {
                nearbySpectators.add(player.getUUID());
                if (retained.add(player.getUUID())) {
                    sendPlayback(player, sourcePos, synchronizedUrl, rawUrl, playUrl, songName, remainingSeconds);
                }
            }
        }
        PlaybackSessionId parsedSession = PlaybackSessionId.parse(sessionId).orElse(null);
        ModernTurntableStopPacket stopPacket = parsedSession != null
                ? new ModernTurntableStopPacket(sourcePos, parsedSession.value())
                : null;
        retained.removeIf(playerId -> {
            ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(playerId);
            if (player == null || player.level() != serverLevel) {
                return true;
            }
            boolean departedSpectator = player.isSpectator() && !nearbySpectators.contains(playerId);
            if (departedSpectator && stopPacket != null) {
                PacketDistributor.sendToPlayer(player, stopPacket);
            }
            return departedSpectator;
        });
        return retained;
    }

    private static String synchronizedUrl(Level anchorLevel, String playUrl, String sessionId,
            long elapsedMillis, long durationMillis) {
        String result = PlaybackSync.withSync(playUrl, sessionId, elapsedMillis, durationMillis);
        var hostMinecart = MinecartTurntableCompat.hostMinecart(anchorLevel);
        return hostMinecart != null
                ? PlaybackSync.withMinecartAnchor(result, hostMinecart.getId(), hostMinecart.getUUID())
                : result;
    }

    private static void stopDeparted(ServerLevel serverLevel, BlockPos sourcePos, String sessionId,
            Set<UUID> previouslySynced, Set<UUID> nearby) {
        Set<UUID> departed = ModernTurntableAudiencePolicy.departed(previouslySynced, nearby);
        PlaybackSessionId parsedSession = PlaybackSessionId.parse(sessionId).orElse(null);
        if (departed.isEmpty() || parsedSession == null) {
            return;
        }
        ModernTurntableStopPacket packet = new ModernTurntableStopPacket(sourcePos, parsedSession.value());
        for (UUID playerId : departed) {
            ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.level() == serverLevel) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
    }

    private static void sendPlayback(ServerPlayer player, BlockPos sourcePos, String synchronizedUrl,
            String rawUrl, String playUrl, String songName, int remainingSeconds) {
        @SuppressWarnings("null")
        MusicToClientMessage message = new MusicToClientMessage(sourcePos, synchronizedUrl,
                rawUrl.isBlank() ? playUrl : rawUrl, Math.max(1, remainingSeconds), songName);
        NetworkHandler.sendToClientPlayer(message, player);
    }
}
