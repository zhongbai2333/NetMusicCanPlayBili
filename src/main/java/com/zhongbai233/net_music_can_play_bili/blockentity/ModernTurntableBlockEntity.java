package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.network.NetworkHandler;
import com.github.tartaricacid.netmusic.network.message.MusicToClientMessage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.compat.minecartrevolution.MinecartTurntableCompat;
import com.zhongbai233.net_music_can_play_bili.block.ModernTurntableBlock;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStackResourceHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public class ModernTurntableBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DISC_TAG = "Disc";
    private static final String PLAYING_TAG = "Playing";
    private static final String RAW_URL_TAG = "RawUrl";
    private static final String PLAY_URL_TAG = "PlayUrl";
    private static final String SONG_NAME_TAG = "SongName";
    private static final String DURATION_TAG = "DurationSeconds";
    private static final String STARTED_TIME_TAG = "StartedGameTime";
    private static final String ELAPSED_SECONDS_TAG = "ElapsedSeconds";
    private static final String ELAPSED_TICKS_TAG = "ElapsedTicks";
    private static final String SEEK_GENERATION_TAG = "SeekGeneration";
    private static final String OWNER_TAG = "PlaybackOwner";
    private static final String REPEAT_ONE_TAG = "RepeatOne";
    private static final String REDSTONE_MODE_TAG = "RedstoneMode";
    private static final String EXTRACTION_MODE_TAG = "ExtractionMode";
    private static final String PLAYBACK_COMPLETED_TAG = "PlaybackCompleted";
    private static final String VOLUME_PER_MILLE_TAG = "VolumePerMille";
    private static final int SYNC_RANGE = 96;
    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final Set<ModernTurntableBlockEntity> LOADED_SERVER_TURNTABLES = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private final Set<UUID> syncedPlayers = new HashSet<>();

    private ItemStack disc = ItemStack.EMPTY;
    private boolean playing;
    private String rawUrl = "";
    private String playUrl = "";
    private String songName = "";
    private int durationSeconds;
    private long startedGameTime;
    private int savedElapsedSeconds;
    private long savedElapsedTicks;
    private int seekGeneration;
    private long lastFullSyncGameTime;
    private boolean needsResolveOnLoad;
    private boolean pendingAutomaticStart;
    private boolean resolvingPlayback;
    private boolean redstonePowered;
    private boolean redstoneStateInitialized;
    private boolean pulsePlaybackRequested;
    private int lastComparatorOutput = -1;
    private UUID playbackOwnerId;
    private boolean repeatOne;
    private TurntableRedstoneMode redstoneMode = TurntableRedstoneMode.IGNORE;
    private TurntableExtractionMode extractionMode = TurntableExtractionMode.AFTER_PLAYBACK;
    private boolean playbackCompleted;
    private int volumePerMille = 1000;
    private transient LyricRecord clientLyricRecord;
    private transient String clientLyricSessionId = "";
    private transient int clientLyricTick = -1;

    public ModernTurntableBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.MODERN_TURNTABLE.get(), pos, blockState);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            LOADED_SERVER_TURNTABLES.add(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LOADED_SERVER_TURNTABLES.remove(this);
        syncedPlayers.clear();
        clientLyricRecord = null;
        clientLyricSessionId = "";
        clientLyricTick = -1;
    }

    private final ResourceHandler<ItemResource> itemHandler = new ItemStackResourceHandler() {
        @Override
        protected ItemStack getStack() {
            return disc;
        }

        @Override
        protected void setStack(ItemStack stack) {
            disc = stack.isEmpty() ? ItemStack.EMPTY : BiliSongInfoSanitizer.sanitizeDisc(stack.copyWithCount(1));
        }

        @Override
        protected boolean isValid(ItemResource resource) {
            ItemStack stack = Objects.requireNonNull(resource.toStack(), "ItemResource.toStack");
            return ItemMusicCD.getSongInfo(stack) != null;
        }

        @Override
        protected int getCapacity(ItemResource resource) {
            return 1;
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            if (!canAutomationExtract()) {
                return 0;
            }
            return super.extract(index, resource, amount, transaction);
        }

        @Override
        protected void onRootCommit(ItemStack originalStack) {
            boolean hadDisc = !originalStack.isEmpty();
            if (hadDisc && disc.isEmpty()) {
                pendingAutomaticStart = false;
                stopPlayback();
                markDirty();
            } else if (!hadDisc && !disc.isEmpty()) {
                playbackCompleted = false;
                pendingAutomaticStart = true;
                markDirty();
            } else {
                markDirty();
            }
        }
    };

    public ResourceHandler<ItemResource> getItemHandler() {
        return itemHandler;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ModernTurntableBlockEntity turntable) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        turntable.pollRedstoneSignal(serverLevel);
        if (turntable.pendingAutomaticStart && turntable.hasDisc() && !turntable.playing
                && !turntable.resolvingPlayback) {
            turntable.pendingAutomaticStart = false;
            if (turntable.redstoneMode != TurntableRedstoneMode.PULSE_TOGGLE
                    && turntable.redstoneMode.shouldPlay(turntable.redstonePowered)) {
                turntable.startFromDisc();
            }
        }
        // 世界加载后需要重新解析 B站 CDN 直链（旧 URL 可能已过期）
        if (turntable.needsResolveOnLoad && turntable.playing && !turntable.rawUrl.isBlank()) {
            turntable.needsResolveOnLoad = false;
            turntable.resolveAndResume(serverLevel);
            return;
        }
        if (!turntable.playing) {
            turntable.updateComparatorOutput();
            return;
        }
        int remaining = turntable.remainingSeconds(serverLevel.getGameTime());
        if (remaining <= 0) {
            if (turntable.repeatOne && turntable.hasPlaybackData()) {
                turntable.restartForRepeat(serverLevel);
            } else {
                turntable.playbackCompleted = true;
                turntable.pulsePlaybackRequested = false;
                turntable.stopPlayback();
            }
            return;
        }
        // 每 3 秒全量重同步：覆盖关闭音量后进入范围、重进等边缘情况
        if (serverLevel.getGameTime() - turntable.lastFullSyncGameTime > 60) {
            turntable.syncedPlayers.clear();
            turntable.lastFullSyncGameTime = serverLevel.getGameTime();
        }
        if (serverLevel.getGameTime() % SYNC_INTERVAL_TICKS == 0) {
            turntable.syncNearbyPlayers(serverLevel, remaining);
        }
        turntable.updateComparatorOutput();
        turntable.recordAudit(serverLevel);
    }

    private void resolveAndResume(ServerLevel serverLevel) {
        if (!isPlaybackAllowed(serverLevel, rawUrl, null)) {
            resolvingPlayback = false;
            stopPlayback();
            return;
        }
        long elapsedTicks = snapshotElapsedTicks(serverLevel.getGameTime());
        if (!(rawUrl.startsWith("BV") || rawUrl.startsWith("bv") || rawUrl.startsWith("av") || rawUrl.startsWith("AV"))
                || !rawUrl.contains("|p=")) {
            // 非 B站 存储选集的 URL：尝试直接用保存的 URL 恢复
            if (!redstoneAllowsPlayback(serverLevel)) {
                resolvingPlayback = false;
                return;
            }
            playing = true;
            resolvingPlayback = false;
            startedGameTime = serverLevel.getGameTime() - elapsedTicks;
            syncedPlayers.clear();
            markDirty();
            syncNearbyPlayers(serverLevel, remainingSeconds(serverLevel.getGameTime()));
            return;
        }
        // B站 存储选集的 URL：重新解析 CDN 直链
        playing = false;
        syncedPlayers.clear();
        markDirty();
        @SuppressWarnings("null")
        ItemMusicCD.SongInfo resumeInfo = new ItemMusicCD.SongInfo(rawUrl, songName, durationSeconds, false);
        MusicPlayResolverManager.resolve(resumeInfo)
                .thenAcceptAsync(resolved -> {
                    resolvingPlayback = false;
                    if (!playing || !Objects.equals(rawUrl, resolved.songUrl != null ? resolved.songUrl : rawUrl)) {
                        String newUrl = playbackUrlForStorage(rawUrl,
                                resolved.songUrl != null ? resolved.songUrl : playUrl);
                        if (!newUrl.isBlank() && redstoneAllowsPlayback(serverLevel)) {
                            playUrl = newUrl;
                            playing = true;
                            resolvingPlayback = false;
                            startedGameTime = serverLevel.getGameTime() - elapsedTicks;
                            durationSeconds = Math.max(1, resolved.songTime > 0 ? resolved.songTime : durationSeconds);
                            syncedPlayers.clear();
                            markDirty();
                            syncNearbyPlayers(serverLevel, remainingSeconds(serverLevel.getGameTime()));
                        }
                    }
                }, serverLevel.getServer())
                .exceptionally(error -> {
                    resolvingPlayback = false;
                    LOGGER.error("现代化唱片机恢复播放 B站 解析失败: {}", songName, error);
                    return null;
                });
    }

    public boolean hasDisc() {
        return !disc.isEmpty();
    }

    public ItemStack getDisc() {
        return disc;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isRepeatOne() {
        return repeatOne;
    }

    public TurntableRedstoneMode getRedstoneMode() {
        return redstoneMode;
    }

    public TurntableExtractionMode getExtractionMode() {
        return extractionMode;
    }

    public float getVolume() {
        return volumePerMille / 1000.0F;
    }

    public int getVolumePerMille() {
        return volumePerMille;
    }

    public String getSongName() {
        return songName;
    }

    public String getRawUrl() {
        return rawUrl;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public boolean hasPlaybackData() {
        return durationSeconds > 0 && (!playUrl.isBlank() || !rawUrl.isBlank());
    }

    public int getComparatorOutput() {
        if (!hasDisc() || durationSeconds <= 0 || level == null) {
            return 0;
        }
        long elapsedMillis = getPlaybackElapsedMillis(level.getGameTime());
        long durationMillis = Math.max(1L, durationSeconds * 1000L);
        return TurntableComparatorSignal.fromProgress(true, elapsedMillis, durationMillis);
    }

    public LyricRecord getClientLyricRecord() {
        return clientLyricRecord;
    }

    public int getClientLyricTick() {
        return clientLyricTick;
    }

    public void setClientLyricRecord(LyricRecord lyricRecord, String sessionId) {
        setClientLyricRecord(lyricRecord, sessionId, -1);
    }

    public void setClientLyricRecord(LyricRecord lyricRecord, String sessionId, int lyricTick) {
        if (level != null && !level.isClientSide()) {
            return;
        }
        clientLyricRecord = lyricRecord;
        clientLyricSessionId = normalizeSessionId(sessionId);
        clientLyricTick = lyricTick;
    }

    public void clearClientLyricRecord(String sessionId) {
        if (level != null && !level.isClientSide()) {
            return;
        }
        String normalized = normalizeSessionId(sessionId);
        if (normalized.isBlank() || clientLyricSessionId.isBlank() || clientLyricSessionId.equals(normalized)) {
            clientLyricRecord = null;
            clientLyricSessionId = "";
            clientLyricTick = -1;
        }
    }

    public long getPlaybackElapsedMillis(long gameTime) {
        if (playing) {
            return Math.min(durationSeconds * 1000L, Math.max(0L, (gameTime - startedGameTime) * 50L));
        }
        long elapsedTicks = storedElapsedTicks();
        return Math.min(durationSeconds * 1000L, Math.max(0L, elapsedTicks * 50L));
    }

    public PlaybackSync.Metadata getPlaybackSyncMetadata(long gameTime) {
        if (!playing || playUrl.isBlank()) {
            return new PlaybackSync.Metadata("", 0L, 0L);
        }
        return new PlaybackSync.Metadata(playbackSessionId(), elapsedMillis(gameTime), durationSeconds * 1000L);
    }

    public void setDisc(ItemStack stack) {
        disc = stack.isEmpty() ? ItemStack.EMPTY : BiliSongInfoSanitizer.sanitizeDisc(stack);
        playbackCompleted = false;
        pendingAutomaticStart = false;
        resolvingPlayback = false;
        stopPlayback();
        markDirty();
    }

    public ItemStack removeDisc() {
        ItemStack removed = disc;
        disc = ItemStack.EMPTY;
        pendingAutomaticStart = false;
        resolvingPlayback = false;
        stopPlayback();
        markDirty();
        return removed;
    }

    public ItemStack removeDiscForBlockRemoval() {
        ItemStack removed = disc;
        disc = ItemStack.EMPTY;
        stopPlaybackWithoutBlockUpdate();
        setChanged();
        return removed;
    }

    public void stopPlaybackForBlockRemoval() {
        stopPlaybackWithoutBlockUpdate();
        setChanged();
    }

    public void startFromDisc(ServerPlayer triggerPlayer) {
        startFromDiscInternal(triggerPlayer);
    }

    public void startFromDisc() {
        startFromDiscInternal(null);
    }

    private void startFromDiscInternal(ServerPlayer triggerPlayer) {
        if (!(level instanceof ServerLevel serverLevel) || disc.isEmpty()) {
            return;
        }
        pendingAutomaticStart = false;
        playbackCompleted = false;
        if (!redstoneAllowsPlayback(serverLevel)) {
            return;
        }

        @SuppressWarnings("null")
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(disc);
        if (songInfo == null) {
            if (triggerPlayer != null) {
                triggerPlayer.sendSystemMessage(Component.translatable(
                        "message.net_music_can_play_bili.modern_turntable.need_cd"));
            }
            return;
        }
        if (songInfo.vip && !MusicPlayResolverManager.canResolve(songInfo)) {
            if (triggerPlayer != null) {
                triggerPlayer.sendSystemMessage(Component.translatable(
                        "message.net_music_can_play_bili.modern_turntable.need_vip"));
            }
            return;
        }
        if (!isPlaybackAllowed(serverLevel, songInfo.songUrl, triggerPlayer)) {
            return;
        }

        ItemMusicCD.SongInfo original = songInfo.clone();
        playbackOwnerId = triggerPlayer != null ? triggerPlayer.getUUID() : null;
        resolvingPlayback = true;
        MusicPlayResolverManager.resolve(original.clone())
                .thenAcceptAsync(resolved -> applyResolvedPlayback(serverLevel, original, resolved),
                        serverLevel.getServer())
                .exceptionally(error -> {
                    resolvingPlayback = false;
                    LOGGER.error("现代化唱片机解析播放失败: {}", original.songName, error);
                    return null;
                });
    }

    private void applyResolvedPlayback(ServerLevel serverLevel, ItemMusicCD.SongInfo original,
            ItemMusicCD.SongInfo resolved) {
        resolvingPlayback = false;
        @SuppressWarnings("null")
        ItemMusicCD.SongInfo current = ItemMusicCD.getSongInfo(disc);
        if (current == null || !Objects.equals(current.songUrl, original.songUrl)) {
            return;
        }
        if (!isPlaybackAllowed(serverLevel, original.songUrl, null)) {
            stopPlayback();
            return;
        }
        if (!redstoneAllowsPlayback(serverLevel)) {
            return;
        }

        rawUrl = original.songUrl != null ? original.songUrl : "";
        playUrl = playbackUrlForStorage(rawUrl, resolved.songUrl != null ? resolved.songUrl : rawUrl);
        songName = resolved.songName != null && !resolved.songName.isBlank()
                ? resolved.songName
                : original.songName;
        durationSeconds = Math.max(1, resolved.songTime);
        startedGameTime = serverLevel.getGameTime();
        savedElapsedSeconds = 0;
        savedElapsedTicks = 0L;
        seekGeneration = 0;
        playing = true;
        syncedPlayers.clear();
        markDirty();
        syncNearbyPlayers(serverLevel, durationSeconds);
    }

    private void pollRedstoneSignal(ServerLevel serverLevel) {
        boolean powered = serverLevel.hasNeighborSignal(worldPosition);
        boolean stateChanged = !redstoneStateInitialized || powered != redstonePowered;
        boolean risingEdge = redstoneStateInitialized && powered && !redstonePowered;
        redstonePowered = powered;
        redstoneStateInitialized = true;
        if (redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE) {
            if (risingEdge) {
                togglePlaybackFromPulse(serverLevel);
            }
        } else if (stateChanged) {
            applyRedstoneMode(serverLevel);
        }
        if (lastComparatorOutput < 0) {
            lastComparatorOutput = getComparatorOutput();
        }
    }

    private void applyRedstoneMode(ServerLevel serverLevel) {
        if (!redstoneStateInitialized || redstoneMode == TurntableRedstoneMode.IGNORE
                || redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE || !hasDisc()) {
            return;
        }
        if (redstoneMode.shouldPlay(redstonePowered)) {
            if (!playing && !resolvingPlayback) {
                if (hasPlaybackData()) {
                    resumePlaybackAutomatically(serverLevel);
                } else {
                    startFromDisc();
                }
            }
        } else if (playing) {
            pausePlayback(serverLevel);
        }
    }

    public void cycleRedstoneMode(ServerLevel serverLevel) {
        redstoneMode = redstoneMode.next();
        redstonePowered = serverLevel.hasNeighborSignal(worldPosition);
        redstoneStateInitialized = true;
        pulsePlaybackRequested = redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE && playing;
        if (redstoneMode == TurntableRedstoneMode.IGNORE) {
            if (hasDisc() && !playing && !resolvingPlayback) {
                if (hasPlaybackData()) {
                    resumePlaybackAutomatically(serverLevel);
                } else {
                    startFromDisc();
                }
            }
        } else {
            applyRedstoneMode(serverLevel);
        }
        markDirty();
    }

    public void cycleExtractionMode() {
        extractionMode = extractionMode.next();
        markDirty();
    }

    public void setVolumePerMille(int value) {
        volumePerMille = Math.max(0, Math.min(1000, value));
        markDirty();
    }

    private boolean canAutomationExtract() {
        return extractionMode == TurntableExtractionMode.ALWAYS || playbackCompleted;
    }

    private void togglePlaybackFromPulse(ServerLevel serverLevel) {
        if (!hasDisc()) {
            pulsePlaybackRequested = false;
            return;
        }
        pulsePlaybackRequested = !pulsePlaybackRequested;
        if (!pulsePlaybackRequested) {
            if (playing) {
                pausePlayback(serverLevel);
            }
            return;
        }
        if (playing || resolvingPlayback) {
            return;
        }
        if (hasPlaybackData()) {
            resumePlaybackAutomatically(serverLevel);
        } else {
            startFromDisc();
        }
    }

    public void pauseFromControl(ServerLevel serverLevel) {
        if (redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE) {
            pulsePlaybackRequested = false;
            pausePlayback(serverLevel);
        } else if (redstoneMode == TurntableRedstoneMode.IGNORE || !redstoneAllowsPlayback(serverLevel)) {
            pausePlayback(serverLevel);
        }
    }

    private boolean redstoneAllowsPlayback(ServerLevel serverLevel) {
        return redstoneMode == TurntableRedstoneMode.IGNORE
                || (redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE
                        ? pulsePlaybackRequested
                        : redstoneMode.shouldPlay(serverLevel.hasNeighborSignal(worldPosition)));
    }

    private void resumePlaybackAutomatically(ServerLevel serverLevel) {
        if (!isPlaybackAllowed(serverLevel, rawUrl, null)) {
            return;
        }
        long elapsedTicks = saveElapsedTicks(storedElapsedTicks());
        if (isStoredBiliSelection(rawUrl)) {
            resolvingPlayback = true;
            resolveAndResume(serverLevel);
        } else {
            playing = true;
            startedGameTime = serverLevel.getGameTime() - elapsedTicks;
            syncedPlayers.clear();
            markDirty();
            syncNearbyPlayers(serverLevel, remainingSeconds(serverLevel.getGameTime()));
        }
    }

    private void updateComparatorOutput() {
        if (level == null || level.isClientSide()) {
            return;
        }
        int output = getComparatorOutput();
        if (output != lastComparatorOutput) {
            lastComparatorOutput = output;
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
    }

    private static String playbackUrlForStorage(String rawUrl, String resolvedUrl) {
        return isStoredBiliSelection(rawUrl) ? rawUrl : (resolvedUrl != null ? resolvedUrl : "");
    }

    public void stopPlayback() {
        if (!playing && playUrl.isBlank()) {
            return;
        }
        stopPlaybackWithoutBlockUpdate();
        markDirty();
    }

    private void stopPlaybackWithoutBlockUpdate() {
        playing = false;
        resolvingPlayback = false;
        pulsePlaybackRequested = false;
        rawUrl = "";
        playUrl = "";
        songName = "";
        durationSeconds = 0;
        startedGameTime = 0L;
        savedElapsedSeconds = 0;
        savedElapsedTicks = 0L;
        seekGeneration = 0;
        syncedPlayers.clear();
        playbackOwnerId = null;
    }

    public void replayFromBeginning(ServerPlayer player) {
        if (!(level instanceof ServerLevel) || disc.isEmpty()) {
            return;
        }
        if (redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE) {
            pulsePlaybackRequested = true;
        }
        if (!redstoneAllowsPlayback((ServerLevel) level)) {
            return;
        }
        playbackCompleted = false;
        playing = false;
        startedGameTime = 0L;
        savedElapsedSeconds = 0;
        savedElapsedTicks = 0L;
        syncedPlayers.clear();
        markDirty();
        startFromDisc(player);
    }

    public void toggleRepeatOne() {
        repeatOne = !repeatOne;
        markDirty();
    }

    private void restartForRepeat(ServerLevel serverLevel) {
        playbackCompleted = false;
        startedGameTime = serverLevel.getGameTime();
        savedElapsedSeconds = 0;
        savedElapsedTicks = 0L;
        seekGeneration++;
        syncedPlayers.clear();
        markDirty();
        syncNearbyPlayers(serverLevel, durationSeconds);
    }

    public void pausePlayback(ServerLevel serverLevel) {
        if (!playing) {
            return;
        }
        snapshotElapsedTicks(serverLevel.getGameTime());
        playing = false;
        startedGameTime = 0L;
        syncedPlayers.clear();
        markDirty();
    }

    public void resumePlayback(ServerPlayer player) {
        resumePlayback(player, -1L);
    }

    public void resumePlayback(ServerPlayer player, long targetMillis) {
        if (!(level instanceof ServerLevel serverLevel) || playing) {
            return;
        }
        if (redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE) {
            pulsePlaybackRequested = true;
        }
        if (!redstoneAllowsPlayback(serverLevel)) {
            return;
        }
        playbackCompleted = false;
        if (!hasPlaybackData()) {
            startFromDisc(player);
            return;
        }
        playbackOwnerId = player.getUUID();
        if (!isPlaybackAllowed(serverLevel, rawUrl, player)) {
            return;
        }
        long elapsedTicks = targetMillis >= 0L
                ? saveElapsedTicks(Math.round(Math.max(0L, targetMillis) / 50.0D))
                : saveElapsedTicks(storedElapsedTicks());
        if (isStoredBiliSelection(rawUrl)) {
            playing = false;
            syncedPlayers.clear();
            markDirty();
            resolveAndResume(serverLevel);
        } else {
            playing = true;
            startedGameTime = serverLevel.getGameTime() - elapsedTicks;
            syncedPlayers.clear();
            markDirty();
            syncNearbyPlayers(serverLevel, remainingSeconds(serverLevel.getGameTime()));
        }
    }

    public void seekTo(ServerLevel serverLevel, long targetMillis) {
        if (!hasPlaybackData() || durationSeconds <= 0) {
            return;
        }
        long targetTicks = clampElapsedTicks(Math.round(Math.max(0L, targetMillis) / 50.0D));
        saveElapsedTicks(targetTicks);
        if (playing) {
            startedGameTime = serverLevel.getGameTime() - targetTicks;
            seekGeneration++;
            syncedPlayers.clear();
            markDirty();
            syncNearbyPlayers(serverLevel, remainingSeconds(serverLevel.getGameTime()));
        } else {
            markDirty();
        }
    }

    private long clampElapsedTicks(long ticks) {
        long maxTicks = Math.max(0L, (long) durationSeconds * 20L - 1L);
        return Math.max(0L, Math.min(maxTicks, ticks));
    }

    private long storedElapsedTicks() {
        return savedElapsedTicks > 0L ? savedElapsedTicks : (long) savedElapsedSeconds * 20L;
    }

    private long saveElapsedTicks(long elapsedTicks) {
        long clamped = clampElapsedTicks(elapsedTicks);
        savedElapsedTicks = clamped;
        savedElapsedSeconds = (int) (clamped / 20L);
        return clamped;
    }

    private long snapshotElapsedTicks(long gameTime) {
        if (playing) {
            long liveElapsedTicks = Math.max(0L, gameTime - startedGameTime);
            return saveElapsedTicks(Math.max(storedElapsedTicks(), liveElapsedTicks));
        }
        return saveElapsedTicks(storedElapsedTicks());
    }

    private static boolean isStoredBiliSelection(String value) {
        return value != null && (value.startsWith("BV") || value.startsWith("bv")
                || value.startsWith("av") || value.startsWith("AV")) && value.contains("|p=");
    }

    private static String normalizeSessionId(String sessionId) {
        return sessionId != null ? sessionId : "";
    }

    private void syncNearbyPlayers(ServerLevel serverLevel, int remainingSeconds) {
        if (playUrl.isBlank()) {
            return;
        }
        if (!isPlaybackAllowed(serverLevel, rawUrl.isBlank() ? playUrl : rawUrl, null)) {
            stopPlayback();
            return;
        }
        // 剩余秒数 <= 0 时不跳过同步：仍需要通知客户端歌曲已结束并停止播放
        // 传入 Math.max(1, remainingSeconds) 保证客户端 MusicToClientMessage 收到
        // 有效的 timeSecond 值，触发 ModernTurntableSound 的结束逻辑

        AABB range = new AABB(worldPosition).inflate(SYNC_RANGE);
        Set<UUID> nearby = new HashSet<>();
        long elapsedMillis = elapsedMillis(serverLevel.getGameTime());
        String syncedPlayUrl = PlaybackSync.withSync(playUrl, playbackSessionId(), elapsedMillis,
                durationSeconds * 1000L);
        var hostMinecart = MinecartTurntableCompat.hostMinecart(level);
        if (hostMinecart != null) {
            syncedPlayUrl = PlaybackSync.withMinecartAnchor(syncedPlayUrl, hostMinecart.getId(),
                    hostMinecart.getUUID());
        }
        for (ServerPlayer player : serverLevel.getEntitiesOfClass(ServerPlayer.class, range)) {
            UUID id = player.getUUID();
            nearby.add(id);
            syncPlayer(player, remainingSeconds, syncedPlayUrl);
        }
        syncedPlayers.retainAll(nearby);
    }

    public static void syncLoadedTurntablesToSpectators(ServerLevel serverLevel) {
        ModernTurntableBlockEntity[] loaded;
        synchronized (LOADED_SERVER_TURNTABLES) {
            loaded = LOADED_SERVER_TURNTABLES.toArray(ModernTurntableBlockEntity[]::new);
        }
        for (ModernTurntableBlockEntity turntable : loaded) {
            if (turntable.isRemoved() || turntable.level != serverLevel) {
                continue;
            }
            turntable.syncNearbySpectators(serverLevel);
        }
    }

    public static void clearLoadedServerTurntables() {
        LOADED_SERVER_TURNTABLES.clear();
    }

    private void syncNearbySpectators(ServerLevel serverLevel) {
        if (!playing || playUrl.isBlank()) {
            return;
        }
        int remaining = remainingSeconds(serverLevel.getGameTime());
        if (remaining <= 0) {
            return;
        }
        AABB range = new AABB(worldPosition).inflate(SYNC_RANGE);
        Set<UUID> nearbySpectators = new HashSet<>();
        for (ServerPlayer player : serverLevel.players()) {
            if (player.isSpectator() && range.contains(player.position())) {
                nearbySpectators.add(player.getUUID());
                syncPlayer(serverLevel, player, remaining);
            }
        }
        syncedPlayers.removeIf(id -> {
            ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(id);
            return player != null && player.isSpectator() && !nearbySpectators.contains(id);
        });
    }

    private void syncPlayer(ServerLevel serverLevel, ServerPlayer player, int remainingSeconds) {
        if (!syncedPlayers.add(player.getUUID())) {
            return;
        }
        long elapsedMillis = elapsedMillis(serverLevel.getGameTime());
        String syncedPlayUrl = PlaybackSync.withSync(playUrl, playbackSessionId(), elapsedMillis,
                durationSeconds * 1000L);
        var hostMinecart = MinecartTurntableCompat.hostMinecart(level);
        if (hostMinecart != null) {
            syncedPlayUrl = PlaybackSync.withMinecartAnchor(syncedPlayUrl, hostMinecart.getId(),
                    hostMinecart.getUUID());
        }
        syncPlayer(player, remainingSeconds, syncedPlayUrl);
    }

    private void syncPlayer(ServerPlayer player, int remainingSeconds, String syncedPlayUrl) {
        @SuppressWarnings("null")
        MusicToClientMessage message = new MusicToClientMessage(
                worldPosition,
                syncedPlayUrl,
                rawUrl.isBlank() ? playUrl : rawUrl,
                Math.max(1, remainingSeconds),
                songName);
        NetworkHandler.sendToClientPlayer(message, player);
    }

    private void recordAudit(ServerLevel serverLevel) {
        PlaybackAuditManager.recordModernTurntable(serverLevel, worldPosition, songName,
                rawUrl.isBlank() ? playUrl : rawUrl, durationSeconds,
                getPlaybackElapsedMillis(serverLevel.getGameTime()), playbackOwnerId);
    }

    private boolean isPlaybackAllowed(ServerLevel serverLevel, String sourceUrl, ServerPlayer actor) {
        if (BiliSongInfoSanitizer.isForbiddenBiliDirectUrl(sourceUrl)) {
            if (actor != null) {
                actor.sendSystemMessage(BiliWhitelistManager.denialMessage(actor, sourceUrl, "播放"));
            }
            return false;
        }
        if (!BiliWhitelistManager.enabled() || BiliWhitelistManager.canonicalResource(sourceUrl).isEmpty()) {
            return true;
        }
        if (BiliWhitelistManager.isAllowed(serverLevel.getServer(), sourceUrl)) {
            return true;
        }
        if (actor != null) {
            actor.sendSystemMessage(BiliWhitelistManager.denialMessage(actor, sourceUrl, "播放"));
        }
        return false;
    }

    private String playbackSessionId() {
        UUID hostUuid = MinecartTurntableCompat.hostUuid(level);
        String sourceId = hostUuid != null ? "minecart-" + hostUuid : Long.toString(worldPosition.asLong());
        return sourceId + "-" + Long.toString(startedGameTime)
                + (seekGeneration > 0 ? "-" + seekGeneration : "");
    }

    private int remainingSeconds(long gameTime) {
        if (!playing || durationSeconds <= 0) {
            return 0;
        }
        return Math.max(0, durationSeconds - elapsedSeconds(gameTime));
    }

    private long elapsedMillis(long gameTime) {
        if (!playing) {
            return 0L;
        }
        // 与 getPlaybackElapsedMillis 对齐：超过歌曲总时长的已播放时间视为已结束
        return Math.min(durationSeconds * 1000L, Math.max(0L, (gameTime - startedGameTime) * 50L));
    }

    private int elapsedSeconds(long gameTime) {
        if (!playing) {
            return 0;
        }
        long elapsed = Math.max(0L, (gameTime - startedGameTime) / 20L);
        // 与 getPlaybackElapsedMillis 对齐：已播放秒数不应超过歌曲总时长
        return Math.min(durationSeconds, (int) Math.min(Integer.MAX_VALUE, elapsed));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store(DISC_TAG, ItemStack.OPTIONAL_CODEC, disc);
        output.putBoolean(PLAYING_TAG, playing);
        output.putString(RAW_URL_TAG, rawUrl);
        output.putString(PLAY_URL_TAG, playUrl);
        output.putString(SONG_NAME_TAG, songName);
        output.putInt(DURATION_TAG, durationSeconds);
        output.putLong(STARTED_TIME_TAG, startedGameTime);
        long elapsedTicks = level instanceof ServerLevel sl
                ? snapshotElapsedTicks(sl.getGameTime())
                : saveElapsedTicks(storedElapsedTicks());
        output.putInt(ELAPSED_SECONDS_TAG, (int) (elapsedTicks / 20L));
        output.putLong(ELAPSED_TICKS_TAG, elapsedTicks);
        output.putInt(SEEK_GENERATION_TAG, seekGeneration);
        if (playbackOwnerId != null) {
            output.putString(OWNER_TAG, playbackOwnerId.toString());
        }
        output.putBoolean(REPEAT_ONE_TAG, repeatOne);
        output.putString(REDSTONE_MODE_TAG, redstoneMode.serializedName());
        output.putString(EXTRACTION_MODE_TAG, extractionMode.serializedName());
        output.putBoolean(PLAYBACK_COMPLETED_TAG, playbackCompleted);
        output.putInt(VOLUME_PER_MILLE_TAG, volumePerMille);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        disc = BiliSongInfoSanitizer
                .sanitizeDisc(input.read(DISC_TAG, ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        playing = input.getBooleanOr(PLAYING_TAG, false);
        rawUrl = input.getStringOr(RAW_URL_TAG, "");
        playUrl = input.getStringOr(PLAY_URL_TAG, "");
        songName = input.getStringOr(SONG_NAME_TAG, "");
        durationSeconds = input.getIntOr(DURATION_TAG, 0);
        startedGameTime = input.getLongOr(STARTED_TIME_TAG, 0L);
        savedElapsedSeconds = input.getIntOr(ELAPSED_SECONDS_TAG, 0);
        savedElapsedTicks = input.getLongOr(ELAPSED_TICKS_TAG, (long) savedElapsedSeconds * 20L);
        seekGeneration = Math.max(0, input.getIntOr(SEEK_GENERATION_TAG, 0));
        playbackOwnerId = parseUuid(input.getStringOr(OWNER_TAG, ""));
        repeatOne = input.getBooleanOr(REPEAT_ONE_TAG, false);
        redstoneMode = TurntableRedstoneMode.byName(input.getStringOr(REDSTONE_MODE_TAG, "ignore"));
        extractionMode = TurntableExtractionMode.byName(
                input.getStringOr(EXTRACTION_MODE_TAG, "after_playback"));
        playbackCompleted = input.getBooleanOr(PLAYBACK_COMPLETED_TAG, false);
        volumePerMille = Math.max(0, Math.min(1000, input.getIntOr(VOLUME_PER_MILLE_TAG, 1000)));
        redstoneStateInitialized = false;
        pulsePlaybackRequested = redstoneMode == TurntableRedstoneMode.PULSE_TOGGLE && playing;
        saveElapsedTicks(savedElapsedTicks);
        syncedPlayers.clear();
        needsResolveOnLoad = playing && durationSeconds > 0 && savedElapsedTicks < (long) durationSeconds * 20L;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public void markDirty() {
        setChanged();
        if (level != null) {
            BlockState current = level.getBlockState(worldPosition);
            if (current.getBlock() instanceof ModernTurntableBlock
                    && (current.getValue(ModernTurntableBlock.HAS_DISC) != hasDisc()
                            || current.getValue(ModernTurntableBlock.PLAYING) != isPlaying())) {
                level.setBlock(worldPosition, current
                        .setValue(ModernTurntableBlock.HAS_DISC, hasDisc())
                        .setValue(ModernTurntableBlock.PLAYING, isPlaying()), 3);
            }
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
            updateComparatorOutput();
        }
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
