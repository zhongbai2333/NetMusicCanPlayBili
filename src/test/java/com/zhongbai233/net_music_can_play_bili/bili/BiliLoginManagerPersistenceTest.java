package com.zhongbai233.net_music_can_play_bili.bili;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("ncpb-bili-static-state")
class BiliLoginManagerPersistenceTest {
    @TempDir
    Path temporaryDirectory;

    private String originalSessdata;
    private String originalWebCookie;

    @BeforeEach
    void rememberCredentials() {
        originalSessdata = BiliApiClient.sessdata;
        originalWebCookie = BiliApiClient.webCookie;
    }

    @AfterEach
    void restoreCredentials() {
        BiliApiClient.sessdata = originalSessdata;
        BiliApiClient.webCookie = originalWebCookie;
    }

    @Test
    void successfulQrLoginExplicitlyPersistsEncryptedCredentials() throws Exception {
        Path vault = temporaryDirectory.resolve("game/net_music_can_play_bili-private/bili-credentials.json.enc");
        BiliCredentialStore store = new BiliCredentialStore(vault,
                () -> Optional.of("qr-test-machine"), new SecureRandom());

        try (LoginServer server = new LoginServer();
                BiliLoginManager manager = manager(server, store)) {
            assertEquals(BiliLoginManager.State.PENDING, manager.generate().join());
            assertEquals(BiliLoginManager.State.SUCCESS, manager.poll().join());

            assertEquals("qr-session-secret", BiliApiClient.sessdata);
            assertTrue(BiliApiClient.webCookie.contains("SESSDATA=qr-session-secret"));
            assertTrue(BiliApiClient.webCookie.contains("bili_jct=qr-csrf-secret"));
            BiliCredentialStore.Credentials reloaded = store.load().credentials().orElseThrow();
            assertEquals(BiliApiClient.sessdata, reloaded.sessdata());
            assertEquals(BiliApiClient.webCookie, reloaded.webCookie());
            String encryptedFile = Files.readString(vault, StandardCharsets.UTF_8);
            assertFalse(encryptedFile.contains("qr-session-secret"));
            assertFalse(encryptedFile.contains("qr-csrf-secret"));
        }
    }

    @Test
    void qrLoginRemainsUsableForSessionWhenMachineBoundSaveFails() throws Exception {
        Path vault = temporaryDirectory.resolve("bili-credentials.json.enc");
        BiliCredentialStore unavailableStore = new BiliCredentialStore(vault, Optional::empty,
                new SecureRandom());

        try (LoginServer server = new LoginServer();
                BiliLoginManager manager = manager(server, unavailableStore)) {
            assertEquals(BiliLoginManager.State.PENDING, manager.generate().join());
            assertEquals(BiliLoginManager.State.SUCCESS_SESSION_ONLY, manager.poll().join());

            assertEquals("qr-session-secret", BiliApiClient.sessdata);
            assertTrue(BiliApiClient.webCookie.contains("SESSDATA=qr-session-secret"));
            assertFalse(Files.exists(vault));
        }
    }

    private static BiliLoginManager manager(LoginServer server, BiliCredentialStore store) {
        return new BiliLoginManager(HttpClient.newHttpClient(), URI.create(server.baseUrl() + "/generate"),
                server.baseUrl() + "/poll", server.baseUrl() + "/qr", store);
    }

    private static final class LoginServer implements AutoCloseable {
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final HttpServer server;

        private LoginServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/generate", exchange -> send(exchange,
                    "{\"code\":0,\"data\":{\"qrcode_key\":\"test-key\","
                            + "\"url\":\"https://example.invalid/qr\"}}"));
            server.createContext("/poll", exchange -> {
                exchange.getResponseHeaders().add("Set-Cookie",
                        "SESSDATA=qr-session-secret; Path=/; Secure; HttpOnly");
                exchange.getResponseHeaders().add("Set-Cookie",
                        "bili_jct=qr-csrf-secret; Path=/; Secure");
                exchange.getResponseHeaders().add("Set-Cookie", "DedeUserID=42; Path=/; Secure");
                send(exchange, "{\"code\":0,\"data\":{\"code\":0}} ");
            });
            server.setExecutor(executor);
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }

        private static void send(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
