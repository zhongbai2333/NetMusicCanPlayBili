package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual current OS identity backend and crypto/temp-file storage. No defaults, user credentials or machine ID output. */
class ProviderFunctionalClosureOsCredentialTest {
    @TempDir Path directory;
    @Test void actualCurrentOsIdentityBackendEncryptsAndReloadsOnlyFabricatedTemporaryCredentials() throws Exception {
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
        assertTrue(os.contains("mac")||os.contains("darwin")||os.startsWith("windows")||os.contains("linux"),"Actual identity backend needs a supported OS; no skipped fake success");
        var first=BiliMachineIdentity.current();var second=BiliMachineIdentity.current();
        assertTrue(first.isPresent(),"Actual OS installation identity must be available in this execution environment");
        assertTrue(first.equals(second),"Actual OS installation identity must be stable across backend reads");
        Path file=directory.resolve("fake.encrypted.json");
        var store=new BiliCredentialStore(file,()->first,new SecureRandom());
        var fabricated=new BiliCredentialStore.Credentials("fabricated-functional-test","SESSDATA=fabricated-functional-test; synthetic=value");
        assertTrue(store.save(fabricated).saved());assertTrue(Files.isRegularFile(file));
        var encrypted=new String(Files.readAllBytes(file),StandardCharsets.UTF_8);
        assertFalse(encrypted.contains("fabricated-functional-test"));
        assertFalse(encrypted.contains(first.orElseThrow()),"Machine installation identity must not be persisted as plaintext");
        var loaded=store.load();assertTrue(loaded.loaded());assertTrue(loaded.credentials().orElseThrow().equals(fabricated),"Fabricated credential must reload with actual OS binding");
        var foreign=new BiliCredentialStore(file,()->Optional.of("test-foreign-machine-identity"),new SecureRandom());
        assertFalse(foreign.load().loaded());assertTrue(store.load().loaded());
        assertTrue(store.clear());assertFalse(Files.exists(file));
        try(var paths=Files.list(directory)){assertEquals(0,paths.count());}
    }
}
