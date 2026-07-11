package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.PadClient;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

record PadGuiViewState(UUID deviceId, PadDocument document, PadMapSnapshot map, float playerX, float playerZ,
    float playerYaw, int ticks, long focusRevision, boolean hasVideoFrame, boolean transparentVideoOverlay,
    boolean controlsVisible, float mediaProgress, boolean qualityMenuOpen, boolean subtitleMenuOpen,
    String qualityLabel, boolean subtitlesEnabled, boolean subtitlePrimaryMode, boolean subtitleAiEnabled) {
    static PadGuiViewState capture(UUID deviceId) {
        return capture(deviceId, 1.0F);
    }

    static PadGuiViewState capture(UUID deviceId, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack stack = itemStack(minecraft, deviceId);
        PadDocument document = stack.getItem() instanceof PadItem ? PadClient.cachedDocumentFor(stack)
            : PadDocument.DEFAULT;
        float clampedPartial = Math.max(0.0F, Math.min(1.0F, partialTick));
        float playerX = minecraft.player != null
                ? (float) (minecraft.player.xo + (minecraft.player.getX() - minecraft.player.xo) * clampedPartial)
                : 0.0F;
        float playerZ = minecraft.player != null
                ? (float) (minecraft.player.zo + (minecraft.player.getZ() - minecraft.player.zo) * clampedPartial)
                : 0.0F;
        float playerYaw = minecraft.player != null ? minecraft.player.getYRot() : 0.0F;
        int ticks = minecraft.player != null ? minecraft.player.tickCount : 0;
        int mapCenterX = minecraft.player != null ? minecraft.player.blockPosition().getX() : 0;
        int mapCenterZ = minecraft.player != null ? minecraft.player.blockPosition().getZ() : 0;
        PadMapSnapshot map = PadMapClientCache.snapshot(mapCenterX, mapCenterZ);
        boolean hasPlayback = deviceId != null && ClientMediaPlayback.hasPlayback(deviceId);
        boolean hasVideoFrame = hasPlayback
            && MP4HandheldVideoClient.latestFrame(deviceId) != null;
        boolean pausedOverlay = document.locked() && PadFocusState.pausedPlaybackAvailable()
            && PadFocusState.pausedVideo()
            && PadFocusState.controlsVisible();
        boolean transparentVideoOverlay = document.locked() && (hasVideoFrame || pausedOverlay);
        long elapsedMillis = deviceId != null ? ClientMediaPlayback.elapsedMillis(deviceId) : 0L;
        long durationMillis = deviceId != null ? ClientMediaPlayback.durationMillis(deviceId) : 0L;
        if (!hasPlayback && PadFocusState.pausedPlaybackAvailable()) {
            elapsedMillis = PadFocusState.pausedElapsedMillis();
            durationMillis = PadFocusState.pausedDurationMillis();
        }
        float playbackProgress = durationMillis > 0L
            ? Math.max(0.0F, Math.min(1.0F, elapsedMillis / (float) durationMillis))
            : 0.0F;
        float mediaProgress = PadFocusState.mediaProgress() > 0.0F && PadFocusState.controlsVisible()
            ? PadFocusState.mediaProgress()
            : playbackProgress;
        return new PadGuiViewState(deviceId, document, map, playerX, playerZ, playerYaw, ticks,
            PadFocusState.revision(), hasVideoFrame, transparentVideoOverlay, PadFocusState.controlsVisible(),
            mediaProgress, PadFocusState.qualityMenuOpen(), PadFocusState.subtitleMenuOpen(),
            PadFocusState.qualityLabel(), PadFocusState.subtitlesEnabled(), PadFocusState.subtitlePrimaryMode(),
            PadFocusState.subtitleAiEnabled());
    }

    private static ItemStack itemStack(Minecraft minecraft, UUID deviceId) {
        if (minecraft.player == null) {
            return ItemStack.EMPTY;
        }
        ItemStack main = minecraft.player.getMainHandItem();
        if (main.getItem() instanceof PadItem && (deviceId == null || deviceId.equals(PadItem.readDeviceId(main)))) {
            return main;
        }
        ItemStack offhand = minecraft.player.getOffhandItem();
        if (offhand.getItem() instanceof PadItem
                && (deviceId == null || deviceId.equals(PadItem.readDeviceId(offhand)))) {
            return offhand;
        }
        return ItemStack.EMPTY;
    }
}