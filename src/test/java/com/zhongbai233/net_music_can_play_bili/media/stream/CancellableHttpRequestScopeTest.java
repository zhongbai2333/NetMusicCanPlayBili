package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellableHttpRequestScopeTest {
    @Test
    void closeCancelsRootRequestBeforeHeadersAndConvergesDiagnostics() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                send(exchange, "late");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        HttpRequestCloseDiagnostics diagnostics = new HttpRequestCloseDiagnostics(8, 8);
        CancellableHttpRequestScope scope = new CancellableHttpRequestScope(diagnostics);
        try {
            var future = scope.sendAsync(HttpClient.newHttpClient(), request(server),
                    HttpResponse.BodyHandlers.ofByteArray(), "login-test");
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            scope.close();

            assertEquals(0, scope.activeRequests());
            Throwable cancellation = assertThrows(RuntimeException.class, future::join);
            assertTrue(hasCause(cancellation, java.util.concurrent.CancellationException.class));
            var snapshot = diagnostics.snapshot(System.nanoTime());
            assertEquals(0, snapshot.activeRequests());
            assertEquals(1, snapshot.cancelRequests());
            assertEquals(1, snapshot.failedRequests());
        } finally {
            release.countDown();
            scope.close();
            server.stop(0);
        }
    }

    @Test
    void completedByteResponseRecordsHeadersBodyAndExactBytes() throws Exception {
        HttpServer server = server(exchange -> send(exchange, "qr-bytes"));
        HttpRequestCloseDiagnostics diagnostics = new HttpRequestCloseDiagnostics(8, 8);
        try (CancellableHttpRequestScope scope = new CancellableHttpRequestScope(diagnostics)) {
            byte[] body = scope.sendAsync(HttpClient.newHttpClient(), request(server),
                    HttpResponse.BodyHandlers.ofByteArray(), "login-image").join().body();

            assertArrayEquals("qr-bytes".getBytes(StandardCharsets.UTF_8), body);
            var snapshot = diagnostics.snapshot(System.nanoTime());
            assertEquals(0, snapshot.activeRequests());
            assertEquals(1, snapshot.completedRequests());
            assertEquals(0, snapshot.failedRequests());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void closedScopeRejectsLateAdmission() {
        CancellableHttpRequestScope scope = new CancellableHttpRequestScope(
                new HttpRequestCloseDiagnostics(2, 2));
        scope.close();

        var future = scope.sendAsync(HttpClient.newHttpClient(),
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/")).GET().build(),
                HttpResponse.BodyHandlers.ofString(), "late");

        assertThrows(java.util.concurrent.CancellationException.class, future::join);
        assertEquals(0, scope.activeRequests());
    }

    @Test
    void interruptingBlockingCallerCancelsExactRootRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                send(exchange, "late");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        HttpRequestCloseDiagnostics diagnostics = new HttpRequestCloseDiagnostics(8, 8);
        CancellableHttpRequestScope scope = new CancellableHttpRequestScope(diagnostics);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var task = executor.submit(() -> scope.sendBlocking(HttpClient.newHttpClient(), request(server),
                    HttpResponse.BodyHandlers.ofString(), "blocking-api"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            assertTrue(task.cancel(true));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (scope.activeRequests() != 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(0, scope.activeRequests());
            var snapshot = diagnostics.snapshot(System.nanoTime());
            assertEquals(1, snapshot.cancelRequests());
            assertEquals(1, snapshot.failedRequests());
        } finally {
            release.countDown();
            scope.close();
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

    private static HttpRequest request(HttpServer server) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                .GET().build();
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
