package com.zhongbai233.net_music_can_play_bili.bili.codec;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 通过系统 ffmpeg 子进程做视频解码的快速原型。
 *
 * 用法：喂 H.264 annex-b 流到 stdin，从 stdout 读 RGBA rawvideo。
 * 这是 bench 阶段的临时方案，生产环境应改用 JNI 直接调用 libavcodec。
 */
public class FfmpegSubprocessDecoder implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Process process;
    private final OutputStream stdin;
    private final InputStream stdout;
    private final int width;
    private final int height;
    private final int frameSize;
    private int totalFrames;
    private boolean closed;

    /**
     * @param videoUrl 视频文件或 URL（ffmpeg 能直接打开的）
     * @param targetWidth 输出宽度
     * @param targetHeight 输出高度
     * @param fps 输出帧率
     */
    public FfmpegSubprocessDecoder(String videoUrl, int targetWidth, int targetHeight, int fps)
            throws IOException {
        this.width = targetWidth;
        this.height = targetHeight;
        this.frameSize = width * height * 4; // RGBA

        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-v"); cmd.add("error");
        cmd.add("-i"); cmd.add(videoUrl);

        // 解码参数
        if (fps > 0) {
            cmd.add("-vf");
            cmd.add("fps=" + fps + ",scale=" + width + ":" + height + ":flags=bilinear");
        } else {
            cmd.add("-vf");
            cmd.add("scale=" + width + ":" + height + ":flags=bilinear");
        }

        cmd.add("-pix_fmt"); cmd.add("rgba");
        cmd.add("-f"); cmd.add("rawvideo");
        cmd.add("-an");             // 不要音频
        cmd.add("pipe:1");          // stdout = RGBA raw

        LOGGER.info("启动 ffmpeg: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        this.process = pb.start();
        this.stdin = process.getOutputStream();
        this.stdout = process.getInputStream();

        // 异步读取 stderr（避免管道阻塞）
        Thread stderrReader = new Thread(() -> {
            try (InputStream err = process.getErrorStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = err.read(buf)) > 0) {
                    String msg = new String(buf, 0, n);
                    if (!msg.isBlank()) {
                        LOGGER.warn("ffmpeg stderr: {}", msg.trim());
                    }
                }
            } catch (IOException ignored) {
            }
        }, "ffmpeg-stderr");
        stderrReader.setDaemon(true);
        stderrReader.start();

        LOGGER.info("ffmpeg 子进程已启动: {}×{} RGBA, frameSize={}B",
                width, height, frameSize);
    }

    /**
     * 直接从 URL 解码（ffmpeg 处理 HTTP 连接）。
     */
    public static FfmpegSubprocessDecoder fromUrl(String url, int targetWidth, int targetHeight, int fps)
            throws IOException {
        return new FfmpegSubprocessDecoder(url, targetWidth, targetHeight, fps);
    }

    /**
     * 获取下一帧 RGBA。
     *
     * @return RGBA byte[] (width*height*4)，EOF 返回 null
     */
    public byte[] getNextFrame() throws IOException {
        if (closed) return null;

        byte[] buf = new byte[frameSize];
        int totalRead = 0;
        while (totalRead < frameSize) {
            int n = stdout.read(buf, totalRead, frameSize - totalRead);
            if (n < 0) {
                // EOF
                if (totalRead == 0) return null;
                // 部分帧（不应该发生）
                LOGGER.warn("ffmpeg 在帧中间 EOF: {}/{} bytes", totalRead, frameSize);
                return null;
            }
            totalRead += n;
        }

        totalFrames++;
        return buf;
    }

    /**
     * 跳过帧直到满足条件。
     */
    public byte[] skipFrames(int count) throws IOException {
        byte[] last = null;
        for (int i = 0; i <= count; i++) {
            byte[] frame = getNextFrame();
            if (frame == null) break;
            last = frame;
        }
        return last;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getTotalFrames() { return totalFrames; }
    public boolean isAlive() { return process != null && process.isAlive(); }

    @Override
    public void close() {
        closed = true;
        try { stdin.close(); } catch (IOException ignored) {}
        try { stdout.close(); } catch (IOException ignored) {}
        if (process != null) {
            process.destroy();
            try { process.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        LOGGER.debug("ffmpeg 子进程已关闭 ({} 帧)", totalFrames);
    }
}
