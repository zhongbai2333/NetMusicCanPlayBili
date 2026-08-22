package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.network.ModernTurntableStopPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-owned active sessions continue routing after their source chunk unloads. */
@EventBusSubscriber
public final class IndexedBlockPlaybackSessionManager {
    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int MAX_SESSION_ID_LENGTH = 128;
    private static final Map<PlaybackSourceId, Session> SESSIONS = new ConcurrentHashMap<>();

    private IndexedBlockPlaybackSessionManager() {
    }

    public static Set<UUID> publishAndSync(ServerLevel level, Level anchorLevel, PlaybackSourceId sourceId,
            BlockPos sourcePos, String playUrl, String rawUrl, String songName, String sessionId,
            long elapsedMillis, long durationMillis, int remainingSeconds) {
        return publishAndSync(level, anchorLevel, sourceId, sourcePos, playUrl, rawUrl, songName, sessionId,
                elapsedMillis, durationMillis, remainingSeconds, false);
    }

    public static Set<UUID> publishAndSync(ServerLevel level, Level anchorLevel, PlaybackSourceId sourceId,
            BlockPos sourcePos, String playUrl, String rawUrl, String songName, String sessionId,
            long elapsedMillis, long durationMillis, int remainingSeconds, boolean repeat) {
        if (level == null || sourceId == null || sourcePos == null || playUrl == null || playUrl.isBlank()
                || PlaybackSessionId.parse(sessionId).isEmpty()) {
            return Set.of();
        }
        long gameTime = level.getGameTime();
        Session next = SESSIONS.compute(sourceId, (ignored, current) -> {
            if (current != null && !current.sessionId.equals(sessionId)) {
                stopRecipients(level, current);
                current = null;
            }
            if (current == null) {
                current = new Session(level.dimension(), anchorLevel, sourcePos.immutable(), playUrl, rawUrl,
                        songName, sessionId, elapsedMillis, durationMillis, remainingSeconds, repeat, gameTime);
            } else {
                current.update(anchorLevel, sourcePos, playUrl, rawUrl, songName, elapsedMillis,
                        durationMillis, remainingSeconds, repeat, gameTime);
            }
            return current;
        });
        sync(level, sourceId, next, gameTime);
        return Set.copyOf(next.syncedPlayers);
    }

    public static void remove(ServerLevel level, PlaybackSourceId sourceId) {
        Session removed = sourceId != null ? SESSIONS.remove(sourceId) : null;
        if (removed != null) {
            if (level != null) {
                stopRecipients(level, removed);
            }
            ModernTurntableAudienceSync.forgetSource(sourceId);
        }
    }

    public static boolean contains(PlaybackSourceId sourceId) {
        return sourceId != null && SESSIONS.containsKey(sourceId);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % SYNC_INTERVAL_TICKS != 0) {
            return;
        }
        for (var indexed : SESSIONS.entrySet()) {
            Session session = indexed.getValue();
            ServerLevel level = event.getServer().getLevel(session.dimension);
            if (level == null) {
                if (SESSIONS.remove(indexed.getKey(), session)) {
                    ModernTurntableAudienceSync.forgetSource(indexed.getKey());
                }
                continue;
            }
            long gameTime = level.getGameTime();
            long elapsed = session.elapsedAt(gameTime);
            if (session.durationMillis > 0L && elapsed >= session.durationMillis) {
                if (session.repeat) {
                    stopRecipients(level, session);
                    session.restartForIndexedRepeat(gameTime);
                    sync(level, indexed.getKey(), session, gameTime);
                    continue;
                }
                if (SESSIONS.remove(indexed.getKey(), session)) {
                    stopRecipients(level, session);
                    ModernTurntableAudienceSync.forgetSource(indexed.getKey());
                }
                continue;
            }
            sync(level, indexed.getKey(), session, gameTime);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModernTurntableAudienceSync.forgetPlayer(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SESSIONS.clear();
        ModernTurntableAudienceSync.clearSubscriptions();
    }

    private static void sync(ServerLevel level, PlaybackSourceId sourceId, Session session, long gameTime) {
        if (session.lastSyncGameTime == gameTime) {
            return;
        }
        long elapsed = session.elapsedAt(gameTime);
        int remaining = session.durationMillis > 0L
                ? (int) Math.max(1L, (session.durationMillis - elapsed + 999L) / 1_000L)
                : session.remainingSeconds;
        Set<UUID> recipients = ModernTurntableAudienceSync.syncNearbyPlayers(level, session.anchorLevel,
                session.sourcePos, sourceId, session.syncedPlayers, session.playUrl, session.rawUrl,
                session.songName, session.sessionId, elapsed, session.durationMillis, remaining,
                com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange.SYNC_DISTANCE_BLOCKS);
        session.syncedPlayers.clear();
        session.syncedPlayers.addAll(recipients);
        session.knownPlayers.addAll(recipients);
        session.lastSyncGameTime = gameTime;
    }

    private static void stopRecipients(ServerLevel level, Session session) {
        PlaybackSessionId parsed = PlaybackSessionId.parse(session.sessionId).orElse(null);
        if (parsed == null) {
            return;
        }
        ModernTurntableStopPacket packet = new ModernTurntableStopPacket(session.sourcePos, parsed.value());
        for (UUID playerId : session.knownPlayers) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.level() == level) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
    }

