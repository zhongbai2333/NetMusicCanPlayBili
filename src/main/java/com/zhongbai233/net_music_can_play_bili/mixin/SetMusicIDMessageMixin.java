package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.network.message.SetMusicIDMessage;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

/** 服务端兜底：启用白名单后拒绝创建不在白名单内的 BV/第三方链接唱片。 */
@Mixin(SetMusicIDMessage.class)
public abstract class SetMusicIDMessageMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private static void net_music_can_play_bili$guardBiliWhitelist(SetMusicIDMessage message,
            IPayloadContext context, CallbackInfo ci) {
        if (message == null || context == null || !context.flow().isServerbound()) {
            return;
        }
        ItemMusicCD.SongInfo song = message.song();
        String songUrl = song == null ? null : song.songUrl;
        ItemMusicCD.SongInfo normalized = BiliSongInfoSanitizer.sanitize(song);
        if (normalized != song) {
            SetMusicIDMessage.handle(new SetMusicIDMessage(Objects.requireNonNull(normalized)), context);
            ci.cancel();
            return;
        }
        songUrl = normalized == null ? songUrl : normalized.songUrl;
        boolean forbiddenBiliDirectUrl = BiliSongInfoSanitizer.isForbiddenBiliDirectUrl(songUrl);
        if (!forbiddenBiliDirectUrl && (!BiliWhitelistManager.enabled()
                || BiliWhitelistManager.canonicalResource(songUrl).isEmpty())) {
            return;
        }
        if (!(context.player() instanceof ServerPlayer player)) {
            ci.cancel();
            return;
        }
        if (forbiddenBiliDirectUrl) {
            player.sendSystemMessage(BiliWhitelistManager.denialMessage(player, songUrl, "创建唱片/音源头"));
            ci.cancel();
            return;
        }
        if (BiliWhitelistManager.isAllowed(player.level().getServer(), songUrl)) {
            return;
        }

        player.sendSystemMessage(BiliWhitelistManager.denialMessage(player, songUrl, "创建唱片/音源头"));
        ci.cancel();
    }

}