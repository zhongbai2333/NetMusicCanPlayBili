package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.Optional;

/** Neutral handle for a synchronized client media sound instance. */
public interface ClientMediaSoundHandle {
    String sessionId();

    /** Typed runtime identity; the string method remains the compatibility facade. */
    default Optional<PlaybackSessionId> playbackSession() {
        return PlaybackSessionId.parse(sessionId());
    }

    boolean headphoneRouted();

    boolean stopped();

    /** Stops this handle without completing its logical session. Implementations must be idempotent. */
    void discardWithoutFinishing();

    void setMediaVolume(float volume);
}
