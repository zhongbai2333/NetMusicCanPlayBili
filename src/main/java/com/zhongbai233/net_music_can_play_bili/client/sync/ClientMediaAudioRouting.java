package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Shared client-side audio routing rules for synchronized media devices. */
public final class ClientMediaAudioRouting {
    private ClientMediaAudioRouting() {
    }

    public static boolean canHear(UUID deviceId, boolean headphoneRouted) {
        if (!HeadphoneClientState.equipped()) {
            return !headphoneRouted;
        }
        return headphoneRouted && HeadphoneClientState.handlesMediaDevice(deviceId);
    }

    public static Vec3 localHeadPosition() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return Vec3.ZERO;
        }
        return minecraft.player.position().add(0.0D, minecraft.player.getEyeHeight(), 0.0D);
    }

    public static Vec3 audiblePosition(UUID deviceId, boolean headphoneRouted) {
        Vec3 pos = headphoneRouted ? localHeadPosition() : ClientMediaPlayback.sourcePosition(deviceId);
        if (pos != null) {
            return pos;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
    }
}