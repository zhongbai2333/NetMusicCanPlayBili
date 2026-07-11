package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;

/** Carrier-specific callbacks for the shared client media sync handler. */
public interface ClientMediaSyncPolicy {
    boolean canHear(UUID sourceId, boolean headphoneRouted);

    void stop(UUID sourceId);

    void updateVolume(UUID sourceId, float volume);

    boolean shouldRebuildSound(UUID sourceId, ClientMediaSyncPayload payload);

    void preparePlayback(ClientMediaSyncPayload payload, UUID sourceId);

    default void onSyncReceived(ClientMediaSyncPayload payload, UUID sourceId) {
    }

    default void onIgnoredCannotHear(ClientMediaSyncPayload payload, UUID sourceId) {
    }

    default void beforeRegisterPlayback(ClientMediaSyncPayload payload, UUID sourceId) {
    }

    default void afterRegisterPlayback(ClientMediaSyncPayload payload, UUID sourceId) {
    }

    default void onRebuildSound(ClientMediaSyncPayload payload, UUID sourceId) {
    }
}