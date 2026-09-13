package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("ncpb-bili-static-state")
class BiliConfigPersistenceTest {
    private static final BiliCredentialStore.Credentials NO_RUNTIME_CREDENTIALS =
            new BiliCredentialStore.Credentials("", "");

    @TempDir
    Path temporaryDirectory;

    private Snapshot original;

    @BeforeEach
    void rememberStaticState() {
        original = new Snapshot(BiliApiClient.sessdata, BiliApiClient.webCookie, BiliConfig.dolbyEnabled,
                BiliConfig.dolbyJocEnabled, BiliConfig.dolbyMaxObjectSources, BiliConfig.stereoCrossfeed,
                BiliConfig.userAgent, BiliConfig.rotateUserAgent);
    }

    @AfterEach
    void restoreStaticState() {
        BiliApiClient.sessdata = original.sessdata();
        BiliApiClient.webCookie = original.webCookie();
        BiliConfig.dolbyEnabled = original.dolbyEnabled();
        BiliConfig.dolbyJocEnabled = original.dolbyJocEnabled();
        BiliConfig.dolbyMaxObjectSources = original.dolbyMaxObjectSources();
        BiliConfig.stereoCrossfeed = original.stereoCrossfeed();
        BiliConfig.userAgent = original.userAgent();
        BiliConfig.rotateUserAgent = original.rotateUserAgent();
    }

    @Test
    void savingSettingsNeverWritesCredentialsOrTouchesVault() throws Exception {
        Path settings = temporaryDirectory.resolve("config/net_music_can_play_bili.json");
        Path vault = temporaryDirectory.resolve("game/net_music_can_play_bili-private/bili-credentials.json.enc");
        BiliCredentialStore store = store(vault, "machine-a");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, """
                {"sessdata":"old-secret-marker","webCookie":"SESSDATA=old-secret-marker",
                 "futureSetting":{"preserve":true}}
                """, StandardCharsets.UTF_8);
        assertTrue(BiliConfig.saveCredentials(store, new BiliCredentialStore.Credentials(
                "session-secret-marker", "SESSDATA=session-secret-marker; bili_jct=csrf-secret-marker")).saved());
        byte[] vaultBefore = Files.readAllBytes(vault);

        BiliApiClient.sessdata = "runtime-secret-marker";
        BiliApiClient.webCookie = "SESSDATA=runtime-secret-marker; bili_jct=runtime-csrf-marker";
        BiliConfig.dolbyEnabled = false;
        BiliConfig.dolbyJocEnabled = false;
        BiliConfig.dolbyMaxObjectSources = 17;
        BiliConfig.stereoCrossfeed = 0.25;

        assertTrue(BiliConfig.save(settings));

        String saved = Files.readString(settings, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(saved).getAsJsonObject();
        assertFalse(root.has("sessdata"));
        assertFalse(root.has("webCookie"));
        assertFalse(saved.contains("secret-marker"));
        assertEquals(17, root.get("dolbyMaxObjectSources").getAsInt());
        assertTrue(root.getAsJsonObject("futureSetting").get("preserve").getAsBoolean());
        assertArrayEquals(vaultBefore, Files.readAllBytes(vault));
    }

