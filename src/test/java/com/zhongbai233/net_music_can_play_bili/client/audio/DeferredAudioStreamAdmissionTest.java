package com.zhongbai233.net_music_can_play_bili.client.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredAudioStreamAdmissionTest {
    @Test
    void cancellationCompletesNormallyWithAChannelDrainingDecision() {
        DeferredAudioStreamAdmission admission = new DeferredAudioStreamAdmission();
        CompletableFuture<DeferredAudioStreamAdmission.Decision> future = admission.future();

        assertFalse(future.isDone());
        assertTrue(admission.drainAllocatedChannel());

        assertFalse(future.isCompletedExceptionally());
        assertFalse(future.isCancelled());
        assertEquals(DeferredAudioStreamAdmission.Decision.ATTACH_DRAINED_STREAM,
                assertDoesNotThrow(future::join));
    }

    @Test
    void approvalCompletesNormallyWithTheMediaStreamDecision() {
        DeferredAudioStreamAdmission admission = new DeferredAudioStreamAdmission();

        assertTrue(admission.approveMediaStream());

        assertFalse(admission.future().isCompletedExceptionally());
        assertEquals(DeferredAudioStreamAdmission.Decision.OPEN_MEDIA_STREAM, admission.future().join());
    }

    @Test
    void repeatedCancellationIsIdempotentAndCannotReopenTheStream() {
        DeferredAudioStreamAdmission admission = new DeferredAudioStreamAdmission();

        assertTrue(admission.drainAllocatedChannel());
        assertFalse(admission.drainAllocatedChannel());
        assertFalse(admission.approveMediaStream());

        assertEquals(DeferredAudioStreamAdmission.Decision.ATTACH_DRAINED_STREAM, admission.future().join());
    }

    @Test
    void cancellationAfterApprovalCannotRewriteAnAlreadyScheduledOpen() {
        DeferredAudioStreamAdmission admission = new DeferredAudioStreamAdmission();

        assertTrue(admission.approveMediaStream());
        assertFalse(admission.drainAllocatedChannel());

        assertEquals(DeferredAudioStreamAdmission.Decision.OPEN_MEDIA_STREAM, admission.future().join());
    }
}
