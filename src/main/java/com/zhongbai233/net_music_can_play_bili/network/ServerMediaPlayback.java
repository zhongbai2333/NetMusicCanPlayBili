package com.zhongbai233.net_music_can_play_bili.network;

import java.util.UUID;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Protocol-neutral server-side media playback facade.
 *
 * <p>
 * The backing implementation is still {@link MP4PlaybackSyncManager} while the
 * legacy MP4-named state machine is
 * extracted. New Pad/projector/shared media code should depend on this facade
 * instead of the MP4 manager directly.
 * </p>
 */
public final class ServerMediaPlayback {
    private ServerMediaPlayback() {
    }

    public static void start(ServerPlayer owner, StartRequest request) {
        if (owner == null || request == null) {
            return;
        }
        start(owner, new MP4PlaybackSyncPacket(
                request.ownerId() != null ? request.ownerId() : owner.getUUID(),
                request.sourceId(), request.sourceType(), request.sourceEntityId(), request.sourceX(),
                request.sourceY(), request.sourceZ(), true, request.queueIndex(), request.playUrl(), request.rawUrl(),
                request.songName(), request.durationSeconds(), request.volumePerMille(), request.sessionId(),
                request.elapsedMillis(), request.headphoneRouted()));
    }

    public static void start(ServerPlayer owner, MP4PlaybackSyncPacket packet) {
        MP4PlaybackSyncManager.start(owner, packet);
    }

    public static void stop(ServerPlayer owner, UUID deviceId) {
        MP4PlaybackSyncManager.stop(owner, deviceId);
    }

    public static void stopAndBroadcast(ServerPlayer owner, UUID deviceId) {
        stop(owner, deviceId);
        if (owner != null && deviceId != null) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(owner,
                    MP4PlaybackSyncPacket.stop(owner.getUUID(), deviceId, 0));
        }
    }

    public static void stopExternalPlaybackForLinkedHeadphones(ServerPlayer actor, UUID deviceId) {
        MP4PlaybackSyncManager.stopExternalPlaybackForLinkedHeadphones(actor, deviceId);
    }

    public record StartRequest(UUID ownerId, UUID sourceId, int sourceType, int sourceEntityId, double sourceX,
            double sourceY, double sourceZ, int queueIndex, String playUrl, String rawUrl, String songName,
            int durationSeconds, int volumePerMille, String sessionId, long elapsedMillis, boolean headphoneRouted) {
        public static StartRequest player(ServerPlayer player, UUID deviceId, int queueIndex, String playUrl,
                String rawUrl, String songName, int durationSeconds, int volumePerMille, String sessionId,
                long elapsedMillis) {
            if (player == null) {
                return new StartRequest(null, deviceId, ClientMediaSyncPayload.SOURCE_PLAYER, -1, 0.0D, 0.0D, 0.0D,
                        queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, sessionId,
                        elapsedMillis, false);
            }
            return new StartRequest(player.getUUID(), deviceId, ClientMediaSyncPayload.SOURCE_PLAYER, player.getId(),
                    player.getX(), player.getY() + 1.2D, player.getZ(), queueIndex, playUrl, rawUrl, songName,
                    durationSeconds, volumePerMille, sessionId, elapsedMillis, false);
        }
    }
}