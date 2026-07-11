package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;

/**
 * Protocol-neutral view of a lightweight synchronized client media timeline
 * update.
 */
public interface ClientMediaTimelinePayload {
    UUID sourceId();

    String sessionId();

    long elapsedMillis();

    int volumePerMille();

    boolean headphoneRouted();
}