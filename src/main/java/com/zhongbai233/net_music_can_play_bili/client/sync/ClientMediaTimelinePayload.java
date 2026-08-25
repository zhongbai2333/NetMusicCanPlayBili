package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;

import java.util.UUID;
import java.util.Optional;

/**
 * Protocol-neutral view of a lightweight synchronized client media timeline
 * update.
 */
public interface ClientMediaTimelinePayload {
    UUID sourceId();

    String sessionId();

    /** Typed runtime view of the wire-compatible session field. */
    default Optional<PlaybackSessionId> playbackSessionId() {
        return PlaybackSessionId.parse(sessionId());
    }

    long elapsedMillis();

    int volumePerMille();

    boolean headphoneRouted();

    default AreaAudioZone areaAudioZone() {
        return AreaAudioZone.unrestricted();
    }
}
