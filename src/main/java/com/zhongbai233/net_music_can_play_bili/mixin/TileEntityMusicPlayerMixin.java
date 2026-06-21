package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 服务端兜底：启用白名单后拒绝 NetMusic 原版唱片机解析/播放不在白名单内的音源。 */
@Mixin(value = TileEntityMusicPlayer.class, remap = false)
public abstract class TileEntityMusicPlayerMixin {
    private static final double NET_MUSIC_CAN_PLAY_BILI_NOTIFY_RANGE = 32.0D;

    @Shadow
    public abstract void setPlay(boolean play);

    @Shadow
    public abstract void setCurrentTime(int currentTime);

    @Shadow
    public abstract void markDirty();

    @Inject(method = "setPlayToClient", at = @At("HEAD"), cancellable = true)
    private void net_music_can_play_bili$guardOriginalMusicPlayer(ItemMusicCD.SongInfo song, CallbackInfo ci) {
        String songUrl = song == null ? null : song.songUrl;
        if (!BiliSongInfoSanitizer.isForbiddenBiliDirectUrl(songUrl) && (!BiliWhitelistManager.enabled()
            || BiliWhitelistManager.canonicalResource(songUrl).isEmpty())) {
            return;
        }

        BlockEntity self = (BlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!BiliSongInfoSanitizer.isForbiddenBiliDirectUrl(songUrl)
            && BiliWhitelistManager.isAllowed(serverLevel.getServer(), songUrl)) {
            return;
        }

        this.setPlay(false);
        this.setCurrentTime(0);
        this.markDirty();
        notifyNearbyPlayers(serverLevel, self.getBlockPos(), songUrl);
        ci.cancel();
    }

    private static void notifyNearbyPlayers(ServerLevel level, BlockPos pos, String songUrl) {
        AABB range = new AABB(pos).inflate(NET_MUSIC_CAN_PLAY_BILI_NOTIFY_RANGE);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, range)) {
            player.sendSystemMessage(BiliWhitelistManager.denialMessage(player, songUrl, "播放"));
        }
    }
}
