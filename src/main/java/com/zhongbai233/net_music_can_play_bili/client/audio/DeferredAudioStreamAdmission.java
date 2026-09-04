package com.zhongbai233.net_music_can_play_bili.client.audio;

import java.util.concurrent.CompletableFuture;

/**
 * One-shot decision for a streaming sound whose OpenAL handle is allocated before decoding may start.
 *
 * <p>The decision future deliberately never completes exceptionally. Once Minecraft has reserved a streaming
 * handle, cancellation still has to deliver an {@code AudioStream} so the handle can attach, observe EOF and be
 * released by the normal sound-engine lifecycle.</p>
 */
final class DeferredAudioStreamAdmission {
    private final CompletableFuture<Decision> decision = new CompletableFuture<>();

    CompletableFuture<Decision> future() {
        return decision;
    }

    boolean approveMediaStream() {
        return decision.complete(Decision.OPEN_MEDIA_STREAM);
    }

    boolean drainAllocatedChannel() {
        return decision.complete(Decision.ATTACH_DRAINED_STREAM);
    }

    boolean isDecided() {
        return decision.isDone();
    }

    enum Decision {
        OPEN_MEDIA_STREAM,
        ATTACH_DRAINED_STREAM
    }
}
