package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioDurationProbeTest {
    private static final byte[] MP3_FRAME = { (byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64 };

    @Test
    void rangeProbeEstimatesDurationAndConvergesBodyDiagnostics() throws Exception {
        HttpServer server = server(exchange -> sendMp3(exchange, 128_000L));
        HttpRequestCloseDiagnostics diagnostics = new HttpRequestCloseDiagnostics(8, 8);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OptionalLong duration = AudioDurationProbe.probeMillisAsync(url(server, "/"),
                    HttpClient.newHttpClient(), diagnostics, executor).get(5L, TimeUnit.SECONDS);

            assertEquals(OptionalLong.of(8_000L), duration);
            var snapshot = diagnostics.snapshot(System.nanoTime());
            assertEquals(0, snapshot.activeRequests());
            assertEquals(1, snapshot.startedRequests());
            assertEquals(1, snapshot.completedRequests());
            assertEquals(0, snapshot.failedRequests());
        } finally {
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void redirectsReleaseEachBodyBeforeStartingTheNextRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().add("Location", "/audio");
                exchange.sendResponseHeaders(302, -1L);
            }
        });
        server.createContext("/audio", exchange -> sendMp3(exchange, 256_000L));
        server.start();
        HttpRequestCloseDiagnostics diagnostics = new HttpRequestCloseDiagnostics(8, 8);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OptionalLong duration = AudioDurationProbe.probeMillisAsync(url(server, "/start"),
                    HttpClient.newHttpClient(), diagnostics, executor).get(5L, TimeUnit.SECONDS);

            assertEquals(OptionalLong.of(16_000L), duration);
            var snapshot = diagnostics.snapshot(System.nanoTime());
            assertEquals(0, snapshot.activeRequests());
            assertEquals(2, snapshot.startedRequests());
            assertEquals(2, snapshot.completedRequests());
        } finally {
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void cancelBeforeHeadersInterruptsTheRootRequestAndConverges() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            entered.countDown();
            await(release);
            sendMp3(exchange, 128_000L);
        });
        HttpRequestCloseDiagnostics diagnostics = new HttpRequestCloseDiagnostics(8, 8);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var task = AudioDurationProbe.probeMillisAsync(url(server, "/"),
                    HttpClient.newHttpClient(), diagnostics, executor);
            assertTrue(entered.await(2L, TimeUnit.SECONDS));

            assertTrue(task.cancel(true));
            awaitConvergence(diagnostics);

            assertTrue(task.isCancelled());
            var snapshot = diagnostics.snapshot(System.nanoTime());
            assertEquals(0, snapshot.activeRequests());
            assertEquals(1, snapshot.cancelRequests());
            assertEquals(1, snapshot.failedRequests());
        } finally {
            release.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void cancelAfterHeadersClosesThePublishedResponseBody() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(206, 256 * 1024L);
                exchange.getResponseBody().write(MP3_FRAME);
                exchange.getResponseBody().flush();
                bodyStarted.countDown();
                await(release);
                exchange.getResponseBody().write(new byte[256 * 1024 - MP3_FRAME.length]);
            }
        });
        HttpRequestCloseDiagnostics diagnostics = new HttpRequestCloseDiagnostics(8, 8);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var task = AudioDurationProbe.probeMillisAsync(url(server, "/"),
                    HttpClient.newHttpClient(), diagnostics, executor);
            assertTrue(bodyStarted.await(2L, TimeUnit.SECONDS));

            assertTrue(task.cancel(true));
            awaitConvergence(diagnostics);

            assertTrue(task.isCancelled());
            var snapshot = diagnostics.snapshot(System.nanoTime());
            assertEquals(0, snapshot.activeRequests());
            assertEquals(1, snapshot.cancelRequests());
            assertEquals(1, snapshot.completedRequests() + snapshot.failedRequests());
        } finally {
            release.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void sendMp3(HttpExchange exchange, long totalBytes) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().add("Content-Range", "bytes 0-3/" + totalBytes);
            exchange.sendResponseHeaders(206, MP3_FRAME.length);
            exchange.getResponseBody().write(MP3_FRAME);
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new IOException("test server timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("test server interrupted", interrupted);
        }
    }

    private static void awaitConvergence(HttpRequestCloseDiagnostics diagnostics) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (diagnostics.snapshot(System.nanoTime()).activeRequests() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertEquals(0, diagnostics.snapshot(System.nanoTime()).activeRequests());
    }
}
