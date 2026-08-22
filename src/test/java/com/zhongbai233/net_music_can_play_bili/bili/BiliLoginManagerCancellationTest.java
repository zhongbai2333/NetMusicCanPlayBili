package com.zhongbai233.net_music_can_play_bili.bili;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliLoginManagerCancellationTest {
    @Test
    void closeCancelsGenerateBeforeHeadersAndRejectsLateWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        HttpServer server = server(serverExecutor, exchange -> {
            entered.countDown();
            await(release);
            send(exchange, generatedResponse());
        });
        BiliLoginManager manager = manager(server);
        try {
            CompletableFuture<BiliLoginManager.State> generate = manager.generate();
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            manager.close();

            assertEquals(0, manager.activeRequestCount());
            assertEquals(BiliLoginManager.State.FAILED, generate.join());
            assertEquals(BiliLoginManager.State.FAILED, manager.generate().join());
            assertNull(manager.loadQrImage("late").join());
        } finally {
            release.countDown();
            manager.close();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void closeCancelsSingleFlightPollAndQrImageTogether() throws Exception {
        CountDownLatch blockingRequests = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        HttpServer server = server(serverExecutor, exchange -> {
            if ("/generate".equals(exchange.getRequestURI().getPath())) {
                send(exchange, generatedResponse());
                return;
            }
            blockingRequests.countDown();
            await(release);
            if ("/poll".equals(exchange.getRequestURI().getPath())) {
                send(exchange, "{\"code\":0,\"data\":{\"code\":86101}}");
            } else {
                send(exchange, "image");
            }
        });
        BiliLoginManager manager = manager(server);
        try {
            assertEquals(BiliLoginManager.State.PENDING, manager.generate().join());
            CompletableFuture<BiliLoginManager.State> poll = manager.poll();
            assertSame(poll, manager.poll());
            CompletableFuture<byte[]> qrImage = manager.loadQrImage("https://example.invalid/login");
            assertTrue(blockingRequests.await(2, TimeUnit.SECONDS));

            manager.close();

            assertEquals(BiliLoginManager.State.FAILED, poll.join());
            assertNull(qrImage.join());
            assertEquals(0, manager.activeRequestCount());
        } finally {
            release.countDown();
            manager.close();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    private static BiliLoginManager manager(HttpServer server) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new BiliLoginManager(HttpClient.newHttpClient(), URI.create(base + "/generate"),
                base + "/poll", base + "/qr");
    }

    private static HttpServer server(ExecutorService executor, com.sun.net.httpserver.HttpHandler handler)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.setExecutor(executor);
        server.start();
        return server;
    }

    private static void await(CountDownLatch release) {
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String generatedResponse() {
        return "{\"code\":0,\"data\":{\"qrcode_key\":\"test-key\",\"url\":\"https://example.invalid/qr\"}}";
    }
}
