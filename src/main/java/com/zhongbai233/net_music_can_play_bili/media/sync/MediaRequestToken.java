package com.zhongbai233.net_music_can_play_bili.media.sync;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Opaque one-shot media request identity that is safe to serialize in a URL fragment. */
public record MediaRequestToken(String value) {
    private static final int MAX_LENGTH = 128;
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._~-]+");

    public MediaRequestToken {
        value = Objects.requireNonNull(value, "value").trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH || !SAFE_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid media request token");
        }
    }

    public static MediaRequestToken random() {
        return new MediaRequestToken(UUID.randomUUID().toString());
    }

    public static Optional<MediaRequestToken> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new MediaRequestToken(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
