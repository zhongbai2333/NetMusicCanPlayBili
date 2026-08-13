package com.zhongbai233.net_music_can_play_bili.client;

import java.util.concurrent.CompletableFuture;

/** Stable per-device close signals that survive business-state replacement. */
final class HandheldReplacementGate {
    record Signals(String sessionId, CompletableFuture<Void> decodeExit,
            CompletableFuture<Void> nativeTermination) {
    }

    private Signals signals = new Signals("", CompletableFuture.completedFuture(null),
            CompletableFuture.completedFuture(null));

    synchronized Signals snapshot() {
        return signals;
    }

    synchronized Signals install(String sessionId, CompletableFuture<Void> decodeExit,
            CompletableFuture<Void> nativeTermination) {
        if (decodeExit == null || nativeTermination == null) {
            throw new IllegalArgumentException("handheld replacement signals must not be null");
        }
        signals = new Signals(sessionId != null ? sessionId : "", decodeExit, nativeTermination);
        return signals;
    }

    synchronized boolean matches(Signals expected) {
        return signals == expected;
    }

    synchronized boolean completedNormally() {
        return HandheldDecoderAdmissionPolicy.decide(signals.decodeExit(), signals.nativeTermination())
                == HandheldDecoderAdmissionPolicy.Decision.OPEN;
    }
}
