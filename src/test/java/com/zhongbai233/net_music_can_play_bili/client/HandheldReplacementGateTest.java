package com.zhongbai233.net_music_can_play_bili.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandheldReplacementGateTest {
    @Test
    void stableSignalsRemainPendingAcrossBusinessStateReplacement() {
        HandheldReplacementGate gate = new HandheldReplacementGate();
        CompletableFuture<Void> decodeExit = new CompletableFuture<>();
        CompletableFuture<Void> nativeTermination = new CompletableFuture<>();
        gate.install("old-session", decodeExit, nativeTermination);

        HandheldReplacementGate.Signals beforeClear = gate.snapshot();
        HandheldReplacementGate.Signals afterRecreate = gate.snapshot();
        assertSame(beforeClear, afterRecreate);
        assertFalse(gate.completedNormally());

        decodeExit.complete(null);
        assertFalse(gate.completedNormally());
        nativeTermination.complete(null);
        assertTrue(gate.completedNormally());
    }
}
