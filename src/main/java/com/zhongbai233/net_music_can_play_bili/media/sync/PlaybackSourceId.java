package com.zhongbai233.net_music_can_play_bili.media.sync;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Strongly typed identity of one synchronized playback source/device. */
public record PlaybackSourceId(UUID value) {
    public PlaybackSourceId {
        Objects.requireNonNull(value, "value");
    }

    public static PlaybackSourceId of(UUID value) {
        return new PlaybackSourceId(value);
    }

    public static Optional<PlaybackSourceId> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PlaybackSourceId(UUID.fromString(raw.trim())));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
