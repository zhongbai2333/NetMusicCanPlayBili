package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoResolveRequestOwnerTest {
    @Test
    void lateBindingAfterCancelCancelsWorker() throws Exception {
        VideoResolveRequestOwner<Object> owner = new VideoResolveRequestOwner<>(116,
                ResolveGeneration.of(7L), List.of());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CancellableTaskFuture<Void> worker = CancellableTaskFuture.submit(executor, () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            started.await();
            owner.cancel();
            owner.bind(worker);
            assertTrue(worker.isCancelled());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void replacementMetadataRemainsStable() {
        VideoResolveRequestOwner<Object> owner = new VideoResolveRequestOwner<>(80,
                ResolveGeneration.of(11L), List.of());
        assertTrue(owner.matches(1234L, 80));
        assertTrue(!owner.matches(1234L, 116));
        assertEquals(ResolveGeneration.of(11L), owner.requestGeneration());
        assertEquals(0, owner.consumerPositions().size());
    }
}