    private static final class Session {
        private final ResourceKey<Level> dimension;
        private final String rootSessionId;
        private Level anchorLevel;
        private BlockPos sourcePos;
        private String playUrl;
        private String rawUrl;
        private String songName;
        private String sessionId;
        private long repeatGeneration;
        private boolean repeat;
        private long baseElapsedMillis;
        private long durationMillis;
        private int remainingSeconds;
        private long publishedGameTime;
        private long lastSyncGameTime = Long.MIN_VALUE;
        private final Set<UUID> syncedPlayers = new HashSet<>();
        /** Every client that received this exact session; authoritative stop must reach dormant clients too. */
        private final Set<UUID> knownPlayers = new HashSet<>();

        private Session(ResourceKey<Level> dimension, Level anchorLevel, BlockPos sourcePos,
                String playUrl, String rawUrl, String songName, String sessionId,
                long elapsedMillis, long durationMillis, int remainingSeconds, boolean repeat, long gameTime) {
            this.dimension = dimension;
            this.rootSessionId = sessionId;
            this.sessionId = sessionId;
            update(anchorLevel, sourcePos, playUrl, rawUrl, songName, elapsedMillis,
                    durationMillis, remainingSeconds, repeat, gameTime);
        }

        private void update(Level anchorLevel, BlockPos sourcePos, String playUrl, String rawUrl,
                String songName, long elapsedMillis, long durationMillis, int remainingSeconds,
                boolean repeat, long gameTime) {
            this.anchorLevel = anchorLevel;
            this.sourcePos = sourcePos.immutable();
            this.playUrl = playUrl;
            this.rawUrl = rawUrl != null ? rawUrl : "";
            this.songName = songName != null ? songName : "";
            this.repeat = repeat;
            this.baseElapsedMillis = Math.max(0L, elapsedMillis);
            this.durationMillis = Math.max(0L, durationMillis);
            this.remainingSeconds = Math.max(1, remainingSeconds);
            this.publishedGameTime = gameTime;
        }

        private void restartForIndexedRepeat(long gameTime) {
            repeatGeneration++;
            String suffix = "~indexed-repeat-" + repeatGeneration;
            int rootLength = Math.min(rootSessionId.length(), MAX_SESSION_ID_LENGTH - suffix.length());
            sessionId = PlaybackSessionId.of(rootSessionId.substring(0, rootLength) + suffix).value();
            baseElapsedMillis = 0L;
            remainingSeconds = (int) Math.max(1L, (durationMillis + 999L) / 1_000L);
            publishedGameTime = gameTime;
            lastSyncGameTime = Long.MIN_VALUE;
            syncedPlayers.clear();
            knownPlayers.clear();
        }

        private long elapsedAt(long gameTime) {
            return baseElapsedMillis + Math.max(0L, gameTime - publishedGameTime) * 50L;
        }
    }
}
