package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.network.NetworkHandler;
import com.github.tartaricacid.netmusic.network.message.MusicToClientMessage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.block.ModernTurntableBlock;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
    private static final int SYNC_RANGE = 96;
    private static final int SYNC_INTERVAL_TICKS = 20;

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
    private boolean needsResolveOnLoad;
    private transient LyricRecord clientLyricRecord;
    private transient String clientLyricSessionId = "";

    public ModernTurntableBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.MODERN_TURNTABLE.get(), pos, blockState);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ModernTurntableBlockEntity turntable) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // 世界加载后需要重新解析 B站 CDN 直链（旧 URL 可能已过期）
        if (turntable.needsResolveOnLoad && turntable.playing && !turntable.rawUrl.isBlank()) {
            turntable.needsResolveOnLoad = false;
            turntable.resolveAndResume(serverLevel);
            return;
        }
        if (!turntable.playing) {
            return;
        }
        int remaining = turntable.remainingSeconds(serverLevel.getGameTime());
        if (remaining <= 0) {
            turntable.stopPlayback();
            return;
        }
        if (serverLevel.getGameTime() % SYNC_INTERVAL_TICKS == 0) {
            turntable.syncNearbyPlayers(serverLevel, remaining);
        }
    }

    private void resolveAndResume(ServerLevel serverLevel) {
        long elapsedTicks = snapshotElapsedTicks(serverLevel.getGameTime());
        if (!(rawUrl.startsWith("BV") || rawUrl.startsWith("bv") || rawUrl.startsWith("av") || rawUrl.startsWith("AV"))
                || !rawUrl.contains("|p=")) {
            // 非 B站 存储选集的 URL：尝试直接用保存的 URL 恢复
            playing = true;
            startedGameTime = serverLevel.getGameTime() - elapsedTicks;
            syncedPlayers.clear();
            markDirty();
            syncNearbyPlayers(serverLevel, remainingSeconds(serverLevel.getGameTime()));
            LOGGER.info("现代化唱片机恢复播放: {} ({}s, 续播 {}ms)", songName, durationSeconds, elapsedTicks * 50L);
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
                    if (!playing || !Objects.equals(rawUrl, resolved.songUrl != null ? resolved.songUrl : rawUrl)) {
                        String newUrl = resolved.songUrl != null ? resolved.songUrl : playUrl;
                        if (!newUrl.isBlank()) {
                            playUrl = newUrl;
                            playing = true;
                            startedGameTime = serverLevel.getGameTime() - elapsedTicks;
                            durationSeconds = Math.max(1, resolved.songTime > 0 ? resolved.songTime : durationSeconds);
                            syncedPlayers.clear();
                            markDirty();
                            syncNearbyPlayers(serverLevel, remainingSeconds(serverLevel.getGameTime()));
                            LOGGER.info("现代化唱片机恢复播放(B站): {} ({}s, 续播 {}ms)", songName, durationSeconds,
                                    elapsedTicks * 50L);
                        }
                    }
                }, serverLevel.getServer())
                .exceptionally(error -> {
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

    public String getSongName() {
        return songName;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public boolean hasPlaybackData() {
        return durationSeconds > 0 && (!playUrl.isBlank() || !rawUrl.isBlank());
    }

    public LyricRecord getClientLyricRecord() {
        return clientLyricRecord;
    }

    public void setClientLyricRecord(LyricRecord lyricRecord, String sessionId) {
        if (level != null && !level.isClientSide()) {
            return;
        }
        clientLyricRecord = lyricRecord;
        clientLyricSessionId = normalizeSessionId(sessionId);
    }

    public void clearClientLyricRecord(String sessionId) {
        if (level != null && !level.isClientSide()) {
            return;
        }
        String normalized = normalizeSessionId(sessionId);
        if (normalized.isBlank() || clientLyricSessionId.isBlank() || clientLyricSessionId.equals(normalized)) {
            clientLyricRecord = null;
            clientLyricSessionId = "";
        }
    }

    public long getPlaybackElapsedMillis(long gameTime) {
        if (playing && startedGameTime > 0L) {
            return Math.min(durationSeconds * 1000L, Math.max(0L, (gameTime - startedGameTime) * 50L));
        }
        long elapsedTicks = storedElapsedTicks();
        return Math.min(durationSeconds * 1000L, Math.max(0L, elapsedTicks * 50L));
    }

    public void setDisc(ItemStack stack) {
        disc = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        stopPlayback();
        markDirty();
    }

    public ItemStack removeDisc() {
        ItemStack removed = disc;
        disc = ItemStack.EMPTY;
        stopPlayback();
        markDirty();
        return removed;
    }

    public void startFromDisc(ServerPlayer triggerPlayer) {
        if (!(level instanceof ServerLevel serverLevel) || disc.isEmpty()) {
            return;
        }

        @SuppressWarnings("null")
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(disc);
        if (songInfo == null) {
            triggerPlayer.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.modern_turntable.need_cd"));
            return;
        }
        if (songInfo.vip && !MusicPlayResolverManager.canResolve(songInfo)) {
            triggerPlayer.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.modern_turntable.need_vip"));
            return;
        }

        ItemMusicCD.SongInfo original = songInfo.clone();
        MusicPlayResolverManager.resolve(original.clone())
                .thenAcceptAsync(resolved -> applyResolvedPlayback(serverLevel, original, resolved),
                        serverLevel.getServer())
                .exceptionally(error -> {
                    LOGGER.error("现代化唱片机解析播放失败: {}", original.songName, error);
                    return null;
                });
    }

    private void applyResolvedPlayback(ServerLevel serverLevel, ItemMusicCD.SongInfo original,
            ItemMusicCD.SongInfo resolved) {
        @SuppressWarnings("null")
        ItemMusicCD.SongInfo current = ItemMusicCD.getSongInfo(disc);
        if (current == null || !Objects.equals(current.songUrl, original.songUrl)) {
            return;
        }

        rawUrl = original.songUrl != null ? original.songUrl : "";
        playUrl = resolved.songUrl != null ? resolved.songUrl : rawUrl;
        songName = resolved.songName != null && !resolved.songName.isBlank()
                ? resolved.songName
                : original.songName;
        durationSeconds = Math.max(1, resolved.songTime);
        startedGameTime = serverLevel.getGameTime();
        savedElapsedSeconds = 0;
        savedElapsedTicks = 0L;
        playing = true;
        syncedPlayers.clear();
        markDirty();
        syncNearbyPlayers(serverLevel, durationSeconds);
        LOGGER.info("现代化唱片机开始播放: {} ({}s)", songName, durationSeconds);
    }

    public void stopPlayback() {
        if (!playing && playUrl.isBlank()) {
            return;
        }
        playing = false;
        rawUrl = "";
        playUrl = "";
        songName = "";
        durationSeconds = 0;
        startedGameTime = 0L;
        savedElapsedSeconds = 0;
        savedElapsedTicks = 0L;
        syncedPlayers.clear();
        markDirty();
    }

    public void replayFromBeginning(ServerPlayer player) {
        if (!(level instanceof ServerLevel) || disc.isEmpty()) {
            return;
        }
        playing = false;
        startedGameTime = 0L;
        savedElapsedSeconds = 0;
        savedElapsedTicks = 0L;
        syncedPlayers.clear();
        markDirty();
        startFromDisc(player);
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
        if (!(level instanceof ServerLevel serverLevel) || playing) {
            return;
        }
        if (!hasPlaybackData()) {
            startFromDisc(player);
            return;
        }
        long elapsedTicks = saveElapsedTicks(storedElapsedTicks());
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
        if (playing && startedGameTime > 0L) {
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
        if (playUrl.isBlank() || remainingSeconds <= 0) {
            return;
        }

        AABB range = new AABB(worldPosition).inflate(SYNC_RANGE);
        Set<UUID> nearby = new HashSet<>();
        long elapsedMillis = elapsedMillis(serverLevel.getGameTime());
        String syncedPlayUrl = PlaybackSync.withSync(playUrl, playbackSessionId(), elapsedMillis,
                durationSeconds * 1000L);
        for (ServerPlayer player : serverLevel.getEntitiesOfClass(ServerPlayer.class, range)) {
            UUID id = player.getUUID();
            nearby.add(id);
            if (syncedPlayers.add(id)) {
                @SuppressWarnings("null")
                MusicToClientMessage message = new MusicToClientMessage(
                        worldPosition,
                        syncedPlayUrl,
                        rawUrl.isBlank() ? playUrl : rawUrl,
                        remainingSeconds,
                        songName);
                NetworkHandler.sendToClientPlayer(message, player);
                LOGGER.info("现代化唱片机同步播放给玩家: {} -> {} (剩余 {}s)",
                        songName, player.getScoreboardName(), remainingSeconds);
            }
        }
        syncedPlayers.retainAll(nearby);
    }

    private String playbackSessionId() {
        return Long.toString(worldPosition.asLong()) + "-" + Long.toString(startedGameTime);
    }

    private int remainingSeconds(long gameTime) {
        if (!playing || durationSeconds <= 0) {
            return 0;
        }
        return Math.max(0, durationSeconds - elapsedSeconds(gameTime));
    }

    private long elapsedMillis(long gameTime) {
        if (!playing || startedGameTime <= 0L) {
            return 0L;
        }
        return Math.max(0L, (gameTime - startedGameTime) * 50L);
    }

    private int elapsedSeconds(long gameTime) {
        if (!playing || startedGameTime <= 0L) {
            return 0;
        }
        long elapsed = Math.max(0L, (gameTime - startedGameTime) / 20L);
        return (int) Math.min(Integer.MAX_VALUE, elapsed);
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
        LOGGER.trace("现代化唱片机保存: playing={} startedGameTime={} duration={} elapsedTicks={}",
                playing, startedGameTime, durationSeconds, elapsedTicks);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        disc = input.read(DISC_TAG, ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        playing = input.getBooleanOr(PLAYING_TAG, false);
        rawUrl = input.getStringOr(RAW_URL_TAG, "");
        playUrl = input.getStringOr(PLAY_URL_TAG, "");
        songName = input.getStringOr(SONG_NAME_TAG, "");
        durationSeconds = input.getIntOr(DURATION_TAG, 0);
        startedGameTime = input.getLongOr(STARTED_TIME_TAG, 0L);
        savedElapsedSeconds = input.getIntOr(ELAPSED_SECONDS_TAG, 0);
        savedElapsedTicks = input.getLongOr(ELAPSED_TICKS_TAG, (long) savedElapsedSeconds * 20L);
        saveElapsedTicks(savedElapsedTicks);
        syncedPlayers.clear();
        needsResolveOnLoad = playing && durationSeconds > 0 && savedElapsedTicks < (long) durationSeconds * 20L;
        LOGGER.trace("现代化唱片机加载: playing={} startedGameTime={} duration={} elapsedTicks={} needsResolve={}",
                playing, startedGameTime, durationSeconds, savedElapsedTicks, needsResolveOnLoad);
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
        }
    }
}
