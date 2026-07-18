package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Native bundle 指纹目录的安全清理工具。 */
final class NativeBundleDirectories {

    private static final List<Pattern> LEGACY_NATIVE_FILE_PATTERNS = List.of(
            Pattern.compile("^(avutil|swscale|avcodec|swresample)-\\d+\\.dll$"),
            Pattern.compile("^lib(avutil|swscale|avcodec|swresample)\\.\\d+\\.dylib$"),
            Pattern.compile("^lib(avutil|swscale|avcodec|swresample)\\.so\\.\\d+$"));
    private static final Set<String> LEGACY_NATIVE_FILE_NAMES = Set.of(
            "eac3_jni.dll", "video_jni.dll", "libiconv-2.dll", "libwinpthread-1.dll",
            "libeac3_jni.dylib", "libvideo_jni.dylib",
            "libeac3_jni.so", "libvideo_jni.so");

    private NativeBundleDirectories() {
    }

    /**
     * 仅保留当前平台的当前指纹目录，并移除旧版 loader 留在平台根目录的已知 native 文件。
     * 未知普通文件、符号链接和其他平台目录均不触碰。
     */
    static void pruneObsoleteBundles(Path platformNativeDir, String currentFingerprint) throws IOException {
        Path normalizedRoot = platformNativeDir.toAbsolutePath().normalize();
        Path currentDir = normalizedRoot.resolve(currentFingerprint).normalize();
        if (!currentDir.getParent().equals(normalizedRoot) || !Files.isDirectory(currentDir)) {
            throw new IOException("当前 native bundle 目录无效: " + currentDir);
        }

        try (var children = Files.list(normalizedRoot)) {
            for (Path child : children.toList()) {
                Path normalizedChild = child.toAbsolutePath().normalize();
                if (normalizedChild.equals(currentDir)
                        || !Files.isDirectory(normalizedChild, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                deleteDirectoryTree(normalizedChild);
            }
        }

        try (var children = Files.list(normalizedRoot)) {
            for (Path child : children.toList()) {
                if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                        && isLegacyNativeFile(child.getFileName().toString())) {
                    Files.deleteIfExists(child);
                }
            }
        }
    }

    private static boolean isLegacyNativeFile(String fileName) {
        return LEGACY_NATIVE_FILE_NAMES.contains(fileName)
                || LEGACY_NATIVE_FILE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(fileName).matches());
    }

    private static void deleteDirectoryTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            List<Path> descending = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : descending) {
                Files.deleteIfExists(path);
            }
        }
    }
}