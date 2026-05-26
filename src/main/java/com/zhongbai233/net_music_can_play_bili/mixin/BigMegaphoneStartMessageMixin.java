package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.client.audio.BigMegaphoneClientManager;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveAudioResolver;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 拦截大喇叭播放 URL
 */
@Mixin(BigMegaphoneClientManager.class)
public class BigMegaphoneStartMessageMixin {

    @ModifyArg(method = "handleStart", at = @At(value = "INVOKE", target = "Lcom/github/tartaricacid/netmusic/client/audio/BigMegaphoneClientManager$TrackedBroadcast;<init>(Lnet/minecraft/core/BlockPos;JLjava/lang/String;Ljava/lang/String;I)V"), index = 2, remap = false)
    private static String net_music_can_play_bili$resolveUrl(BlockPos pos, long sessionId, String url, String name,
            int range) {
        if (url.startsWith("http://live/") && url.endsWith(".m3u8")) {
            String roomId = url.substring("http://live/".length(), url.length() - ".m3u8".length());
            try {
                return BiliLiveAudioResolver.resolveM3u8Url(roomId);
            } catch (Exception ignored) {
            }
        }
        return url;
    }
}
