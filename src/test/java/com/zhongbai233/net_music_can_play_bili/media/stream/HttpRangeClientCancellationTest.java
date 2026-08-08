package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRangeClientCancellationTest {
    @Test
    void interruptBeforeHeadersCancelsRootRequestAndConverges() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            accepted.countDown();
            await(releaseServer);
            send(exchange, "late");
        });
        var before = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread requester = new Thread(() -> {
                HttpRequest request = HttpRequest.newBuilder(uri(server)).GET().build();
                long operation = HttpRequestCloseDiagnostics.global().begin(
                    "test", "localhost", -1L, -1L, System.nanoTime());
                try {
                    var body = CancellableHttpTransport.send(HttpClient.newHttpClient(), request,
                        HttpRequestCloseDiagnostics.global(), operation).body();
                    try (body) {
                        failure.set(new AssertionError("request unexpectedly returned headers"));
                    }
                } catch (IOException expected) {
                if (!Thread.currentThread().isInterrupted()) {
                    failure.set(new AssertionError("interrupt status was not preserved", expected));
                }
            }
        }, "http-range-cancel-test");
        try {
            requester.start();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            requester.interrupt();
            requester.join(5_000L);
            assertFalse(requester.isAlive());
            assertEquals(null, failure.get());
            var after = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
            assertEquals(before.activeRequests(), after.activeRequests());
            assertEquals(before.startedRequests() + 1L, after.startedRequests());
            assertEquals(before.cancelRequests() + 1L, after.cancelRequests());
            assertEquals(before.failedRequests() + 1L, after.failedRequests());
        } finally {
            releaseServer.countDown();
            server.stop(0);
        }
    }

    @Test
    void closingPublishedBodyBeforeEofRecordsCancellationAndConvergence() throws Exception {
        HttpServer server = server(exchange -> send(exchange, "deterministic-body"));
        var before = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
        try {
            HttpRequest request = HttpRequest.newBuilder(uri(server)).GET().build();
            long operation = HttpRequestCloseDiagnostics.global().begin(
                    "test", "localhost", -1L, -1L, System.nanoTime());
            var response = CancellableHttpTransport.send(HttpClient.newHttpClient(), request,
                    HttpRequestCloseDiagnostics.global(), operation);
            try (var body = response.body()) {
                assertEquals('d', body.read());
            }
            var after = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
            assertEquals(before.activeRequests(), after.activeRequests());
            assertEquals(before.startedRequests() + 1L, after.startedRequests());
            assertEquals(before.cancelRequests() + 1L, after.cancelRequests());
            assertEquals(before.completedRequests() + 1L, after.completedRequests());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancellingRaceLoserInterruptsRequestBeforeHeadersAndConverges() throws Exception {
        CountDownLatch loserAccepted = new CountDownLatch(1);
        CountDownLatch releaseLoser = new CountDownLatch(1);
        HttpServer loserServer = server(exchange -> {
            loserAccepted.countDown();
            await(releaseLoser);
            send(exchange, "loser");
        });
        HttpServer winnerServer = server(exchange -> send(exchange, "winner"));
        var executor = Executors.newFixedThreadPool(2);
        var before = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
        try {
            Future<Integer> loser = executor.submit(() -> firstByte(loserServer));
            assertTrue(loserAccepted.await(5, TimeUnit.SECONDS));
            Future<Integer> winner = executor.submit(() -> firstByte(winnerServer));
            assertEquals((int) 'w', winner.get(5, TimeUnit.SECONDS));
            assertTrue(loser.cancel(true));
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            var after = HttpRequestCloseDiagnostics.global().snapshot(System.nanoTime());
            assertEquals(before.activeRequests(), after.activeRequests());
            assertEquals(before.startedRequests() + 2L, after.startedRequests());
            assertEquals(before.cancelRequests() + 2L, after.cancelRequests());
            assertEquals(before.completedRequests() + 1L, after.completedRequests());
            assertEquals(before.failedRequests() + 1L, after.failedRequests());
        } finally {
            releaseLoser.countDown();
            executor.shutdownNow();
            loserServer.stop(0);
            winnerServer.stop(0);
        }
    }

    private static int firstByte(HttpServer server) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri(server)).GET().build();
        long operation = HttpRequestCloseDiagnostics.global().begin(
                "race-test", "localhost", -1L, -1L, System.nanoTime());
        var response = CancellableHttpTransport.send(HttpClient.newHttpClient(), request,
                HttpRequestCloseDiagnostics.global(), operation);
        try (var body = response.body()) {
            return body.read();
        }
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media", handler);
        server.start();
        return server;
    }

    private static URI uri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/media");
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("test server timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("test server interrupted", e);
        }
    }
}