package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoSessionInstanceRegistryTest {
    @Test
    void replacementDisposesThePreviousSessionOwner() {
        VideoSessionInstanceRegistry<TrackedInstance> registry = registry();
        TrackedInstance first = new TrackedInstance();
        TrackedInstance second = new TrackedInstance();

        registry.replace("session", first);
        registry.replace("session", second);

        assertEquals(1, first.disposals());
        assertEquals(0, second.disposals());
        assertSame(second, registry.get("session"));
    }

    @Test
    void exactRemovalCannotDeleteAReplacement() {
        VideoSessionInstanceRegistry<TrackedInstance> registry = registry();
        TrackedInstance first = new TrackedInstance();
        TrackedInstance second = new TrackedInstance();
        registry.replace("session", first);
        registry.replace("session", second);

        assertFalse(registry.remove("session", first));
        assertEquals(1, first.disposals());
        assertSame(second, registry.get("session"));
        assertEquals(0, second.disposals());
        assertTrue(registry.remove("session", second));
        assertEquals(1, second.disposals());
        assertTrue(registry.isEmpty());
    }

    @Test
    void conditionalRemovalDisposesOnlyMatchingInstances() {
        VideoSessionInstanceRegistry<TrackedInstance> registry = registry();
        TrackedInstance retained = new TrackedInstance();
        TrackedInstance removed = new TrackedInstance();
        removed.removable = true;
        registry.replace("retained", retained);
        registry.replace("removed", removed);

        registry.removeIf(instance -> instance.removable);

        assertSame(retained, registry.get("retained"));
        assertEquals(0, retained.disposals());
        assertEquals(1, removed.disposals());
        assertEquals(1, registry.size());
    }

    @Test
    void clearDisposesEveryRegisteredInstanceExactlyOnce() {
        VideoSessionInstanceRegistry<TrackedInstance> registry = registry();
        TrackedInstance first = new TrackedInstance();
        TrackedInstance second = new TrackedInstance();
        registry.replace(null, first);
        registry.replace("second", second);

        registry.clear();
        registry.clear();

        assertEquals(1, first.disposals());
        assertEquals(1, second.disposals());
        assertTrue(registry.instances().isEmpty());
    }

    private static VideoSessionInstanceRegistry<TrackedInstance> registry() {
        return new VideoSessionInstanceRegistry<>(TrackedInstance::dispose);
    }

    private static final class TrackedInstance {
        private final AtomicInteger disposals = new AtomicInteger();
        private boolean removable;

        private void dispose() {
            disposals.incrementAndGet();
        }

        private int disposals() {
            return disposals.get();
        }
    }
}
