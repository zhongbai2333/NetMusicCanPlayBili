package com.zhongbai233.net_music_can_play_bili.bili.codec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * E-AC-3 原生解码器，封装 FFmpeg libavcodec。
 * <p>
 * 通过自研薄 JNI 层 ({@link Eac3Jni}) 直接调用 FFmpeg，零 JavaCPP 依赖。
 */
public class Eac3NativeDecoder implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile boolean loaderInitialized;
    private static volatile boolean nativeAvailable;
    private static volatile String ffmpegVersion;

    private long handle;          // DecoderHandle*
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
        if (!nativeAvailable) return null;
        if (!ensureOpen()) return null;

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
            if (src == null) continue;
            int n = Math.min(samples, src.length);
            for (int i = 0; i < n; i++) {
                pcm[i][ch] = src[i];
            }
        }

        if (samples != lastNbSamples || channels != lastNbChannels) {
            LOGGER.info("Eac3Native 解码: {}samples × {}ch (FFmpeg {})",
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
        LOGGER.info("Eac3Native 已关闭 (解码 {} 帧)", totalFrames);
    }

    /** 已成功解码的总帧数。 */
    public long totalFrames() {
        return totalFrames;
    }

    // ── 内部 ──

    /** 预加载 FFmpeg native 库，幂等。建议在 mdat 到达前调用，避免 worker 线程被初始化阻塞。 */
    public static synchronized void preload() {
        ensureLoaderReady();
    }

    /** FFmpeg native 库是否成功加载，Dolby 管线由此决定是否可用。 */
    public static boolean isNativeAvailable() {
        ensureLoaderReady();
        return nativeAvailable;
    }

    private static synchronized void ensureLoaderReady() {
        if (loaderInitialized) return;
        try {
            loadEmbeddedNatives();
            ffmpegVersion = Eac3Jni.version();
            nativeAvailable = true;
            LOGGER.info("FFmpeg E-AC-3 解码器加载成功: {}", ffmpegVersion);
        } catch (Throwable e) {
            nativeAvailable = false;
            LOGGER.error("FFmpeg 加载失败，Dolby 全景声将不可用，自动降级到 FLAC/AAC。", e);
        } finally {
            loaderInitialized = true;
        }
    }

    /** 从 jar 资源中提取并加载平台对应的 native 库。 */
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

        // FFmpeg 核心库，按依赖顺序: avutil → swresample → avcodec
        String[] ffmpegLibs = {"avutil", "swresample", "avcodec"};
        // 自研薄 JNI 层（单个 DLL，替代原来的 jniavutil/jniswresample/jniavcodec 三个）
        String[] jniLibs = {"eac3_jni"};

        Path gameDir = Path.of("").toAbsolutePath();

        // ── 提取所有文件 ──
        String[][] allLibs = {ffmpegLibs, jniLibs};
        for (String[] group : allLibs) {
            for (String baseName : group) {
                String fileName = nativeFileName(baseName, os, isWindows);

                Path target = gameDir.resolve(fileName);
                if (!Files.exists(target)) {
                    String resPath = "/native/" + platformDir + "/" + fileName;
                    try (InputStream in = Eac3NativeDecoder.class.getResourceAsStream(resPath)) {
                        if (in == null) {
                            throw new IOException("内嵌 native 库缺失: " + resPath);
                        }
                        Files.copy(in, target);
                    }
                }
            }
        }

        // ── 加载 FFmpeg DLL ──
        for (String baseName : ffmpegLibs) {
            if (isWindows) {
                System.loadLibrary(ffmpegLibraryName(baseName));
            } else {
                String fn = nativeFileName(baseName, os, false);
                System.load(gameDir.resolve(fn).toAbsolutePath().toString());
            }
        }

        // ── 加载自研 JNI ──
        for (String baseName : jniLibs) {
            if (isWindows) {
                System.loadLibrary(baseName);
            } else {
                String fn = nativeFileName(baseName, os, false);
                System.load(gameDir.resolve(fn).toAbsolutePath().toString());
            }
        }
    }

    private static boolean isFfmpegLib(String name) {
        return name.equals("avutil") || name.equals("swresample") || name.equals("avcodec");
    }

    private static String ffmpegFileName(String base) {
        switch (base) {
            case "avutil":
                return "avutil-60";
            case "swresample":
                return "swresample-6";
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
            case "avcodec":
                return isMac ? "libavcodec.62.dylib" : "libavcodec.so.62";
            default:
                return "lib" + base + (isMac ? ".dylib" : ".so");
        }
    }

    private static String ffmpegLibraryName(String base) {
        switch (base) {
            case "avutil":
                return "avutil-60";
            case "swresample":
                return "swresample-6";
            case "avcodec":
                return "avcodec-62";
            default:
                return base;
        }
    }

    private boolean ensureOpen() {
        if (open && handle != 0) return true;
        if (openFailures > 2) return false;

        try {
            handle = Eac3Jni.decoderOpen();
            if (handle == 0) {
                openFailures++;
                LOGGER.error("Eac3Native: 打开解码器失败");
                return false;
            }
            open = true;
            openFailures = 0;
            LOGGER.info("Eac3Native 解码器就绪");
            return true;
        } catch (Exception e) {
            openFailures++;
            LOGGER.error("Eac3Native 初始化异常", e);
            return false;
        }
    }
}
