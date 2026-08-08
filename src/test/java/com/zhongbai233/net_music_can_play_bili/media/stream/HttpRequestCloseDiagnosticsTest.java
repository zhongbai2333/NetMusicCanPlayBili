package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpRequestCloseDiagnosticsTest {
    @Test
    void tracksCancellationAndConvergenceWithoutRetainingResources() {
        HttpRequestCloseDiagnostics diagnostics = new HttpRequestCloseDiagnostics(4, 4);
        long request = diagnostics.begin("range", "localhost", 0L, 99L, 1_000L);
        diagnostics.headers(request, 206);
        diagnostics.bodyPublished(request);
        diagnostics.cancelRequested(request);
        diagnostics.cancelRequested(request);
        diagnostics.terminal(request, true, 25L, 1_100L);

        var snapshot = diagnostics.snapshot(1_100L);
        assertEquals(0, snapshot.activeRequests());
        assertEquals(1, snapshot.retainedCompleted());
        assertEquals(1, snapshot.startedRequests());
        assertEquals(1, snapshot.cancelRequests());
        assertEquals(1, snapshot.completedRequests());
        assertEquals(100L, snapshot.latestConvergenceNanos());
    }

    @Test
    void boundsActiveAndCompletedHistory() {
        HttpRequestCloseDiagnostics diagnostics = new HttpRequestCloseDiagnostics(1, 1);
        diagnostics.begin("get", "first", -1L, -1L, 0L);
        long second = diagnostics.begin("get", "second", -1L, -1L, 1L);
        diagnostics.terminal(second, false, 0L, 2L);
        long third = diagnostics.begin("get", "third", -1L, -1L, 3L);
        diagnostics.terminal(third, true, 0L, 4L);

        var snapshot = diagnostics.snapshot(4L);
        assertEquals(0, snapshot.activeRequests());
        assertEquals(1, snapshot.retainedCompleted());
        assertEquals(1, snapshot.droppedRequests());
        assertEquals(1, snapshot.failedRequests());
        assertEquals(1, snapshot.completedRequests());
    }
}