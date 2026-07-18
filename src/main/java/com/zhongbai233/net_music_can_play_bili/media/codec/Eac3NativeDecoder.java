package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * E-AC-3 原生解码器，封装 FFmpeg libavcodec
 */
public class Eac3NativeDecoder implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern WINDOWS_FFMPEG_LIB = Pattern
            .compile("^(avutil|swscale|avcodec)-(\\d+)\\.dll$");
    private static final Pattern MACOS_FFMPEG_LIB = Pattern
            .compile("^lib(avutil|swscale|avcodec)\\.(\\d+)\\.dylib$");
    private static final Pattern LINUX_FFMPEG_LIB = Pattern
            .compile("^lib(avutil|swscale|avcodec)\\.so\\.(\\d+)$");

    private static volatile boolean loaderInitialized;
    private static volatile boolean nativeAvailable;
    private static volatile String ffmpegVersion;

    private long handle; // DecoderHandle*
    private boolean open;
    private int openFailures;
    private int decodeFailures;
    private long totalFrames;
    private int lastNbSamples;
    private int lastNbChannels;

    public Eac3NativeDecoder() {
        ensureLoaderReady();
    }

    // ── 公开 API ──

    /**
     * 解码一帧 E-AC-3。
     *
     * @return float PCM [sample][channel]，通常为 1536×6 (5.1)；失败返回 null
     */
    public float[][] decodeFrame(byte[] ec3Frame) {
        if (!nativeAvailable)
            return null;
        if (!ensureOpen())
            return null;

        // JNI 层返回 planar PCM: [channel][sample]；上层播放器沿用 [sample][channel]。
        float[][] planar = Eac3Jni.decode(handle, ec3Frame, 0, ec3Frame.length);
        if (planar == null) {
            if (decodeFailures++ < 3) {
                LOGGER.warn("Eac3Native decode 失败 (连续 {} 次)", decodeFailures);
            }
            return null;
        }

        decodeFailures = 0;
        totalFrames++;

        int channels = planar.length;
        int samples = channels > 0 ? planar[0].length : 0;
        float[][] pcm = new float[samples][channels];
        for (int ch = 0; ch < channels; ch++) {
            float[] src = planar[ch];
            if (src == null)
                continue;
            int n = Math.min(samples, src.length);
            for (int i = 0; i < n; i++) {
                pcm[i][ch] = src[i];
            }
        }

        if (samples != lastNbSamples || channels != lastNbChannels) {
            LOGGER.debug("Eac3Native 解码: {}samples × {}ch (FFmpeg {})",
                    samples, channels, ffmpegVersion);
            lastNbSamples = samples;
            lastNbChannels = channels;
        }

        return pcm;
    }

    /** 重置解码器状态（seek / 切歌后调用）。 */
    public void flush() {
        if (open && handle != 0) {
            Eac3Jni.flush(handle);
        }
    }

    @Override
    public void close() {
        open = false;
        if (handle != 0) {
            Eac3Jni.close(handle);
            handle = 0;
        }
        LOGGER.debug("Eac3Native 已关闭 (解码 {} 帧)", totalFrames);
    }

    /** 已成功解码的总帧数 */
    public long totalFrames() {
        return totalFrames;
    }

    // ── 内部 ──

    /** 预加载 FFmpeg native 库，幂等。建议在 mdat 到达前调用，避免 worker 线程被初始化阻塞 */
    public static synchronized void preload() {
        ensureLoaderReady();
    }

    /** FFmpeg native 库是否成功加载，Dolby 管线由此决定是否可用 */
    public static boolean isNativeAvailable() {
        ensureLoaderReady();
        return nativeAvailable;
    }

    private static synchronized void ensureLoaderReady() {
        if (loaderInitialized)
            return;
        try {
            loadEmbeddedNatives();
            ffmpegVersion = Eac3Jni.version();
            nativeAvailable = true;
            LOGGER.info("FFmpeg media native 解码器加载成功: {}", ffmpegVersion);
        } catch (Throwable e) {
            nativeAvailable = false;
            LOGGER.error("FFmpeg media native 加载失败，Dolby/视频原生解码将不可用，自动降级。", e);
        } finally {
            loaderInitialized = true;
        }
    }

    /** 从 jar 资源中提取并加载平台对应的 native 库 */
    private static void loadEmbeddedNatives() throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        boolean isArm = arch.contains("aarch64") || arch.contains("arm");

        // 确定平台目录
        String platformDir;
        boolean isWindows;
        if (os.contains("win")) {
            platformDir = isArm ? "windows-arm64" : "windows-x86_64";
            isWindows = true;
        } else if (os.contains("mac") || os.contains("darwin")) {
            platformDir = isArm ? "macos-arm64" : "macos-x86_64";
            isWindows = false;
        } else {
            platformDir = isArm ? "linux-arm64" : "linux-x86_64";
            isWindows = false;
        }

        NativeLibrarySet libraries = discoverNativeLibraries(platformDir, os, isWindows);
        String bundleFingerprint = nativeBundleFingerprint();
        // Windows MinGW 运行时依赖必须先显式加载；FFmpeg x64/ARM64 构建均依赖
        // libiconv，avutil 还可能依赖 libwinpthread。顺序保持在 FFmpeg DLL 之前。
        String[] runtimeLibs = isWindows ? new String[] { "libiconv-2", "libwinpthread-1" } : new String[0];
        boolean[] runtimeLibPresent = new boolean[runtimeLibs.length];

        // ── 提取到 config 目录（避免 temp 被杀软拦截 DLL 加载；平台子目录隔离多版本）──
        Path platformNativeDir = gameConfigDir().resolve("net_music_can_play_bili").resolve("natives")
                .resolve(platformDir);
        Path nativeDir = platformNativeDir.resolve(bundleFingerprint);
        Files.createDirectories(nativeDir);

        // ── 提取所有文件；如果 jar 内资源更新，覆盖 config 中旧版本，避免用户残留旧 DLL ──
        for (int i = 0; i < runtimeLibs.length; i++) {
            String fileName = nativeFileName(runtimeLibs[i], os, isWindows);
            runtimeLibPresent[i] = extractEmbeddedNative(platformDir, fileName, nativeDir.resolve(fileName), false);
        }
        for (String fileName : libraries.ffmpegLibraries()) {
            extractEmbeddedNative(platformDir, fileName, nativeDir.resolve(fileName), true);
        }
        for (String fileName : libraries.jniLibraries()) {
            extractEmbeddedNative(platformDir, fileName, nativeDir.resolve(fileName), true);
        }

        // 当前 bundle 已完整提取后再清理旧指纹目录。失败只影响磁盘整洁度，不能阻断 native 加载。
        try {
            NativeBundleDirectories.pruneObsoleteBundles(platformNativeDir, bundleFingerprint);
        } catch (IOException cleanupError) {
            LOGGER.warn("清理旧 FFmpeg native bundle 失败: path={}", platformNativeDir, cleanupError);
        }

        // ── 加载所有库 ──
        // 可选运行时依赖必须先显式加载；Windows 不保证会从当前 DLL 所在目录解析二级依赖。
        for (int i = 0; i < runtimeLibs.length; i++) {
            if (runtimeLibPresent[i]) {
                String fn = nativeFileName(runtimeLibs[i], os, isWindows);
                LOGGER.debug("加载 native 运行时依赖: {}", nativeDir.resolve(fn));
                System.load(nativeDir.resolve(fn).toAbsolutePath().toString());
            }
        }
        for (String fn : libraries.ffmpegLibraries()) {
            LOGGER.debug("加载 FFmpeg native 库: {}", nativeDir.resolve(fn));
            System.load(nativeDir.resolve(fn).toAbsolutePath().toString());
        }
        for (String fn : libraries.jniLibraries()) {
            LOGGER.debug("加载 JNI native 库: {}", nativeDir.resolve(fn));
            System.load(nativeDir.resolve(fn).toAbsolutePath().toString());
        }

        LOGGER.info("FFmpeg native 库提取并加载: os={}, arch={}, bundle={}, path={}",
                os, arch, bundleFingerprint, nativeDir);
    }

    private static boolean extractEmbeddedNative(String platformDir, String fileName, Path target, boolean required)
            throws IOException {
        String resPath = "/native/" + platformDir + "/" + fileName;
        try (InputStream in = Eac3NativeDecoder.class.getResourceAsStream(resPath)) {
            InputStream source = in != null ? in : openFilesystemNativeResource(platformDir, fileName);
            if (source == null) {
                if (required) {
                    throw new IOException("内嵌 native 库缺失: " + resPath);
                }
                return false;
            }

            byte[] bundled;
            try (source) {
                bundled = source.readAllBytes();
            }
            if (!Files.exists(target) || !Arrays.equals(Files.readAllBytes(target), bundled)) {
                writeNativeAtomically(target, bundled);
            }
            return true;
        }
    }

    /**
     * 使用全新 inode 发布 native 文件。macOS 会缓存 Mach-O 的代码签名验证状态，不能用
     * TRUNCATE_EXISTING 原地覆盖旧 dylib，否则旧版本的 vnode/扩展属性可能影响新签名。
     */
    private static void writeNativeAtomically(Path target, byte[] bundled) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        boolean moved = false;
        try {
            Files.write(temp, bundled);
            temp.toFile().setReadable(true, false);
            temp.toFile().setExecutable(true, false);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    /**
     * README 记录整套六平台 native 的发布版本和校验和，以其内容指纹作为 bundle 目录名。
     * 更新 native 时必须同步更新该文件，确保任何平台都切换到全新路径。
     */
    private static String nativeBundleFingerprint() throws IOException {
        String resPath = "/native/README.md";
        try (InputStream in = Eac3NativeDecoder.class.getResourceAsStream(resPath)) {
            InputStream source = in != null ? in : openFilesystemNativeResource("README.md");
            if (source == null) {
                throw new IOException("内嵌 native 指纹文件缺失: " + resPath);
            }
            try (source) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.readAllBytes());
                return java.util.HexFormat.of().formatHex(digest, 0, 8);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
            }
        }
    }

    private static InputStream openFilesystemNativeResource(String platformDir, String fileName) throws IOException {
        return openFilesystemNativeResource(platformDir + "/" + fileName);
    }

    private static InputStream openFilesystemNativeResource(String relativeResourcePath) throws IOException {
        CodeSource codeSource = Eac3NativeDecoder.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return null;
        }
        try {
            Path workspace = findWorkspaceRoot(Path.of(codeSource.getLocation().toURI()));
            if (workspace == null) {
                return null;
            }
            Path relative = Path.of("native").resolve(relativeResourcePath.replace('/', java.io.File.separatorChar));
            for (Path root : List.of(workspace.resolve("build").resolve("resources").resolve("main"),
                    workspace.resolve("src").resolve("main").resolve("resources"))) {
                Path candidate = root.resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return Files.newInputStream(candidate);
                }
            }
            return null;
        } catch (URISyntaxException e) {
            throw new IOException("native 资源 code source URI 无效", e);
        }
    }

    private static NativeLibrarySet discoverNativeLibraries(String platformDir, String os, boolean isWindows)
            throws IOException {
        Set<String> resourceNames = listNativeResourceFileNames(platformDir);
        boolean isMac = os.contains("mac") || os.contains("darwin");
        Pattern ffmpegPattern = isWindows ? WINDOWS_FFMPEG_LIB : (isMac ? MACOS_FFMPEG_LIB : LINUX_FFMPEG_LIB);
        List<String> ffmpeg = new ArrayList<>();
        for (String base : List.of("avutil", "swscale", "avcodec")) {
            ffmpeg.add(selectVersionedLibrary(resourceNames, ffmpegPattern, base, platformDir));
        }

        List<String> jni = List.of(
                nativeFileName("eac3_jni", os, isWindows),
                nativeFileName("video_jni", os, isWindows));
        for (String fileName : jni) {
            if (!resourceNames.contains(fileName)) {
                throw new IOException("内嵌 JNI native 库缺失: /native/" + platformDir + "/" + fileName);
            }
        }
        return new NativeLibrarySet(List.copyOf(ffmpeg), jni);
    }

    private static String selectVersionedLibrary(Set<String> resourceNames, Pattern pattern, String base,
            String platformDir) throws IOException {
        return resourceNames.stream()
                .map(pattern::matcher)
                .filter(matcher -> matcher.matches())
                .filter(matcher -> matcher.group(1).equals(base))
                .max(Comparator.comparingInt(matcher -> Integer.parseInt(matcher.group(2))))
                .map(matcher -> matcher.group())
                .orElseThrow(() -> new IOException("内嵌 FFmpeg native 库缺失: /native/" + platformDir
                        + "/" + base + " (versioned)"));
    }

    private static Set<String> listNativeResourceFileNames(String platformDir) throws IOException {
        String resourceDir = "native/" + platformDir;
        ClassLoader loader = Eac3NativeDecoder.class.getClassLoader();
        Set<String> names = new HashSet<>();
        Enumeration<URL> urls = loader.getResources(resourceDir);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                listFileResourceNames(url, names);
            } else if ("jar".equals(protocol)) {
                listJarResourceNames(url, resourceDir, names);
            }
        }
        if (names.isEmpty()) {
            listCodeSourceResourceNames(resourceDir, names);
        }
        return names;
    }

    private static void listFileResourceNames(URL url, Set<String> names) throws IOException {
        try {
            Path dir = Path.of(url.toURI());
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .forEach(names::add);
            }
        } catch (URISyntaxException e) {
            throw new IOException("native 资源目录 URI 无效: " + url, e);
        }
    }

    private static void listJarResourceNames(URL url, String resourceDir, Set<String> names) throws IOException {
        JarURLConnection connection = (JarURLConnection) url.openConnection();
        String prefix = resourceDir.endsWith("/") ? resourceDir : resourceDir + "/";
        try (JarFile jar = connection.getJarFile()) {
            listJarEntries(jar, prefix, names);
        }
    }

    private static void listCodeSourceResourceNames(String resourceDir, Set<String> names) throws IOException {
        CodeSource codeSource = Eac3NativeDecoder.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return;
        }
        try {
            Path location = Path.of(codeSource.getLocation().toURI());
            if (Files.isDirectory(location)) {
                Path dir = location.resolve(resourceDir.replace('/', java.io.File.separatorChar));
                listNativeResourceDirectory(dir, names);
                Path workspace = findWorkspaceRoot(location);
                if (workspace != null) {
                    listNativeResourceDirectory(workspace.resolve("build").resolve("resources").resolve("main")
                            .resolve(resourceDir.replace('/', java.io.File.separatorChar)), names);
                    listNativeResourceDirectory(workspace.resolve("src").resolve("main").resolve("resources")
                            .resolve(resourceDir.replace('/', java.io.File.separatorChar)), names);
                }
            } else if (Files.isRegularFile(location) && location.getFileName().toString().endsWith(".jar")) {
                String prefix = resourceDir.endsWith("/") ? resourceDir : resourceDir + "/";
                try (JarFile jar = new JarFile(location.toFile())) {
                    listJarEntries(jar, prefix, names);
                }
            }
        } catch (URISyntaxException e) {
            throw new IOException("native 资源 code source URI 无效", e);
        }
    }

    private static Path findWorkspaceRoot(Path location) {
        Path current = location.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("build.gradle"))
                    || Files.isRegularFile(current.resolve("settings.gradle"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static void listNativeResourceDirectory(Path dir, Set<String> names) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .forEach(names::add);
        }
    }

    private static void listJarEntries(JarFile jar, String prefix, Set<String> names) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            if (!name.startsWith(prefix)) {
                continue;
            }
            String fileName = name.substring(prefix.length());
            if (!fileName.isEmpty() && !fileName.contains("/")) {
                names.add(fileName);
            }
        }
    }

    private static String nativeFileName(String base, String os, boolean isWindows) {
        if (isWindows) {
            return base + ".dll";
        }
        boolean isMac = os.contains("mac") || os.contains("darwin");
        return "lib" + base + (isMac ? ".dylib" : ".so");
    }

    private record NativeLibrarySet(List<String> ffmpegLibraries, List<String> jniLibraries) {
    }

    private boolean ensureOpen() {
        if (open && handle != 0)
            return true;
        if (openFailures > 2)
            return false;

        try {
            handle = Eac3Jni.decoderOpen();
            if (handle == 0) {
                openFailures++;
                LOGGER.error("Eac3Native: 打开解码器失败");
                return false;
            }
            open = true;
            openFailures = 0;
            LOGGER.debug("Eac3Native 解码器就绪");
            return true;
        } catch (Exception e) {
            openFailures++;
            LOGGER.error("Eac3Native 初始化异常", e);
            return false;
        }
    }

    /**
     * 获取游戏 config 目录。优先使用 NeoForge FMLPaths，不可用时回退到工作目录。
     */
    private static Path gameConfigDir() {
        try {
            return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
        } catch (Throwable ignored) {
            return Path.of("config").toAbsolutePath();
        }
    }
}
