package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;
import java.util.function.Consumer;

/** Shared cleanup helpers for client media playback sessions. */
public final class ClientMediaPlaybackSessions {
    private ClientMediaPlaybackSessions() {
    }

    public static void stop(UUID deviceId, Consumer<UUID> carrierStopHook) {
        if (deviceId == null) {
            return;
        }
        ClientMediaPlaybackRegistry.remove(deviceId);
        ClientMediaSoundRegistry.remove(deviceId);
        ClientMediaStreamRecovery.removeForDevice(deviceId);
        ClientMediaPrepareTracker.removeForDevice(deviceId);
        if (carrierStopHook != null) {
            carrierStopHook.accept(deviceId);
        }
    }

    public static void clearAll(Runnable carrierClearHook) {
        ClientMediaPlaybackRegistry.clear();
        ClientMediaSoundRegistry.clear();
        ClientMediaStreamRecovery.clear();
        ClientMediaPrepareTracker.clear();
        if (carrierClearHook != null) {
            carrierClearHook.run();
        }
    }
}