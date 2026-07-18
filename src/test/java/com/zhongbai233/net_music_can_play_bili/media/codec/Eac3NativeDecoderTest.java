package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Eac3NativeDecoderTest {

    @TempDir
    Path tempDir;

    @Test
    void pruneKeepsOnlyCurrentBundleDirectory() throws Exception {
        Path platformDir = Files.createDirectories(tempDir.resolve("macos-arm64"));
        Path current = Files.createDirectories(platformDir.resolve("current-fingerprint"));
        Path obsolete = Files.createDirectories(platformDir.resolve("obsolete-fingerprint").resolve("nested"));
        Files.writeString(current.resolve("libavutil.61.dylib"), "current");
        Files.writeString(obsolete.resolve("old.dylib"), "obsolete");
        Path legacyNative = Files.writeString(platformDir.resolve("libavcodec.62.dylib"), "legacy");
        Path unrelatedFile = Files.writeString(platformDir.resolve("keep-me.txt"), "unrelated");

        NativeBundleDirectories.pruneObsoleteBundles(platformDir, "current-fingerprint");

        assertTrue(Files.isDirectory(current));
        assertTrue(Files.isRegularFile(current.resolve("libavutil.61.dylib")));
        assertFalse(Files.exists(platformDir.resolve("obsolete-fingerprint")));
        assertFalse(Files.exists(legacyNative));
        assertTrue(Files.isRegularFile(unrelatedFile));
    }

    @Test
    void pruneRemovesKnownWindowsLegacyFilesButKeepsUnknownFiles() throws Exception {
        Path platformDir = Files.createDirectories(tempDir.resolve("windows-x86_64"));
        Files.createDirectories(platformDir.resolve("current-fingerprint"));
        Path oldCodec = Files.writeString(platformDir.resolve("avcodec-62.dll"), "old");
        Path oldJni = Files.writeString(platformDir.resolve("video_jni.dll"), "old");
        Path unrelatedDll = Files.writeString(platformDir.resolve("third-party.dll"), "keep");

        NativeBundleDirectories.pruneObsoleteBundles(platformDir, "current-fingerprint");

        assertFalse(Files.exists(oldCodec));
        assertFalse(Files.exists(oldJni));
        assertTrue(Files.isRegularFile(unrelatedDll));
    }

    @Test
    void pruneRefusesToRunWithoutCurrentBundle() throws Exception {
        Path platformDir = Files.createDirectories(tempDir.resolve("linux-x86_64"));
        Path obsolete = Files.createDirectories(platformDir.resolve("obsolete-fingerprint"));

        assertThrows(Exception.class,
                () -> NativeBundleDirectories.pruneObsoleteBundles(platformDir, "missing-fingerprint"));
        assertTrue(Files.isDirectory(obsolete));
    }
}