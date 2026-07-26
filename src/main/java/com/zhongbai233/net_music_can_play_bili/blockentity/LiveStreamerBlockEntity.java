package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.github.tartaricacid.netmusic.network.NetworkHandler;
import com.github.tartaricacid.netmusic.network.message.MusicToClientMessage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveRoomInput;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveStreamResolver;
import com.zhongbai233.net_music_can_play_bili.block.LiveStreamerBlock;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 直播机：用法与现代化唱片机一致，但媒体来源是 B站直播间号而不是唱片。
 *
 * <p>
 * 服务端只负责校验直播间号、白名单和向范围内玩家广播占位地址；真实直播流地址
 * 带有 CDN 签名且按请求方发放，必须由每个客户端自行解析。直播没有时间轴，
 * {@link #getPlaybackElapsedMillis} 返回 -1 让音频输出层跳过进度 pacing。
 * </p>
 */
public class LiveStreamerBlockEntity extends BlockEntity implements PlaybackAudioSource {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOM_ID_TAG = "RoomId";
    private static final String PLAYING_TAG = "Playing";
    private static final String STARTED_TIME_TAG = "StartedGameTime";
    private static final String OWNER_TAG = "PlaybackOwner";
    private static final String VOLUME_PER_MILLE_TAG = "VolumePerMille";
    private static final int SYNC_RANGE = 96;
    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int FULL_RESYNC_INTERVAL_TICKS = 60;
    /**
     * 客户端 Sound 的存活上限来自同步消息里的剩余秒数；直播没有时长，
     * 给一个足够长的滚动值，由每 3 秒的全量重同步不断续期。
     */
    private static final int LIVE_REMAINING_SECONDS = 24 * 60 * 60;

    private final Set<UUID> syncedPlayers = new HashSet<>();

    private String roomId = "";
    private boolean playing;
    private boolean checkingLiveStatus;
    private long startedGameTime;
    private long lastFullSyncGameTime;
    private UUID playbackOwnerId;
    private int volumePerMille = 1000;

    public LiveStreamerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.LIVE_STREAMER.get(), pos, blockState);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, LiveStreamerBlockEntity streamer) {
        if (!(level instanceof ServerLevel serverLevel) || !streamer.playing) {
            return;
        }
        if (serverLevel.getGameTime() - streamer.lastFullSyncGameTime > FULL_RESYNC_INTERVAL_TICKS) {
            streamer.syncedPlayers.clear();
            streamer.lastFullSyncGameTime = serverLevel.getGameTime();
        }
        if (serverLevel.getGameTime() % SYNC_INTERVAL_TICKS == 0) {
            streamer.syncNearbyPlayers(serverLevel);
        }
    }

    public String getRoomId() {
        return roomId;
    }

    @Override
    public boolean isPlaying() {
        return playing;
    }

    @Override
    public float getVolume() {
        return volumePerMille / 1000.0F;
    }

    public int getVolumePerMille() {
        return volumePerMille;
    }

    @Override
    public long getPlaybackElapsedMillis(long gameTime) {
        return -1L;
    }

    public void setVolumePerMille(int value) {
        volumePerMille = Math.max(0, Math.min(1000, value));
        markDirty();
    }

    /** 客户端本地预写音量，让 OpenAL 输出即时响应；服务端同步回包会覆盖为权威值。 */
    public void setClientVolumePerMille(int value) {
        if (level != null && level.isClientSide()) {
            volumePerMille = Math.max(0, Math.min(1000, value));
        }
    }

    /** @return 是否接受了该直播间号 */
    public boolean setRoomId(ServerLevel serverLevel, String input, ServerPlayer actor) {
        String text = input == null ? "" : input.trim();
        String parsed = text.isEmpty() ? "" : BiliLiveRoomInput.parseRoomId(text);
        if (!text.isEmpty() && parsed.isEmpty()) {
            if (actor != null) {
                actor.sendSystemMessage(Component.translatable(
                        "message.net_music_can_play_bili.live_streamer.invalid_room")
                        .withStyle(ChatFormatting.RED));
            }
            return false;
        }
        if (parsed.equals(roomId)) {
            return true;
        }
        stopLive();
        roomId = parsed;
        markDirty();
        return true;
    }

    public void startLive(ServerLevel serverLevel, ServerPlayer actor) {
        if (roomId.isEmpty()) {
            if (actor != null) {
                actor.sendSystemMessage(Component.translatable(
                        "message.net_music_can_play_bili.live_streamer.need_room")
                        .withStyle(ChatFormatting.RED));
            }
            return;
        }
        if (!isLiveAllowed(serverLevel, actor)) {
            return;
        }
        if (checkingLiveStatus) {
            return;
        }

        // 先确认开播再进入播放状态：避免向全体客户端广播一个必然失败的会话。
        checkingLiveStatus = true;
        String requestedRoomId = roomId;
        java.util.UUID actorId = actor != null ? actor.getUUID() : null;
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return BiliLiveStreamResolver.queryLiveStatus(requestedRoomId);
                    } catch (IOException e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                }, Util.backgroundExecutor())
                .whenCompleteAsync((liveStatus, error) -> {
                    checkingLiveStatus = false;
                    if (isRemoved() || !(level instanceof ServerLevel currentLevel)
                            || !requestedRoomId.equals(roomId)) {
                        return;
                    }
                    ServerPlayer feedbackTarget = actorId != null
                            ? currentLevel.getServer().getPlayerList().getPlayer(actorId)
                            : null;
                    if (error != null) {
                        Throwable cause = error instanceof java.util.concurrent.CompletionException
                                ? error.getCause()
                                : error;
                        if (feedbackTarget != null) {
                            feedbackTarget.sendSystemMessage(Component.translatable(
                                    "message.net_music_can_play_bili.live_streamer.room_missing", requestedRoomId)
                                    .withStyle(ChatFormatting.RED));
                        }
                        LOGGER.warn("直播间校验失败，不开始播放: pos={} room={} reason={}", worldPosition,
                                requestedRoomId, cause != null ? cause.toString() : "unknown");
                        return;
                    }
                    // liveStatus < 0 表示接口不可用：放行，由客户端播放路径自行处理。
                    if (liveStatus == BiliLiveStreamResolver.LIVE_STATUS_OFFLINE) {
                        if (feedbackTarget != null) {
                            feedbackTarget.sendSystemMessage(Component.translatable(
                                    "message.net_music_can_play_bili.live_streamer.room_offline", requestedRoomId)
                                    .withStyle(ChatFormatting.RED));
                        }
                        LOGGER.info("直播间未开播，不开始播放: pos={} room={}", worldPosition, requestedRoomId);
                        return;
                    }
                    beginPlaying(currentLevel, actorId);
                }, serverLevel.getServer());
    }

    private void beginPlaying(ServerLevel serverLevel, java.util.UUID actorId) {
        playbackOwnerId = actorId != null ? actorId : playbackOwnerId;
        playing = true;
        startedGameTime = serverLevel.getGameTime();
        syncedPlayers.clear();
        lastFullSyncGameTime = serverLevel.getGameTime();
        markDirty();
        syncNearbyPlayers(serverLevel);
        LOGGER.info("直播机开始播放: pos={} room={} owner={}", worldPosition, roomId, playbackOwnerId);
    }

    public void stopLive() {
        if (!playing) {
            return;
        }
        playing = false;
        startedGameTime = 0L;
        syncedPlayers.clear();
        markDirty();
    }

    public void stopForBlockRemoval() {
        playing = false;
        syncedPlayers.clear();
        setChanged();
    }

    private void syncNearbyPlayers(ServerLevel serverLevel) {
        if (roomId.isEmpty()) {
            stopLive();
            return;
        }
        if (!isLiveAllowed(serverLevel, null)) {
            LOGGER.info("直播间已被移出白名单，停止播放: pos={} room={}", worldPosition, roomId);
            stopLive();
            return;
        }

        long elapsedMillis = Math.max(0L, (serverLevel.getGameTime() - startedGameTime) * 50L);
        String syncedUrl = PlaybackSync.withSync(BiliLiveRoomInput.placeholderUrl(roomId),
                playbackSessionId(), elapsedMillis, 0L);
        String songName = liveSongName();
        AABB range = new AABB(worldPosition).inflate(SYNC_RANGE);
        Set<UUID> nearby = new HashSet<>();
        for (ServerPlayer player : serverLevel.getEntitiesOfClass(ServerPlayer.class, range)) {
            nearby.add(player.getUUID());
            if (syncedPlayers.add(player.getUUID())) {
                @SuppressWarnings("null")
                MusicToClientMessage message = new MusicToClientMessage(worldPosition, syncedUrl,
                        whitelistSourceId(), LIVE_REMAINING_SECONDS, songName);
                NetworkHandler.sendToClientPlayer(message, player);
            }
        }
        syncedPlayers.retainAll(nearby);
        PlaybackAuditManager.recordModernTurntable(serverLevel, worldPosition, songName,
                whitelistSourceId(), 0, elapsedMillis, playbackOwnerId);
    }

    private boolean isLiveAllowed(ServerLevel serverLevel, ServerPlayer actor) {
        if (!BiliWhitelistManager.enabled()
                || BiliWhitelistManager.isAllowed(serverLevel.getServer(), whitelistSourceId())) {
            return true;
        }
        if (actor != null) {
            actor.sendSystemMessage(BiliWhitelistManager.denialMessage(actor, whitelistSourceId(), "播放"));
        }
        return false;
    }

    private String whitelistSourceId() {
        return "live:" + roomId;
    }

    private String liveSongName() {
        return "B站直播 " + roomId;
    }

    private String playbackSessionId() {
        return "live-" + Long.toString(worldPosition.asLong()) + "-" + Long.toString(startedGameTime);
    }

    public void markDirty() {
        setChanged();
        if (level != null) {
            BlockState current = level.getBlockState(worldPosition);
            if (current.getBlock() instanceof LiveStreamerBlock
                    && current.getValue(LiveStreamerBlock.PLAYING) != playing) {
                level.setBlock(worldPosition, current.setValue(LiveStreamerBlock.PLAYING, playing), 3);
            }
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString(ROOM_ID_TAG, roomId);
        output.putBoolean(PLAYING_TAG, playing);
        output.putLong(STARTED_TIME_TAG, startedGameTime);
        output.putInt(VOLUME_PER_MILLE_TAG, volumePerMille);
        if (playbackOwnerId != null) {
            output.putString(OWNER_TAG, playbackOwnerId.toString());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        roomId = sanitizeRoomId(input.getStringOr(ROOM_ID_TAG, ""));
        playing = input.getBooleanOr(PLAYING_TAG, false) && !roomId.isEmpty();
        startedGameTime = input.getLongOr(STARTED_TIME_TAG, 0L);
        volumePerMille = Math.max(0, Math.min(1000, input.getIntOr(VOLUME_PER_MILLE_TAG, 1000)));
        playbackOwnerId = parseUuid(input.getStringOr(OWNER_TAG, ""));
        syncedPlayers.clear();
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    private static String sanitizeRoomId(String value) {
        return BiliLiveRoomInput.parseRoomId(value);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
