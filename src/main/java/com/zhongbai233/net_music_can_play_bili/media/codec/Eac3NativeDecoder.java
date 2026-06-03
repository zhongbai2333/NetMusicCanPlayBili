package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * E-AC-3 原生解码器，封装 FFmpeg libavcodec
 */
public class Eac3NativeDecoder implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();

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

        // FFmpeg 核心库，按依赖顺序: avutil → swresample → swscale → avcodec
        String[] ffmpegLibs = { "avutil", "swresample", "swscale", "avcodec" };
        // 自研薄 JNI 层
        String[] jniLibs = { "eac3_jni", "video_jni" };
        // Windows 工具链运行时依赖原则上应由 FFmpeg 构建端消除；这里保留可选预加载兜底。
        String[] runtimeLibs = isWindows ? new String[] { "libwinpthread-1" } : new String[0];
        boolean[] runtimeLibPresent = new boolean[runtimeLibs.length];

        // ── 提取到 config 目录（避免 temp 被杀软拦截 DLL 加载；平台子目录隔离多版本）──
        Path nativeDir = gameConfigDir().resolve("net_music_can_play_bili").resolve("natives")
                .resolve(platformDir);
        Files.createDirectories(nativeDir);

        // ── 提取所有文件；如果 jar 内资源更新，覆盖 config 中旧版本，避免用户残留旧 DLL ──
        for (int i = 0; i < runtimeLibs.length; i++) {
            String fileName = nativeFileName(runtimeLibs[i], os, isWindows);
            runtimeLibPresent[i] = extractEmbeddedNative(platformDir, fileName, nativeDir.resolve(fileName), false);
        }
        for (String baseName : ffmpegLibs) {
            String fileName = nativeFileName(baseName, os, isWindows);
            extractEmbeddedNative(platformDir, fileName, nativeDir.resolve(fileName), true);
        }
        for (String baseName : jniLibs) {
            String fileName = nativeFileName(baseName, os, isWindows);
            extractEmbeddedNative(platformDir, fileName, nativeDir.resolve(fileName), true);
        }

        // ── 加载所有库 ──
        // 可选运行时依赖必须先显式加载；Windows 不保证会从当前 DLL 所在目录解析二级依赖。
        for (int i = 0; i < runtimeLibs.length; i++) {
            if (runtimeLibPresent[i]) {
                String fn = nativeFileName(runtimeLibs[i], os, isWindows);
                System.load(nativeDir.resolve(fn).toAbsolutePath().toString());
            }
        }
        for (String baseName : ffmpegLibs) {
            String fn = nativeFileName(baseName, os, isWindows);
            System.load(nativeDir.resolve(fn).toAbsolutePath().toString());
        }
        for (String baseName : jniLibs) {
            String fn = nativeFileName(baseName, os, isWindows);
            System.load(nativeDir.resolve(fn).toAbsolutePath().toString());
        }

        LOGGER.info("FFmpeg native 库提取并加载: {}", nativeDir);
    }

    private static boolean extractEmbeddedNative(String platformDir, String fileName, Path target, boolean required)
            throws IOException {
        String resPath = "/native/" + platformDir + "/" + fileName;
        try (InputStream in = Eac3NativeDecoder.class.getResourceAsStream(resPath)) {
            if (in == null) {
                if (required) {
                    throw new IOException("内嵌 native 库缺失: " + resPath);
                }
                return false;
            }

            byte[] bundled = in.readAllBytes();
            if (!Files.exists(target) || !Arrays.equals(Files.readAllBytes(target), bundled)) {
                Files.write(target, bundled);
            }
            return true;
        }
    }

    private static boolean isFfmpegLib(String name) {
        return name.equals("avutil") || name.equals("swresample") || name.equals("swscale")
                || name.equals("avcodec");
    }

    private static String ffmpegFileName(String base) {
        switch (base) {
            case "avutil":
                return "avutil-60";
            case "swresample":
                return "swresample-6";
            case "swscale":
                return "swscale-9";
            case "avcodec":
                return "avcodec-62";
            default:
                return base;
        }
    }

    private static String nativeFileName(String base, String os, boolean isWindows) {
        if (isWindows) {
            return isFfmpegLib(base) ? ffmpegFileName(base) + ".dll" : base + ".dll";
        }
        boolean isMac = os.contains("mac") || os.contains("darwin");
        if (!isFfmpegLib(base)) {
            return "lib" + base + (isMac ? ".dylib" : ".so");
        }

        switch (base) {
            case "avutil":
                return isMac ? "libavutil.60.dylib" : "libavutil.so.60";
            case "swresample":
                return isMac ? "libswresample.6.dylib" : "libswresample.so.6";
            case "swscale":
                return isMac ? "libswscale.9.dylib" : "libswscale.so.9";
            case "avcodec":
                return isMac ? "libavcodec.62.dylib" : "libavcodec.so.62";
            default:
                return "lib" + base + (isMac ? ".dylib" : ".so");
        }
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
