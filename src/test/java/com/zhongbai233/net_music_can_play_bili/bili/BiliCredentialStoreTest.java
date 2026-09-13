package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliCredentialStoreTest {
    private static final String MACHINE_A = "linux:0123456789abcdef0123456789abcdef";
    private static final String MACHINE_B = "linux:fedcba9876543210fedcba9876543210";
    private static final BiliCredentialStore.Credentials CREDENTIALS = new BiliCredentialStore.Credentials(
            "session-token", "SESSDATA=session-token; bili_jct=csrf-token");

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingFileIsAbsentAndDoesNotCreateDirectories() {
        Path file = credentialPath();
        BiliCredentialStore store = store(file, MACHINE_A);

        BiliCredentialStore.LoadResult result = store.load();

        assertEquals(BiliCredentialStore.LoadStatus.ABSENT, result.status());
        assertTrue(result.credentials().isEmpty());
        assertFalse(Files.exists(file.getParent()));
    }

    @Test
    void savesAtomicallyAndReloadsCredentials() throws Exception {
        Path file = credentialPath();
        BiliCredentialStore store = store(file, MACHINE_A);

        assertEquals(BiliCredentialStore.SaveStatus.SAVED, store.save(CREDENTIALS).status());
        BiliCredentialStore.LoadResult loaded = store(file, MACHINE_A).load();

        assertTrue(loaded.loaded());
        assertEquals(Optional.of(CREDENTIALS), loaded.credentials());
        assertTrue(Files.isRegularFile(file));
        try (var children = Files.list(file.getParent())) {
            assertEquals(1L, children.count());
        }
    }

    @Test
    void copiedCredentialsFailClosedAndAreNotOverwritten() throws Exception {
        Path file = credentialPath();
        assertTrue(store(file, MACHINE_A).save(CREDENTIALS).saved());
        byte[] before = Files.readAllBytes(file);

        BiliCredentialStore.LoadResult result = store(file, MACHINE_B).load();

        assertEquals(BiliCredentialStore.LoadStatus.INVALID_OR_WRONG_MACHINE, result.status());
        assertTrue(result.credentials().isEmpty());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void tamperedAndOversizedFilesFailClosed() throws Exception {
        Path tampered = credentialPath();
        Files.createDirectories(tampered.getParent());
        Files.writeString(tampered, "{\"format\":\"wrong\"}", StandardCharsets.UTF_8);
        assertEquals(BiliCredentialStore.LoadStatus.INVALID_OR_WRONG_MACHINE,
                store(tampered, MACHINE_A).load().status());

        Path oversized = temporaryDirectory.resolve("oversized.enc");
        Files.write(oversized, new byte[BiliCredentialCodec.MAX_ENVELOPE_BYTES + 1]);
        assertEquals(BiliCredentialStore.LoadStatus.INVALID_OR_WRONG_MACHINE,
                store(oversized, MACHINE_A).load().status());
    }

    @Test
    void unavailableMachineIdentityNeverReadsOrWritesCredentials() throws Exception {
        Path file = credentialPath();
        BiliCredentialStore unavailable = new BiliCredentialStore(file, Optional::empty, new SecureRandom());

        assertEquals(BiliCredentialStore.SaveStatus.MACHINE_ID_UNAVAILABLE,
                unavailable.save(CREDENTIALS).status());
        assertFalse(Files.exists(file));

        assertTrue(store(file, MACHINE_A).save(CREDENTIALS).saved());
        byte[] before = Files.readAllBytes(file);
        assertEquals(BiliCredentialStore.LoadStatus.MACHINE_ID_UNAVAILABLE, unavailable.load().status());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void emptyCredentialsAreNotPersisted() {
        Path file = credentialPath();

        BiliCredentialStore.SaveResult result = store(file, MACHINE_A)
                .save(new BiliCredentialStore.Credentials(" ", null));

        assertEquals(BiliCredentialStore.SaveStatus.INVALID_CREDENTIALS, result.status());
        assertFalse(Files.exists(file));
    }

    @Test
    void clearRemovesOnlyTheCredentialFile() throws Exception {
        Path file = credentialPath();
        Path sibling = file.getParent().resolve("keep.txt");
        assertTrue(store(file, MACHINE_A).save(CREDENTIALS).saved());
        Files.writeString(sibling, "keep", StandardCharsets.UTF_8);

        assertTrue(store(file, MACHINE_A).clear());

        assertFalse(Files.exists(file));
        assertTrue(Files.exists(sibling));
    }

    @Test
    void posixFilesAreOwnerOnly() throws Exception {
        Path file = credentialPath();
        assertTrue(store(file, MACHINE_A).save(CREDENTIALS).saved());
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) == null) {
            return;
        }

        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file));
        Set<PosixFilePermission> expectedDirectory = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
        assertEquals(expectedDirectory, Files.getPosixFilePermissions(file.getParent()));
    }

    private Path credentialPath() {
        return temporaryDirectory.resolve("game/net_music_can_play_bili-private/bili-credentials.json.enc");
    }

    private static BiliCredentialStore store(Path file, String identity) {
        return new BiliCredentialStore(file, () -> Optional.of(identity), new SecureRandom());
    }
}
