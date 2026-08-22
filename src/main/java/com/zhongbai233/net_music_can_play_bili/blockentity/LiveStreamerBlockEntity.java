package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveRoomInput;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveStreamResolver;
import com.zhongbai233.net_music_can_play_bili.block.LiveStreamerBlock;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.AudioPlaybackIndexSavedData;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final String AUTO_RESUME_TAG = "AutoResumeRequested";
    private static final String STARTED_TIME_TAG = "StartedGameTime";
    private static final String OWNER_TAG = "PlaybackOwner";
    private static final String VOLUME_PER_MILLE_TAG = "VolumePerMille";
    private static final String SOURCE_ID_TAG = "PlaybackSourceId";
    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int FULL_RESYNC_INTERVAL_TICKS = 60;
    private static final ExecutorService LIVE_STATUS_EXECUTOR = Executors.newFixedThreadPool(2,
            NetMusicThreadFactory.daemon("BiliLiveStatus"));
    /**
     * 客户端 Sound 的存活上限来自同步消息里的剩余秒数；直播没有时长，
     * 给一个足够长的滚动值，由每 3 秒的全量重同步不断续期。
     */
    private static final int LIVE_REMAINING_SECONDS = 24 * 60 * 60;

    private final Set<UUID> syncedPlayers = new HashSet<>();

    private String roomId = "";
    private boolean playing;
    private boolean autoResumeRequested;
    private boolean checkingLiveStatus;
    private boolean needsLiveStatusConfirmation;
    private long nextLiveStatusCheckGameTime;
    private ResolveGeneration liveStatusRequestGeneration = ResolveGeneration.initial();
    private long liveStatusProbeId;
    private CancellableTaskFuture<Integer> liveStatusProbeTask;
    private long startedGameTime;
    private long lastFullSyncGameTime;
    private UUID playbackOwnerId;
    private int volumePerMille = 1000;
    private UUID playbackSourceId = UUID.randomUUID();

    public LiveStreamerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.LIVE_STREAMER.get(), pos, blockState);
    }

    public PlaybackSourceId getPlaybackSourceId() {
        return PlaybackSourceId.of(playbackSourceId);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            AudioLinkIndex.registerPlaybackSource(serverLevel, worldPosition, getPlaybackSourceId(),
                    AudioPlaybackIndexSavedData.SourceKind.LIVE_STREAMER);
        }
    }

    @Override
    public void setRemoved() {
        cancelLiveStatusProbe();
        liveStatusRequestGeneration = liveStatusRequestGeneration.next();
        liveStatusProbeId++;
        super.setRemoved();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, LiveStreamerBlockEntity streamer) {
        if (!(level instanceof ServerLevel serverLevel) || !streamer.autoResumeRequested) {
            return;
        }
        if (!streamer.isLiveAllowed(serverLevel, null)) {
            LOGGER.info("直播间已被移出白名单，停止后台检测: pos={} room={}", pos, streamer.roomId);
            streamer.stopLive();
            return;
        }
        long gameTime = serverLevel.getGameTime();
        if (streamer.needsLiveStatusConfirmation) {
            streamer.needsLiveStatusConfirmation = false;
            if (streamer.stopForegroundPlayback()) {
                streamer.markDirty();
            }
        }
        if (streamer.checkingLiveStatus && gameTime >= streamer.nextLiveStatusCheckGameTime) {
            streamer.cancelLiveStatusProbe();
            streamer.checkingLiveStatus = false;
            streamer.liveStatusProbeId++;
            LOGGER.warn("直播间状态检测超过 10 秒，作废旧结果并继续低频检测: pos={} room={}",
                    pos, streamer.roomId);
        }
        if (streamer.playing) {
            if (gameTime - streamer.lastFullSyncGameTime > FULL_RESYNC_INTERVAL_TICKS) {
                streamer.syncedPlayers.clear();
                streamer.lastFullSyncGameTime = gameTime;
            }
            if (gameTime % SYNC_INTERVAL_TICKS == 0) {
                streamer.syncNearbyPlayers(serverLevel);
            }
        }
        if (LiveStatusProbePolicy.shouldProbe(streamer.autoResumeRequested,
                streamer.checkingLiveStatus, gameTime, streamer.nextLiveStatusCheckGameTime)) {
            streamer.probeLiveStatus(serverLevel, null, false);
        }
    }

    public String getRoomId() {
        return roomId;
    }

    @Override
    public boolean isPlaying() {
        return playing;
    }

    public boolean isWaitingForLive() {
        return autoResumeRequested && !playing;
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
        if (autoResumeRequested) {
            return;
        }

        autoResumeRequested = true;
        playbackOwnerId = actor != null ? actor.getUUID() : playbackOwnerId;
        liveStatusRequestGeneration = liveStatusRequestGeneration.next();
        checkingLiveStatus = false;
        nextLiveStatusCheckGameTime = serverLevel.getGameTime();
        markDirty();
        probeLiveStatus(serverLevel, actor != null ? actor.getUUID() : null, true);
    }

    private void probeLiveStatus(ServerLevel serverLevel, UUID feedbackTargetId, boolean userInitiated) {
        if (!LiveStatusProbePolicy.shouldProbe(autoResumeRequested, checkingLiveStatus,
                serverLevel.getGameTime(), nextLiveStatusCheckGameTime)) {
            return;
        }
        checkingLiveStatus = true;
        nextLiveStatusCheckGameTime = LiveStatusProbePolicy.nextProbeGameTime(serverLevel.getGameTime());
        String requestedRoomId = roomId;
        ResolveGeneration requestGeneration = liveStatusRequestGeneration;
        long probeId = ++liveStatusProbeId;
        CancellableTaskFuture<Integer> probeTask = CancellableTaskFuture.submit(LIVE_STATUS_EXECUTOR, () -> {
            try {
                return BiliLiveStreamResolver.queryLiveStatus(requestedRoomId);
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        CancellableTaskFuture<Integer> previous = liveStatusProbeTask;
        liveStatusProbeTask = probeTask;
        if (previous != null && previous != probeTask) {
            previous.cancel(true);
        }
        probeTask.whenCompleteAsync((liveStatus, error) -> {
                    if (liveStatusProbeTask == probeTask) {
                        liveStatusProbeTask = null;
                    }
                    if (isRemoved() || !(level instanceof ServerLevel currentLevel)
                            || !LiveStatusProbePolicy.acceptsResult(autoResumeRequested,
                                    liveStatusRequestGeneration, requestGeneration,
                                    liveStatusProbeId, probeId, roomId, requestedRoomId)) {
                        return;
                    }
                    checkingLiveStatus = false;
                    ServerPlayer feedbackTarget = feedbackTargetId != null
                            ? currentLevel.getServer().getPlayerList().getPlayer(feedbackTargetId)
                            : null;
                    if (error != null) {
                        Throwable cause = error instanceof java.util.concurrent.CompletionException
                                ? error.getCause()
                                : error;
                        if (feedbackTarget != null) {
                            feedbackTarget.sendSystemMessage(Component.translatable(
                                    "message.net_music_can_play_bili.live_streamer.status_unavailable",
                                    requestedRoomId).withStyle(ChatFormatting.YELLOW));
                        }
                        LOGGER.warn("直播间状态检测失败，保持后台重试: pos={} room={} reason={}", worldPosition,
                                requestedRoomId, cause != null ? cause.toString() : "unknown");
                        return;
                    }
                    if (liveStatus == BiliLiveStreamResolver.LIVE_STATUS_OFFLINE) {
                        if (stopForegroundPlayback()) {
                            markDirty();
                        }
                        if (feedbackTarget != null) {
                            feedbackTarget.sendSystemMessage(Component.translatable(
                                    "message.net_music_can_play_bili.live_streamer.room_offline_waiting",
                                    requestedRoomId, LiveStatusProbePolicy.PROBE_INTERVAL_TICKS / 20L)
                                    .withStyle(ChatFormatting.YELLOW));
                        }
                        LOGGER.info("直播间未开播，已关闭前台播放并转入后台检测: pos={} room={} interval={}s",
                                worldPosition, requestedRoomId, LiveStatusProbePolicy.PROBE_INTERVAL_TICKS / 20L);
                        return;
                    }
                    if (liveStatus < 0) {
                        if (userInitiated) {
                            LOGGER.info("直播状态接口暂不可用，保持后台检测: pos={} room={}",
                                    worldPosition, requestedRoomId);
                        }
                        return;
                    }
                    if (!playing) {
                        beginPlaying(currentLevel, playbackOwnerId);
                    }
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
        if (level instanceof ServerLevel serverLevel) {
            IndexedBlockPlaybackSessionManager.remove(serverLevel, getPlaybackSourceId());
        }
        autoResumeRequested = false;
        liveStatusRequestGeneration = liveStatusRequestGeneration.next();
        cancelLiveStatusProbe();
        checkingLiveStatus = false;
        nextLiveStatusCheckGameTime = 0L;
        stopForegroundPlayback();
        markDirty();
    }

    private boolean stopForegroundPlayback() {
        if (!playing) {
            return false;
        }
        if (level instanceof ServerLevel serverLevel) {
            IndexedBlockPlaybackSessionManager.remove(serverLevel, getPlaybackSourceId());
        }
        playing = false;
        startedGameTime = 0L;
        syncedPlayers.clear();
        return true;
    }

    public void stopForBlockRemoval() {
        autoResumeRequested = false;
        liveStatusRequestGeneration = liveStatusRequestGeneration.next();
        cancelLiveStatusProbe();
        checkingLiveStatus = false;
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
        String songName = liveSongName();
        Set<UUID> nearby = IndexedBlockPlaybackSessionManager.publishAndSync(serverLevel, serverLevel,
                getPlaybackSourceId(), worldPosition, BiliLiveRoomInput.placeholderUrl(roomId),
                whitelistSourceId(), songName, playbackSessionId(), elapsedMillis, 0L, LIVE_REMAINING_SECONDS);
        syncedPlayers.clear();
        syncedPlayers.addAll(nearby);
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
        output.putBoolean(AUTO_RESUME_TAG, autoResumeRequested);
        output.putLong(STARTED_TIME_TAG, startedGameTime);
        output.putInt(VOLUME_PER_MILLE_TAG, volumePerMille);
        output.putString(SOURCE_ID_TAG, playbackSourceId.toString());
        if (playbackOwnerId != null) {
            output.putString(OWNER_TAG, playbackOwnerId.toString());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        roomId = sanitizeRoomId(input.getStringOr(ROOM_ID_TAG, ""));
        playing = input.getBooleanOr(PLAYING_TAG, false) && !roomId.isEmpty();
        autoResumeRequested = input.getBooleanOr(AUTO_RESUME_TAG, playing) && !roomId.isEmpty();
        checkingLiveStatus = false;
        needsLiveStatusConfirmation = playing && autoResumeRequested;
        nextLiveStatusCheckGameTime = 0L;
        liveStatusRequestGeneration = liveStatusRequestGeneration.next();
        cancelLiveStatusProbe();
        liveStatusProbeId++;
        startedGameTime = input.getLongOr(STARTED_TIME_TAG, 0L);
        volumePerMille = Math.max(0, Math.min(1000, input.getIntOr(VOLUME_PER_MILLE_TAG, 1000)));
        playbackSourceId = parseUuid(input.getStringOr(SOURCE_ID_TAG, ""));
        if (playbackSourceId == null) {
            playbackSourceId = UUID.randomUUID();
        }
        playbackOwnerId = parseUuid(input.getStringOr(OWNER_TAG, ""));
        syncedPlayers.clear();
    }

    private void cancelLiveStatusProbe() {
        CancellableTaskFuture<Integer> task = liveStatusProbeTask;
        liveStatusProbeTask = null;
        if (task != null) {
            task.cancel(true);
        }
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
