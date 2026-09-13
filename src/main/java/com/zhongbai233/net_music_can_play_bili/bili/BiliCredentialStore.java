package com.zhongbai233.net_music_can_play_bili.bili;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Stores Bilibili credentials outside {@code config/}, encrypted for the current OS installation. */
public final class BiliCredentialStore {
    private static final Logger LOGGER = Logger.getLogger(BiliCredentialStore.class.getName());
    private static final String PRIVATE_DIRECTORY = "net_music_can_play_bili-private";
    private static final String CREDENTIAL_FILE = "bili-credentials.json.enc";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path file;
    private final Supplier<Optional<String>> machineIdentity;
    private final SecureRandom random;

    BiliCredentialStore(Path file, Supplier<Optional<String>> machineIdentity, SecureRandom random) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.machineIdentity = Objects.requireNonNull(machineIdentity, "machineIdentity");
        this.random = Objects.requireNonNull(random, "random");
    }

    public static BiliCredentialStore defaults() {
        return new BiliCredentialStore(defaultPath(), BiliMachineIdentity::current, new SecureRandom());
    }

    public static Path defaultPath() {
        return FMLPaths.GAMEDIR.get().resolve(PRIVATE_DIRECTORY).resolve(CREDENTIAL_FILE);
    }

    public Path path() {
        return file;
    }

    /** Reads credentials without ever falling back to plaintext or overwriting an unreadable file. */
    public synchronized LoadResult load() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return LoadResult.withoutCredentials(LoadStatus.ABSENT);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            LOGGER.warning("B站加密登录凭据路径不是普通文件: " + file);
            return LoadResult.withoutCredentials(LoadStatus.IO_ERROR);
        }
        Optional<String> identity = resolveMachineIdentity();
        if (identity.isEmpty()) {
            LOGGER.warning("无法取得可靠机器标识，已拒绝读取持久化 B站登录凭据");
            return LoadResult.withoutCredentials(LoadStatus.MACHINE_ID_UNAVAILABLE);
        }

        try {
            long size = Files.size(file);
            if (size <= 0 || size > BiliCredentialCodec.MAX_ENVELOPE_BYTES) {
                LOGGER.warning("B站加密登录凭据格式无效或文件过大: " + file);
                return LoadResult.withoutCredentials(LoadStatus.INVALID_OR_WRONG_MACHINE);
            }
            byte[] encoded = Files.readAllBytes(file);
            Credentials credentials = BiliCredentialCodec.decrypt(encoded, identity.get());
            return new LoadResult(LoadStatus.LOADED, Optional.of(credentials));
        } catch (GeneralSecurityException | RuntimeException error) {
            // Authentication failure intentionally combines corruption and another-machine cases.
            LOGGER.warning("B站加密登录凭据无法在本机验证；文件可能损坏或来自另一台机器: " + file);
            return LoadResult.withoutCredentials(LoadStatus.INVALID_OR_WRONG_MACHINE);
        } catch (IOException error) {
            LOGGER.log(Level.WARNING, "读取 B站加密登录凭据失败: " + file, error);
            return LoadResult.withoutCredentials(LoadStatus.IO_ERROR);
        }
    }

    /** Atomically replaces the encrypted credentials after an explicit successful login. */
    public synchronized SaveResult save(Credentials credentials) {
        Objects.requireNonNull(credentials, "credentials");
        if (!credentials.hasLogin()) {
            return new SaveResult(SaveStatus.INVALID_CREDENTIALS);
        }
        Optional<String> identity = resolveMachineIdentity();
        if (identity.isEmpty()) {
            LOGGER.warning("无法取得可靠机器标识；B站登录仅在当前游戏进程有效，未写入磁盘");
            return new SaveResult(SaveStatus.MACHINE_ID_UNAVAILABLE);
        }

        Path parent = file.getParent();
        if (parent == null) {
            LOGGER.warning("B站加密登录凭据路径缺少父目录: " + file);
            return new SaveResult(SaveStatus.IO_ERROR);
        }

        Path temporary = null;
        try {
            byte[] encoded = BiliCredentialCodec.encrypt(credentials, identity.get(), random);
            Credentials verified = BiliCredentialCodec.decrypt(encoded, identity.get());
            if (!credentials.equals(verified)) {
                throw new GeneralSecurityException("credential encryption self-check failed");
            }
            createPrivateDirectories(parent);
            tightenPermissions(parent, DIRECTORY_PERMISSIONS);
            temporary = createPrivateTemporaryFile(parent);
            tightenPermissions(temporary, FILE_PERMISSIONS);
            writeAndForce(temporary, encoded);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
            tightenPermissions(file, FILE_PERMISSIONS);
            return new SaveResult(SaveStatus.SAVED);
        } catch (GeneralSecurityException | IOException | RuntimeException error) {
            LOGGER.log(Level.WARNING, "保存 B站加密登录凭据失败: " + file, error);
            return new SaveResult(SaveStatus.IO_ERROR);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupError) {
                    LOGGER.log(Level.FINE, "清理 B站登录凭据临时文件失败: " + temporary, cleanupError);
                }
            }
        }
    }

    public synchronized boolean clear() {
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException | SecurityException error) {
            LOGGER.log(Level.WARNING, "清除 B站加密登录凭据失败: " + file, error);
            return false;
        }
    }

    private Optional<String> resolveMachineIdentity() {
        try {
            Optional<String> resolved = machineIdentity.get();
            if (resolved == null || resolved.isEmpty() || resolved.get().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(resolved.get().trim());
        } catch (RuntimeException error) {
            LOGGER.log(Level.FINE, "获取机器标识时发生异常", error);
            return Optional.empty();
        }
    }

    private static void writeAndForce(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer source = ByteBuffer.wrap(bytes);
            while (source.hasRemaining()) {
                channel.write(source);
            }
            channel.force(true);
        }
    }

    private static void createPrivateDirectories(Path path) throws IOException {
        try {
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        } catch (UnsupportedOperationException unsupported) {
            Files.createDirectories(path);
        }
    }

    private static Path createPrivateTemporaryFile(Path parent) throws IOException {
        try {
            return Files.createTempFile(parent, CREDENTIAL_FILE + ".", ".tmp",
                    PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        } catch (UnsupportedOperationException unsupported) {
            return Files.createTempFile(parent, CREDENTIAL_FILE + ".", ".tmp");
        }
    }

    private static void tightenPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            // Windows and non-POSIX file systems retain their inherited ACLs; AES-GCM remains mandatory.
            LOGGER.fine("无法为本地 B站登录凭据设置 POSIX 权限: " + path);
        }
    }

    public record Credentials(String sessdata, String webCookie) {
        public Credentials {
            sessdata = normalize(sessdata);
            webCookie = normalize(webCookie);
        }

        public boolean hasLogin() {
            return !sessdata.isBlank() || !webCookie.isBlank();
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public enum LoadStatus {
        ABSENT,
        LOADED,
        MACHINE_ID_UNAVAILABLE,
        INVALID_OR_WRONG_MACHINE,
        IO_ERROR
    }

    public enum SaveStatus {
        SAVED,
        MACHINE_ID_UNAVAILABLE,
        INVALID_CREDENTIALS,
        IO_ERROR
    }

    public record LoadResult(LoadStatus status, Optional<Credentials> credentials) {
        public LoadResult {
            Objects.requireNonNull(status, "status");
            credentials = Objects.requireNonNull(credentials, "credentials");
            if ((status == LoadStatus.LOADED) != credentials.isPresent()) {
                throw new IllegalArgumentException("loaded status and credentials must agree");
            }
        }

        public boolean loaded() {
            return status == LoadStatus.LOADED;
        }

        private static LoadResult withoutCredentials(LoadStatus status) {
            return new LoadResult(status, Optional.empty());
        }
    }

    public record SaveResult(SaveStatus status) {
        public SaveResult {
            Objects.requireNonNull(status, "status");
        }

        public boolean saved() {
            return status == SaveStatus.SAVED;
        }
    }
}
