package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;

/** Protocol-neutral view of a synchronized client media playback update. */
public interface ClientMediaSyncPayload {
    int SOURCE_PLAYER = 0;
    int SOURCE_ITEM = 1;
    int SOURCE_BLOCK = 2;
    int SOURCE_CONTAINER_ENTITY = 3;

    UUID ownerId();

    UUID sourceId();

    int sourceType();

    int sourceEntityId();

    double sourceX();

    double sourceY();

    double sourceZ();

    boolean playing();

    int queueIndex();

    String playUrl();

    String rawUrl();

    String songName();

    int durationSeconds();

    int volumePerMille();

    String sessionId();

    long elapsedMillis();

    boolean headphoneRouted();
}