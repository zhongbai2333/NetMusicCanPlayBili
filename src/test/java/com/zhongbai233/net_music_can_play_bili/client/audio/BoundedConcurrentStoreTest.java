package com.zhongbai233.net_music_can_play_bili.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class BoundedConcurrentStoreTest {
    @Test
    void replacesSameKeyAndReturnsPreviousValue() {
        BoundedConcurrentStore<String, Object> store = new BoundedConcurrentStore<>(2);
        Object first = new Object();
        Object second = new Object();

        assertNull(store.put("source", first, 10L).replaced());
        BoundedConcurrentStore.PutResult<Object> result = store.put("source", second, 20L);

        assertSame(first, result.replaced());
        assertTrue(result.evicted().isEmpty());
        assertSame(second, store.get("source"));
        assertEquals(1, store.size());
    }

    @Test
    void evictsOldestEntryAtCapacity() {
        BoundedConcurrentStore<String, String> store = new BoundedConcurrentStore<>(2);
        store.put("old", "old-value", 10L);
        store.put("new", "new-value", 20L);

        BoundedConcurrentStore.PutResult<String> result = store.put("latest", "latest-value", 30L);

        assertEquals(List.of("old-value"), result.evicted());
        assertNull(store.get("old"));
        assertEquals("new-value", store.get("new"));
        assertEquals("latest-value", store.get("latest"));
    }

    @Test
    void equalTimestampsEvictInInsertionOrder() {
        BoundedConcurrentStore<String, String> store = new BoundedConcurrentStore<>(2);
        store.put("first", "first-value", 10L);
        store.put("second", "second-value", 10L);

        BoundedConcurrentStore.PutResult<String> result = store.put("third", "third-value", 10L);

        assertEquals(List.of("first-value"), result.evicted());
    }

    @Test
    void conditionalRemoveUsesIdentity() {
        BoundedConcurrentStore<String, String> store = new BoundedConcurrentStore<>(2);
        String stored = new String("value");
        String equalButDifferent = new String("value");
        store.put("key", stored, 1L);

        assertFalse(store.remove("key", equalButDifferent));
        assertTrue(store.remove("key", stored));
        assertTrue(store.isEmpty());
    }

    @Test
    void removeIfAndClearReturnRemovedValues() {
        BoundedConcurrentStore<String, Integer> store = new BoundedConcurrentStore<>(4);
        store.put("one", 1, 1L);
        store.put("two", 2, 2L);
        store.put("three", 3, 3L);

        assertEquals(Set.of(1, 3), new HashSet<>(store.removeIf(value -> value % 2 == 1)));
        assertEquals(List.of(2), store.clear());
        assertTrue(store.isEmpty());
    }

    @Test
    void concurrentReplacementKeepsOneValueAndReportsEveryDisplacedValue() throws Exception {
        int operations = 200;
        BoundedConcurrentStore<String, Integer> store = new BoundedConcurrentStore<>(4);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<BoundedConcurrentStore.PutResult<Integer>>> futures = new ArrayList<>();
            for (int i = 0; i < operations; i++) {
                int value = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return store.put("source", value, value);
                }));
            }
            start.countDown();
            Set<Integer> displaced = new HashSet<>();
            for (Future<BoundedConcurrentStore.PutResult<Integer>> future : futures) {
                Integer replaced = future.get().replaced();
                if (replaced != null) {
                    displaced.add(replaced);
                }
            }
            Integer survivor = store.get("source");
            assertEquals(1, store.size());
            assertEquals(operations - 1, displaced.size());
            assertFalse(displaced.contains(survivor));
        } finally {
            executor.shutdownNow();
        }
    }
}