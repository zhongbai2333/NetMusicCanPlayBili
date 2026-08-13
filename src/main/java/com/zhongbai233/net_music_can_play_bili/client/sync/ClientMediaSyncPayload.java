package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.UUID;
import java.util.Optional;

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

    /**
     * Typed runtime view of the wire-compatible session field. Packet records
     * keep their string component for codec and binary compatibility; client
     * ownership comparisons should use this value instead of reparsing it.
     */
    default Optional<PlaybackSessionId> playbackSessionId() {
        return PlaybackSessionId.parse(sessionId());
    }

    long elapsedMillis();

    boolean headphoneRouted();
}