    @Test
    void legacyPlaintextIsScrubbedButNeverLoadedOrMigrated() throws Exception {
        Path settings = temporaryDirectory.resolve("config/net_music_can_play_bili.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, """
                {
                  "sessdata": "legacy-session-secret",
                  "webCookie": "SESSDATA=legacy-session-secret; bili_jct=legacy-csrf-secret",
                  "dolbyEnabled": false,
                  "dolbyMaxObjectSources": 23,
                  "futureSetting": {"preserve": true}
                }
                """, StandardCharsets.UTF_8);
        Path vault = temporaryDirectory.resolve("game/net_music_can_play_bili-private/bili-credentials.json.enc");
        BiliApiClient.sessdata = "stale-process-secret";
        BiliApiClient.webCookie = "SESSDATA=stale-process-secret";

        BiliConfig.load(settings, store(vault, "machine-a"), NO_RUNTIME_CREDENTIALS);

        assertEquals("", BiliApiClient.sessdata);
        assertEquals("", BiliApiClient.webCookie);
        assertFalse(Files.exists(vault), "legacy plaintext must not be re-encrypted automatically");
        JsonObject scrubbed = JsonParser.parseString(Files.readString(settings, StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertFalse(scrubbed.has("sessdata"));
        assertFalse(scrubbed.has("webCookie"));
        assertTrue(scrubbed.getAsJsonObject("futureSetting").get("preserve").getAsBoolean());
        assertFalse(BiliConfig.dolbyEnabled);
        assertEquals(23, BiliConfig.dolbyMaxObjectSources);
    }

    @Test
    void jvmCredentialsTakePriorityAndRemainRuntimeOnly() throws Exception {
        Path settings = temporaryDirectory.resolve("config/net_music_can_play_bili.json");
        Path vault = temporaryDirectory.resolve("game/net_music_can_play_bili-private/bili-credentials.json.enc");
        BiliCredentialStore store = store(vault, "machine-a");
        assertTrue(store.save(new BiliCredentialStore.Credentials(
                "stored-session", "SESSDATA=stored-session; bili_jct=stored-csrf")).saved());
        byte[] vaultBefore = Files.readAllBytes(vault);
        AtomicInteger machineIdentityReads = new AtomicInteger();
        BiliCredentialStore observedStore = new BiliCredentialStore(vault, () -> {
            machineIdentityReads.incrementAndGet();
            return Optional.of("machine-a");
        }, new SecureRandom());

        BiliConfig.load(settings, observedStore,
                new BiliCredentialStore.Credentials("property-session", "SESSDATA=property-session; bili_jct=p"));

        assertEquals("property-session", BiliApiClient.sessdata);
        assertEquals("SESSDATA=property-session; bili_jct=p", BiliApiClient.webCookie);
        assertEquals(0, machineIdentityReads.get(), "runtime properties must bypass the disk credential store");
        assertArrayEquals(vaultBefore, Files.readAllBytes(vault));
        assertFalse(Files.exists(settings), "loading runtime credentials must not create a settings file");
    }

    @Test
    void wrongMachineAndLegacyPlaintextBothFailClosed() throws Exception {
        Path settings = temporaryDirectory.resolve("config/net_music_can_play_bili.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, """
                {"sessdata":"legacy-secret","webCookie":"SESSDATA=legacy-secret","dolbyEnabled":true}
                """, StandardCharsets.UTF_8);
        Path vault = temporaryDirectory.resolve("game/net_music_can_play_bili-private/bili-credentials.json.enc");
        assertTrue(store(vault, "machine-a").save(new BiliCredentialStore.Credentials(
                "encrypted-secret", "SESSDATA=encrypted-secret")).saved());

        BiliConfig.load(settings, store(vault, "machine-b"), NO_RUNTIME_CREDENTIALS);

        assertEquals("", BiliApiClient.sessdata);
        assertEquals("", BiliApiClient.webCookie);
        String scrubbed = Files.readString(settings, StandardCharsets.UTF_8);
        assertFalse(scrubbed.contains("legacy-secret"));
        assertFalse(scrubbed.contains("sessdata"));
        assertFalse(scrubbed.contains("webCookie"));
    }

    @Test
    void corruptOrNonObjectSettingsAreRebuiltWithoutBlockingEncryptedCredentialLoading() throws Exception {
        Path settings = temporaryDirectory.resolve("config/net_music_can_play_bili.json");
        Files.createDirectories(settings.getParent());
        Path vault = temporaryDirectory.resolve("game/net_music_can_play_bili-private/bili-credentials.json.enc");
        BiliCredentialStore store = store(vault, "machine-a");
        assertTrue(BiliConfig.saveCredentials(store, new BiliCredentialStore.Credentials(
                "stored-session", "SESSDATA=stored-session; bili_jct=stored-csrf")).saved());

        for (String unusableSettings : java.util.List.of(
                "{\"sessdata\":\"truncated-legacy-secret\",\"webCookie\":\"SESSDATA=truncated-legacy-secret\"",
                "[{\"sessdata\":\"array-legacy-secret\",\"webCookie\":\"SESSDATA=array-legacy-secret\"}]")) {
            Files.writeString(settings, unusableSettings, StandardCharsets.UTF_8);

            BiliConfig.load(settings, store, NO_RUNTIME_CREDENTIALS);

            assertEquals("stored-session", BiliApiClient.sessdata);
            assertEquals("SESSDATA=stored-session; bili_jct=stored-csrf", BiliApiClient.webCookie);
            String rebuilt = Files.readString(settings, StandardCharsets.UTF_8);
            assertFalse(rebuilt.contains("legacy-secret"));
            JsonObject rebuiltRoot = JsonParser.parseString(rebuilt).getAsJsonObject();
            assertFalse(rebuiltRoot.has("sessdata"));
            assertFalse(rebuiltRoot.has("webCookie"));
            assertTrue(rebuiltRoot.has("dolbyEnabled"));
            assertTrue(rebuiltRoot.has("cdnPreference"));
        }
    }

    @Test
    void webCookieOnlyCredentialRestoresSessdataForConsumers() {
        Path settings = temporaryDirectory.resolve("config/net_music_can_play_bili.json");

        BiliConfig.load(settings, store(temporaryDirectory.resolve("vault.enc"), "machine-a"),
                new BiliCredentialStore.Credentials("", "bili_jct=csrf; SESSDATA=from-cookie; DedeUserID=42"));

        assertEquals("from-cookie", BiliApiClient.sessdata);
        assertEquals("bili_jct=csrf; SESSDATA=from-cookie; DedeUserID=42", BiliApiClient.webCookie);
    }

    @Test
    void failedAtomicSettingsReplacementCleansTemporaryFile() throws Exception {
        Path targetDirectory = Files.createDirectories(temporaryDirectory.resolve("settings-target"));

        assertFalse(BiliConfig.save(targetDirectory));

        assertTrue(Files.isDirectory(targetDirectory));
        try (var entries = Files.list(temporaryDirectory)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith("settings-target.")));
        }
    }

    private static BiliCredentialStore store(Path path, String machineIdentity) {
        return new BiliCredentialStore(path, () -> Optional.of(machineIdentity), new SecureRandom());
    }

    private record Snapshot(String sessdata, String webCookie, boolean dolbyEnabled, boolean dolbyJocEnabled,
            int dolbyMaxObjectSources, double stereoCrossfeed, String userAgent, boolean rotateUserAgent) {
    }
}
