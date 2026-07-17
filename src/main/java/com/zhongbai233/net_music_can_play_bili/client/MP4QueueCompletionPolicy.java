package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** MP4 queue/repeat behavior when a synchronized sound completes. */
final class MP4QueueCompletionPolicy {
    static final MP4QueueCompletionPolicy INSTANCE = new MP4QueueCompletionPolicy();

    private MP4QueueCompletionPolicy() {
    }

    public void onCompleted(UUID deviceId, String sessionId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !ClientMediaPlayback.isCurrent(deviceId, sessionId)) {
            ClientMediaPlaybackRegistry.finishSession(deviceId, sessionId);
            return;
        }
        ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
        ItemStack stack = MP4Item.findByDeviceId(minecraft.player, deviceId);
        if (active == null || !(stack.getItem() instanceof MP4Item)
                || active.sourceLocation().sourceType() != ClientMediaSyncPayload.SOURCE_PLAYER) {
            ClientMediaPlaybackRegistry.finishSession(deviceId, sessionId);
            return;
        }
        int queueSize = MP4FocusState.queueSize();
        if (queueSize <= 0) {
            MP4FocusState.setPlaying(false);
            MP4Client.updateFocusedLocalState();
            sendControl(MP4PlaybackControlPacket.Action.STOP, 0L);
            return;
        }
        if (MP4FocusState.repeatMode() == 1) {
            MP4FocusState.setMediaProgress(0.0F);
            MP4Client.updateFocusedLocalState();
            sendControl(MP4PlaybackControlPacket.Action.RESTART, 0L);
            return;
        }
        if (MP4FocusState.selectedQueueIndex() < queueSize - 1) {
            MP4FocusState.nextTrack();
            MP4Client.updateFocusedLocalState();
            sendControl(MP4PlaybackControlPacket.Action.RESTART, 0L);
        } else if (MP4FocusState.repeatMode() == 2) {
            MP4FocusState.selectQueueIndexForPlayback(0);
            MP4Client.updateFocusedLocalState();
            sendControl(MP4PlaybackControlPacket.Action.RESTART, 0L);
        } else {
            MP4FocusState.setPlaying(false);
            MP4FocusState.setMediaProgress(0.0F);
            MP4Client.updateFocusedLocalState();
            sendControl(MP4PlaybackControlPacket.Action.STOP, 0L);
        }
    }

    private static void sendControl(MP4PlaybackControlPacket.Action action, long targetMillis) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        ItemStack stack = minecraft.player != null
                ? (MP4FocusState.active()
                        ? minecraft.player.getItemInHand(MP4FocusState.hand())
                        : MP4Item.findAnyInInventory(minecraft.player))
                : ItemStack.EMPTY;
        if (stack.isEmpty()) {
            return;
        }
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId == null) {
            minecraft.getConnection().send(new com.zhongbai233.net_music_can_play_bili.network.MP4EnsureDeviceIdPacket(
                    MP4FocusState.active() ? MP4FocusState.hand() : net.minecraft.world.InteractionHand.MAIN_HAND));
            return;
        }
        minecraft.getConnection().send(new MP4PlaybackControlPacket(action, MP4FocusState.selectedQueueIndex(),
                Math.round(MP4FocusState.volume() * 1000.0F), Math.max(0L, targetMillis), deviceId));
    }
}