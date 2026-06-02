package com.zhongbai233.net_music_can_play_bili.bili.codec;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 通过系统 ffmpeg 子进程做视频解码。
 *
 * 先从 B站 CDN 下载视频片段到临时文件，再用 ffmpeg 解码为 RGBA rawvideo。
 */
public class FfmpegSubprocessDecoder implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Process process;
    private final OutputStream stdin;
    private final InputStream stdout;
    private final Path tempFile;
    private final int width;
    private final int height;
    private final int frameSize;
    private int totalFrames;
    private boolean closed;

    /**
     * 从 URL 创建解码器。
     * 先下载开头 2MB 到临时文件（足够 60 帧 40×22 的视频）。
     */
    public FfmpegSubprocessDecoder(String videoUrl, int targetWidth, int targetHeight, int fps)
            throws IOException {
        this.width = targetWidth;
        this.height = targetHeight;
        this.frameSize = width * height * 4;

        // 1. 下载视频片段到临时文件
        tempFile = Files.createTempFile("bili_video_", ".m4s");
        LOGGER.info("下载视频片段: {} → {}", videoUrl.substring(0, 60), tempFile);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(videoUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", "https://www.bilibili.com/")
                .timeout(Duration.ofSeconds(30))
                .GET().build();

        HttpResponse<InputStream> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(tempFile);
            throw new IOException("下载被中断", e);
        }
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tempFile);
            throw new IOException("下载失败 HTTP " + resp.statusCode());
        }

        // 只下载前 2MB（足够 bench 用）
        byte[] buf = new byte[8192];
        long total = 0;
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(tempFile)) {
            int n;
            while (total < 2 * 1024 * 1024 && (n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
        }
        LOGGER.info("已下载 {}KB", total / 1024);

        // 2. 启动 ffmpeg 解码
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-v"); cmd.add("error");
        cmd.add("-i"); cmd.add(tempFile.toAbsolutePath().toString());

        if (fps > 0) {
            cmd.add("-r"); cmd.add(String.valueOf(fps));
        }
        cmd.add("-vf");
        cmd.add("scale=" + width + ":" + height + ":flags=bilinear");

        cmd.add("-pix_fmt"); cmd.add("rgba");
        cmd.add("-f"); cmd.add("rawvideo");
        cmd.add("-an");
        cmd.add("pipe:1");

        LOGGER.info("启动 ffmpeg (本地文件): {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        this.process = pb.start();
        this.stdin = process.getOutputStream();
        this.stdout = process.getInputStream();

        Thread stderrReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        LOGGER.error("ffmpeg: {}", line);
                    }
                }
            } catch (IOException e) {
                LOGGER.error("ffmpeg stderr 异常", e);
            }
        }, "ffmpeg-stderr");
        stderrReader.setDaemon(true);
        stderrReader.start();

        LOGGER.info("ffmpeg 已启动 (本地文件) {}×{} RGBA", width, height);
    }

    public byte[] getNextFrame() throws IOException {
        if (closed) return null;
        byte[] buf = new byte[frameSize];
        int totalRead = 0;
        while (totalRead < frameSize) {
            int n = stdout.read(buf, totalRead, frameSize - totalRead);
            if (n < 0) return totalRead == 0 ? null : null; // EOF
            totalRead += n;
        }
        totalFrames++;
        return buf;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getTotalFrames() { return totalFrames; }

    @Override
    public void close() {
        closed = true;
        try { stdin.close(); } catch (IOException ignored) {}
        try { stdout.close(); } catch (IOException ignored) {}
        if (process != null) {
            process.destroy();
            try { process.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        LOGGER.debug("ffmpeg 已关闭 ({} 帧)", totalFrames);
    }
}
