package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared client-side audio routing rules for synchronized media devices. */
public final class ClientMediaAudioRouting {
    private static final Set<UUID> LOCAL_PRIVATE_SOURCES = ConcurrentHashMap.newKeySet();

    private ClientMediaAudioRouting() {
    }

    public static boolean canHear(UUID deviceId, boolean headphoneRouted) {
        if (deviceId != null && LOCAL_PRIVATE_SOURCES.contains(deviceId)) {
            return true;
        }
        if (!HeadphoneClientState.equipped()) {
            return !headphoneRouted;
        }
        return headphoneRouted && HeadphoneClientState.handlesMediaDevice(deviceId);
    }

    public static void registerLocalPrivateSource(UUID sourceId) {
        if (sourceId != null) {
            LOCAL_PRIVATE_SOURCES.add(sourceId);
        }
    }

    public static void unregisterLocalPrivateSource(UUID sourceId) {
        if (sourceId != null) {
            LOCAL_PRIVATE_SOURCES.remove(sourceId);
        }
    }

    public static void clearLocalPrivateSources() {
        LOCAL_PRIVATE_SOURCES.clear();
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