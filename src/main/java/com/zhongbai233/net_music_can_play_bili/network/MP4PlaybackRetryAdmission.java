package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * Exact admission boundary for refreshing one active MP4 transport without
 * replacing its logical playback session.
 */
final class MP4PlaybackRetryAdmission<S> {
    private final MP4PlaybackSourceSessionRegistry<S> sessions;
    private final MP4ResolveIntentRegistry resolveIntents;
    private final Function<S, PlaybackSessionId> sessionId;
    private final ToIntFunction<S> queueIndex;
    private final Function<S, String> sourceUrl;

    MP4PlaybackRetryAdmission(MP4PlaybackSourceSessionRegistry<S> sessions,
            MP4ResolveIntentRegistry resolveIntents,
            Function<S, PlaybackSessionId> sessionId,
            ToIntFunction<S> queueIndex,
            Function<S, String> sourceUrl) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.resolveIntents = Objects.requireNonNull(resolveIntents, "resolveIntents");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.queueIndex = Objects.requireNonNull(queueIndex, "queueIndex");
        this.sourceUrl = Objects.requireNonNull(sourceUrl, "sourceUrl");
    }

    Attempt begin(UUID deviceId, PlaybackSessionId expectedSessionId) {
        S current = sessions.get(deviceId);
        if (current == null || expectedSessionId == null || !expectedSessionId.equals(sessionId.apply(current))) {
            return null;
        }
        int expectedQueueIndex = queueIndex.applyAsInt(current);
        String expectedSourceUrl = safeSourceUrl(current);
        MP4ResolveIntentRegistry.Intent resolveIntent = resolveIntents.beginIfIdle(deviceId, expectedQueueIndex,
                expectedSourceUrl);
        if (resolveIntent == null) {
            return null;
        }
        Attempt attempt = new Attempt(expectedSessionId, expectedQueueIndex, expectedSourceUrl, resolveIntent);
        if (!matchesCurrent(deviceId, attempt)) {
            resolveIntents.complete(deviceId, resolveIntent);
            return null;
        }
        return attempt;
    }

    boolean isCurrent(UUID deviceId, Attempt attempt) {
        return attempt != null && resolveIntents.isCurrent(deviceId, attempt.resolveIntent())
                && matchesCurrent(deviceId, attempt);
    }

    S replaceIfCurrent(UUID deviceId, Attempt attempt, UnaryOperator<S> replacementFactory) {
        if (attempt == null || replacementFactory == null || !isCurrent(deviceId, attempt)) {
            complete(deviceId, attempt);
            return null;
        }
        S current = sessions.get(deviceId);
        if (!matches(current, attempt)) {
            complete(deviceId, attempt);
            return null;
        }
        S replacement = Objects.requireNonNull(replacementFactory.apply(current), "replacement");
        if (!resolveIntents.isCurrent(deviceId, attempt.resolveIntent())
                || !sessions.replace(deviceId, current, replacement)) {
            complete(deviceId, attempt);
            return null;
        }
        resolveIntents.complete(deviceId, attempt.resolveIntent());
        return replacement;
    }

    void complete(UUID deviceId, Attempt attempt) {
        if (attempt != null) {
            resolveIntents.complete(deviceId, attempt.resolveIntent());
        }
    }

    private boolean matchesCurrent(UUID deviceId, Attempt attempt) {
        return matches(sessions.get(deviceId), attempt);
    }

    private boolean matches(S current, Attempt attempt) {
        return current != null
                && attempt.expectedSessionId().equals(sessionId.apply(current))
                && attempt.queueIndex() == queueIndex.applyAsInt(current)
                && attempt.sourceUrl().equals(safeSourceUrl(current));
    }

    private String safeSourceUrl(S session) {
        String value = sourceUrl.apply(session);
        return value != null ? value : "";
    }

    record Attempt(PlaybackSessionId expectedSessionId, int queueIndex, String sourceUrl,
            MP4ResolveIntentRegistry.Intent resolveIntent) {
        Attempt {
            Objects.requireNonNull(expectedSessionId, "expectedSessionId");
            sourceUrl = sourceUrl != null ? sourceUrl : "";
            Objects.requireNonNull(resolveIntent, "resolveIntent");
        }
    }
}
