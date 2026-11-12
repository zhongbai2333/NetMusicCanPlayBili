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
import java.util.UUID;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import com.zhongbai233.net_music_can_play_bili.server.DeferredRequestGate;

/** 服务端兜底：启用白名单后拒绝创建不在白名单内的 BV/第三方链接唱片。 */
@Mixin(SetMusicIDMessage.class)
public abstract class SetMusicIDMessageMixin {
    private static final DeferredRequestGate<UUID> NCPB_REQUESTS =
            com.zhongbai233.net_music_can_play_bili.server.DeferredDiscRequests.requests();

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private static void net_music_can_play_bili$guardBiliWhitelist(SetMusicIDMessage message,
            IPayloadContext context, CallbackInfo ci) {
        if (message == null || context == null || !context.flow().isServerbound()) {
            return;
        }
        ServerPlayer requester = context.player() instanceof ServerPlayer p ? p : null;
        long requestTicket = requester == null ? 0L : NCPB_REQUESTS.begin(requester.getUUID());
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
        if (BiliWhitelistManager.isShortLink(songUrl) && requester != null) {
            var server = requester.level().getServer();
            var menu = requester.containerMenu;
            List<ItemStack> slots = menu.getItems().stream().map(ItemStack::copy).toList();
            ItemMusicCD.SongInfo frozen = normalized.clone();
            BiliWhitelistManager.canonicalResourceAsync(songUrl).whenCompleteAsync((canonical, error) -> {
                if (!NCPB_REQUESTS.current(requester.getUUID(), requestTicket)
                        || !BiliWhitelistManager.acceptsServer(server)
                        || server.getPlayerList().getPlayer(requester.getUUID()) != requester
                        || requester.containerMenu != menu || menu.getItems().size() != slots.size()) return;
                for (int i = 0; i < slots.size(); i++) {
                    if (!ItemStack.matches(slots.get(i), menu.getItems().get(i))) return;
                }
                NCPB_REQUESTS.complete(requester.getUUID(), requestTicket);
                if (error != null || canonical == null || canonical.isEmpty()
                        || (BiliWhitelistManager.enabled()
                        && !BiliWhitelistManager.isAllowedCanonical(server, canonical.get().key()))) {
                    requester.sendSystemMessage(BiliWhitelistManager.denialMessage(requester, frozen.songUrl,
                            "创建唱片/音源头"));
                    return;
                }
                frozen.songUrl = canonical.get().id();
                SetMusicIDMessage.handle(new SetMusicIDMessage(frozen), context);
            }, server);
            ci.cancel();
            return;
        }
        if (!forbiddenBiliDirectUrl && (!BiliWhitelistManager.enabled()
                || (BiliWhitelistManager.canonicalResource(songUrl).isEmpty()
                && !BiliWhitelistManager.isShortLink(songUrl)))) {
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