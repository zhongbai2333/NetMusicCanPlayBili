package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.compat.areacontrol.AreaControlAudioCompat;
import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.net.URI;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Builds and routes authoritative MP4 playback packets to public and headphone audiences. */
final class MP4PlaybackAudienceBroadcaster {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final MP4PlaybackSourceSessionRegistry<MP4PlaybackSyncManager.Session> sessions;
    private final double syncRange;
    private final Consumer<UUID> resolveInvalidator;
    private final PlaybackAuthorizer playbackAuthorizer;

    MP4PlaybackAudienceBroadcaster(
            MP4PlaybackSourceSessionRegistry<MP4PlaybackSyncManager.Session> sessions,
            double syncRange,
            Consumer<UUID> resolveInvalidator,
            PlaybackAuthorizer playbackAuthorizer) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.syncRange = Math.max(0.0D, syncRange);
        this.resolveInvalidator = Objects.requireNonNull(resolveInvalidator, "resolveInvalidator");
        this.playbackAuthorizer = Objects.requireNonNull(playbackAuthorizer, "playbackAuthorizer");
    }

    void broadcast(ServerLevel level, MP4PlaybackSyncManager.Session session, long gameTime) {
        if (level == null || session == null) {
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(session.ownerId());
        if (!playbackAuthorizer.isAllowed(level, session.rawUrl(), owner)) {
            if (sessions.remove(session.sourceId(), session)) {
                resolveInvalidator.accept(session.sourceId());
                broadcastStop(level, session);
            }
            return;
        }
        MP4PlaybackSyncManager.SourcePosition pos = session.sourcePosition(level);
        AreaAudioZone sourceZone = AreaControlAudioCompat.zoneAt(level,
                BlockPos.containing(pos.x(), pos.y(), pos.z()));
        MP4PlaybackSyncPacket packet = new MP4PlaybackSyncPacket(
                session.ownerId(), session.sourceId(), session.sourceType(), pos.entityId(), pos.x(), pos.y(), pos.z(),
                true, session.queueIndex(), session.playUrl(), session.rawUrl(), session.songName(),
                session.durationSeconds(), session.volumePerMille(), session.sessionId(),
                session.elapsedMillis(gameTime), false, sourceZone);
        MP4PlaybackSyncPacket headphonePacket = new MP4PlaybackSyncPacket(
                packet.ownerId(), packet.sourceId(), packet.sourceType(), packet.sourceEntityId(), packet.sourceX(),
                packet.sourceY(), packet.sourceZ(), packet.playing(), packet.queueIndex(), packet.playUrl(),
                packet.rawUrl(), packet.songName(), packet.durationSeconds(), packet.volumePerMille(),
                packet.sessionId(), packet.elapsedMillis(), true, AreaAudioZone.unrestricted());
        Set<UUID> headphoneRecipients = sendToHeadphoneListeners(level, session, pos, headphonePacket);
        routePublicPacket(level, session, pos, owner, headphoneRecipients, packet);
        if (session.sourceType() != ClientMediaSyncPayload.SOURCE_PLAYER) {
            LOGGER.trace(
                    "MP4 播放同步下发: owner={} source={} type={} song='{}' session={} elapsed={}ms host={} ownerOnline={} routedToHeadphones={}",
                    session.ownerId(), session.sourceId(), session.sourceType(), session.songName(),
                    session.sessionId(), session.elapsedMillis(gameTime), safeHost(session.playUrl()),
                    owner != null && owner.level() == level, !headphoneRecipients.isEmpty());
        }
    }

    void broadcastTimeline(ServerLevel level, MP4PlaybackSyncManager.Session session, long gameTime) {
        if (level == null || session == null) {
            return;
        }
        MP4PlaybackSyncManager.SourcePosition pos = session.sourcePosition(level);
        AreaAudioZone sourceZone = AreaControlAudioCompat.zoneAt(level,
                BlockPos.containing(pos.x(), pos.y(), pos.z()));
        MP4PlaybackTimelinePacket packet = new MP4PlaybackTimelinePacket(session.sourceId(), session.sessionId(),
                session.elapsedMillis(gameTime), session.volumePerMille(), false, sourceZone);
        MP4PlaybackTimelinePacket headphonePacket = new MP4PlaybackTimelinePacket(session.sourceId(),
                session.sessionId(), session.elapsedMillis(gameTime), session.volumePerMille(), true,
                AreaAudioZone.unrestricted());
        Set<UUID> headphoneRecipients = sendTimelineToHeadphoneListeners(level, session, pos, headphonePacket);
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(session.ownerId());
        routePublicTimeline(level, session, pos, owner, headphoneRecipients, packet);
    }

    void broadcastStop(ServerLevel level, MP4PlaybackSyncManager.Session session) {
        if (level == null || session == null) {
            return;
        }
        MP4PlaybackSyncManager.SourcePosition pos = session.sourcePosition(level);
        MP4PlaybackSyncPacket stop = MP4PlaybackSyncPacket.stop(session.ownerId(), session.sourceId(),
                session.queueIndex());
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(session.ownerId());
        if (owner != null && owner.level() == level) {
            PacketDistributor.sendToPlayer(owner, stop);
        }
        for (UUID playerId : AudioLinkIndex.headphonePlayersForMp4(session.sourceId())) {
            if (owner != null && playerId.equals(owner.getUUID())) {
                continue;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.level() == level) {
                PacketDistributor.sendToPlayer(player, stop);
            }
        }
        boolean playerSource = session.sourceType() == ClientMediaSyncPayload.SOURCE_PLAYER;
        if (MediaAudienceRoutingPolicy.shouldBroadcastStopNearby(playerSource, session.sessionId())) {
            PacketDistributor.sendToPlayersNear(level, null, pos.x(), pos.y(), pos.z(), syncRange, stop);
        }
    }

    void stopExternalPlaybackForLinkedHeadphones(ServerPlayer actor, UUID deviceId) {
        if (actor == null || deviceId == null || !(actor.level() instanceof ServerLevel level)) {
            return;
        }
        MP4PlaybackSyncManager.Session session = sessions.get(deviceId);
        if (session == null) {
            return;
        }
        MP4PlaybackSyncManager.SourcePosition pos = session.sourcePosition(level);
        MP4PlaybackSyncPacket stop = MP4PlaybackSyncPacket.stop(session.ownerId(), session.sourceId(),
                session.queueIndex());
        double rangeSquared = syncRange * syncRange;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos.x(), pos.y(), pos.z()) <= rangeSquared
                    && !isWearingHeadphonesLinkedTo(player, session.sourceId())) {
                PacketDistributor.sendToPlayer(player, stop);
            }
        }
    }

    MP4PlaybackSyncPacket stopPacketFor(UUID deviceId) {
        MP4PlaybackSyncManager.Session session = sessions.get(deviceId);
        if (session != null) {
            return MP4PlaybackSyncPacket.stop(session.ownerId(), session.sourceId(), session.queueIndex());
        }
        return MP4PlaybackSyncPacket.stop(deviceId, deviceId, 0);
    }

    private void routePublicPacket(ServerLevel level, MP4PlaybackSyncManager.Session session,
            MP4PlaybackSyncManager.SourcePosition pos, ServerPlayer owner, Set<UUID> headphoneRecipients,
            MP4PlaybackSyncPacket packet) {
        MediaAudienceRoutingPolicy.PublicRoute route = publicRoute(level, session, owner, headphoneRecipients);
        if (route == MediaAudienceRoutingPolicy.PublicRoute.NEARBY) {
            PacketDistributor.sendToPlayersNear(level, null, pos.x(), pos.y(), pos.z(), syncRange, packet);
        } else if (route == MediaAudienceRoutingPolicy.PublicRoute.OWNER) {
            PacketDistributor.sendToPlayer(owner, packet);
        }
    }

    private void routePublicTimeline(ServerLevel level, MP4PlaybackSyncManager.Session session,
            MP4PlaybackSyncManager.SourcePosition pos, ServerPlayer owner, Set<UUID> headphoneRecipients,
            MP4PlaybackTimelinePacket packet) {
        MediaAudienceRoutingPolicy.PublicRoute route = publicRoute(level, session, owner, headphoneRecipients);
        if (route == MediaAudienceRoutingPolicy.PublicRoute.NEARBY) {
            PacketDistributor.sendToPlayersNear(level, null, pos.x(), pos.y(), pos.z(), syncRange, packet);
        } else if (route == MediaAudienceRoutingPolicy.PublicRoute.OWNER) {
            PacketDistributor.sendToPlayer(owner, packet);
        }
    }

    private static MediaAudienceRoutingPolicy.PublicRoute publicRoute(ServerLevel level,
            MP4PlaybackSyncManager.Session session, ServerPlayer owner, Set<UUID> headphoneRecipients) {
        boolean playerSource = session.sourceType() == ClientMediaSyncPayload.SOURCE_PLAYER;
        boolean ownerOnline = owner != null && owner.level() == level;
        boolean ownerReceivedHeadphoneRoute = owner != null && headphoneRecipients.contains(owner.getUUID());
        return MediaAudienceRoutingPolicy.publicRoute(playerSource, session.sessionId(),
                !headphoneRecipients.isEmpty(), ownerOnline, ownerReceivedHeadphoneRoute);
    }

    private static Set<UUID> sendToHeadphoneListeners(ServerLevel level,
            MP4PlaybackSyncManager.Session session, MP4PlaybackSyncManager.SourcePosition pos,
            MP4PlaybackSyncPacket packet) {
        Set<UUID> recipients = new HashSet<>();
        MP4PlaybackSyncPacket stop = MP4PlaybackSyncPacket.stop(session.ownerId(), session.sourceId(),
                session.queueIndex());
        for (UUID playerId : AudioLinkIndex.headphonePlayersForMp4(session.sourceId())) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
            if (player == null || player.level() != level) {
                AudioLinkIndex.removeHeadphonePlayer(playerId);
                continue;
            }
            boolean linked = isWearingHeadphonesLinkedTo(player, session.sourceId());
            double distanceSquared = player.distanceToSqr(pos.x(), pos.y(), pos.z());
            if (linked && distanceSquared <= AudioLinkData.MP4_HEADPHONE_RANGE_SQUARED) {
                AudioLinkIndex.updatePlayerHeadphones(player);
                PacketDistributor.sendToPlayer(player, packet);
                recipients.add(playerId);
            } else {
                rejectHeadphoneRoute(player, linked, distanceSquared);
                PacketDistributor.sendToPlayer(player, stop);
            }
        }
        return Set.copyOf(recipients);
    }

    private static Set<UUID> sendTimelineToHeadphoneListeners(ServerLevel level,
            MP4PlaybackSyncManager.Session session, MP4PlaybackSyncManager.SourcePosition pos,
            MP4PlaybackTimelinePacket packet) {
        Set<UUID> recipients = new HashSet<>();
        MP4PlaybackSyncPacket stop = MP4PlaybackSyncPacket.stop(session.ownerId(), session.sourceId(),
                session.queueIndex());
        for (UUID playerId : AudioLinkIndex.headphonePlayersForMp4(session.sourceId())) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
            if (player == null || player.level() != level) {
                AudioLinkIndex.removeHeadphonePlayer(playerId);
                continue;
            }
            boolean linked = isWearingHeadphonesLinkedTo(player, session.sourceId());
            double distanceSquared = player.distanceToSqr(pos.x(), pos.y(), pos.z());
            if (linked && distanceSquared <= AudioLinkData.MP4_HEADPHONE_RANGE_SQUARED) {
                AudioLinkIndex.updatePlayerHeadphones(player);
                PacketDistributor.sendToPlayer(player, packet);
                recipients.add(playerId);
            } else {
                rejectHeadphoneRoute(player, linked, distanceSquared);
                PacketDistributor.sendToPlayer(player, stop);
            }
        }
        return Set.copyOf(recipients);
    }

    private static void rejectHeadphoneRoute(ServerPlayer player, boolean linked, double distanceSquared) {
        if (linked && distanceSquared > AudioLinkData.MP4_HEADPHONE_RANGE_SQUARED) {
            clearLinkedMp4(player);
            player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.headphones.mp4_out_of_range"));
        }
    }

    private static boolean isWearingHeadphonesLinkedTo(ServerPlayer player, UUID deviceId) {
        if (player == null || deviceId == null) {
            return false;
        }
        ItemStack head = EquippedMediaItems.firstHeadphones(player);
        return HeadphoneAbility.has(head) && deviceId.equals(AudioLinkData.readHeadphoneMp4(head));
    }

    private static void clearLinkedMp4(ServerPlayer player) {
        ItemStack head = EquippedMediaItems.firstHeadphones(player);
        if (HeadphoneAbility.has(head)) {
            AudioLinkData.clearHeadphoneMp4(head);
            player.getInventory().setChanged();
            AudioLinkIndex.removeHeadphonePlayer(player);
            AudioLinkIndex.updatePlayerHeadphones(player);
        }
    }

    private static String safeHost(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        try {
            String stripped = PlaybackSync.strip(value);
            String host = URI.create(stripped != null ? stripped : value).getHost();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    @FunctionalInterface
    interface PlaybackAuthorizer {
        boolean isAllowed(ServerLevel level, String sourceUrl, ServerPlayer actor);
    }
}
