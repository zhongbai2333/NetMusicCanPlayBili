package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry.ActivePlayback;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** MP4-specific UI projection of active client media playback state. */
public final class MP4PlaybackUiState {
    private MP4PlaybackUiState() {
    }

    public static void syncFocusedProgress() {
        float progress = progress(localPlayback());
        if (progress >= 0.0F) {
            MP4FocusState.setMediaProgressFromPlayback(progress);
        }
    }

    public static MP4Item.State overlayPlaybackState(UUID deviceId, MP4Item.State baseState) {
        if (baseState == null) {
            baseState = MP4Item.State.DEFAULT;
        }
        ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
        if (active == null) {
            return baseState;
        }
        int progressPerMille = baseState.progressPerMille();
        long durationMillis = active.durationMillis();
        if (durationMillis > 0L) {
            progressPerMille = (int) Math.round(Math.max(0L, Math.min(durationMillis,
                    active.elapsedMillis())) * 1000.0D / durationMillis);
        }
        return new MP4Item.State(true, baseState.shuffle(), baseState.videoEnabled(), baseState.landscape(),
                baseState.qualityIndex(), active.queueIndex(), baseState.queueScrollOffset(),
                Math.round(active.volume() * 1000.0F), baseState.repeatMode(), baseState.playlistOpen(),
                baseState.lyricsEnabled(), baseState.subtitleMode(), baseState.subtitleAiEnabled(),
                Math.max(0, Math.min(1000, progressPerMille)), baseState.rotationHintShown());
    }

    private static ActivePlayback localPlayback() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return null;
        }
        ItemStack stack = MP4FocusState.active()
                ? minecraft.player.getItemInHand(MP4FocusState.hand())
                : MP4Item.findAnyInInventory(minecraft.player);
        UUID deviceId = MP4Item.readDeviceId(stack);
        ActivePlayback active = deviceId != null ? ClientMediaPlaybackRegistry.get(deviceId) : null;
        return active != null ? active : ClientMediaPlaybackRegistry.get(minecraft.player.getUUID());
    }

    private static float progress(ActivePlayback active) {
        if (active == null || active.durationMillis() <= 0L) {
            return -1.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, active.elapsedMillis() / (float) active.durationMillis()));
    }
}