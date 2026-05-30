package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.network.NetworkHandler;
import com.github.tartaricacid.netmusic.network.message.MusicToClientMessage;
import com.mojang.logging.LogUtils;
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

    public ModernTurntableBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.MODERN_TURNTABLE.get(), pos, blockState);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ModernTurntableBlockEntity turntable) {
        if (!(level instanceof ServerLevel serverLevel) || !turntable.playing) {
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

    public boolean hasDisc() {
        return !disc.isEmpty();
    }

    public ItemStack getDisc() {
        return disc;
    }

    public boolean isPlaying() {
        return playing;
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
        syncedPlayers.clear();
        markDirty();
    }

    private void syncNearbyPlayers(ServerLevel serverLevel, int remainingSeconds) {
        if (playUrl.isBlank() || remainingSeconds <= 0) {
            return;
        }

        AABB range = new AABB(worldPosition).inflate(SYNC_RANGE);
        Set<UUID> nearby = new HashSet<>();
        for (ServerPlayer player : serverLevel.getEntitiesOfClass(ServerPlayer.class, range)) {
            UUID id = player.getUUID();
            nearby.add(id);
            if (syncedPlayers.add(id)) {
                @SuppressWarnings("null")
                MusicToClientMessage message = new MusicToClientMessage(
                        worldPosition,
                        playUrl,
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

    private int remainingSeconds(long gameTime) {
        if (!playing || durationSeconds <= 0) {
            return 0;
        }
        long elapsedSeconds = Math.max(0L, (gameTime - startedGameTime) / 20L);
        return Math.max(0, durationSeconds - (int) elapsedSeconds);
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
        syncedPlayers.clear();
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
