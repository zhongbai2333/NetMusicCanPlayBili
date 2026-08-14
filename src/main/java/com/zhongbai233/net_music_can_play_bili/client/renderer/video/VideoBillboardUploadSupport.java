package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.NativeImage;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Synthetic frames, texture uploads, staging resources, and render-thread texture cleanup. */
abstract class VideoBillboardUploadSupport extends VideoBillboardGeometrySupport {
    protected static boolean isActiveProjectorValid() {
        Minecraft minecraft = Minecraft.getInstance();
        BlockPos projectorPos = LEGACY_PREVIEW.primaryProjector();
        if (!LEGACY_PREVIEW.requiresProjector()) {
            return true;
        }
        if (minecraft.level == null) {
            return false;
        }
        if (projectorPos != null
                && minecraft.level.getBlockEntity(projectorPos) instanceof VideoProjectorBlockEntity) {
            return true;
        }
        if (projectorPos != null && berManagedProjectorPositions.contains(projectorPos)) {
            return true;
        }
        for (BlockPos pos : LEGACY_PREVIEW.projectors()) {
            if (minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity
                    || berManagedProjectorPositions.contains(pos)) {
                LEGACY_PREVIEW.setPrimaryProjector(pos);
                return true;
            }
        }
        return false;
    }

    protected static void decodeTestPatternLoop(int targetWidth, int targetHeight, int fps, long generation) {
        if (CPU_BARS) {
            decodeCpuBarsLoop(targetWidth, targetHeight, fps, generation);
            return;
        }
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-v", "error",
                    "-nostdin",
                    "-f", "lavfi",
                    "-i", "testsrc2=size=" + targetWidth + "x" + targetHeight + ":rate=" + fps,
                    "-pix_fmt", "rgba",
                    "-f", "rawvideo",
                    "-an",
                    "-");
            pb.redirectError(ProcessBuilder.Redirect.PIPE);
            process = pb.start();
            Process ffmpegProcess = process;
            Thread stderrReader = NetMusicThreadFactory.daemonThread("ffmpeg-test-pattern-stderr",
                    () -> logProcessStderr(ffmpegProcess));
            stderrReader.start();

            readRawVideoLoop(process.getInputStream(), targetWidth, targetHeight, fps, generation);
        } catch (IOException e) {
            LOGGER.error("视频 billboard 本地测试图启动失败，请确认系统 ffmpeg 在 PATH 中", e);
        } finally {
            if (process != null) {
                process.destroy();
            }
            LEGACY_WORKER.finish(generation, null);
        }
    }

    protected static void decodeCpuBarsLoop(int targetWidth, int targetHeight, int fps, long generation) {
        int evenWidth = Math.max(2, targetWidth & ~1);
        int evenHeight = Math.max(2, targetHeight & ~1);
        int frameSize = CUSTOM_YUV_SHADER_BACKEND ? evenWidth * evenHeight * 3 / 2 : targetWidth * targetHeight * 4;
        long frameDelayMs = fps > 0 ? Math.max(1L, 1000L / fps) : 50L;
        long frameIndex = 0L;
        LOGGER.info("视频 billboard 使用 CPU 纯色彩条诊断模式: {}x{} @ {}fps, yuvShaderBackend={}",
                CUSTOM_YUV_SHADER_BACKEND ? evenWidth : targetWidth,
                CUSTOM_YUV_SHADER_BACKEND ? evenHeight : targetHeight,
                fps, CUSTOM_YUV_SHADER_BACKEND);
        try {
            while (LEGACY_WORKER.isActive(generation)) {
                byte[] frame = new byte[frameSize];
                long currentFrameIndex = frameIndex++;
                if (CUSTOM_YUV_SHADER_BACKEND) {
                    fillCpuBarsYuv420p(frame, evenWidth, evenHeight, currentFrameIndex);
                    Minecraft.getInstance().execute(() -> {
                        if (LEGACY_WORKER.isActive(generation)) {
                            uploadYuv420FrameOnRenderThreadForBench(frame, evenWidth, evenHeight);
                        }
                    });
                } else {
                    fillCpuBars(frame, targetWidth, targetHeight, currentFrameIndex);
                    Minecraft.getInstance().execute(() -> {
                        if (LEGACY_WORKER.isActive(generation)) {
                            uploadFrame(frame, targetWidth, targetHeight);
                        }
                    });
                }
                try {
                    Thread.sleep(frameDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            LEGACY_WORKER.finish(generation, null);
        }
    }

    protected static void fillCpuBarsYuv420p(byte[] frame, int frameWidth, int frameHeight, long frameIndex) {
        int ySize = frameWidth * frameHeight;
        int uvWidth = frameWidth / 2;
        int uvHeight = frameHeight / 2;
        int uBase = ySize;
        int vBase = ySize + uvWidth * uvHeight;
        int phase = (int) (frameIndex % Math.max(1, frameWidth));
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                int[] rgb = cpuBarRgb(x, frameWidth, phase);
                int[] yuv = rgbToLimitedBt709(rgb[0], rgb[1], rgb[2]);
                frame[y * frameWidth + x] = (byte) yuv[0];
            }
        }
        for (int y = 0; y < uvHeight; y++) {
            for (int x = 0; x < uvWidth; x++) {
                int sx = Math.min(frameWidth - 1, x * 2);
                int[] rgb = cpuBarRgb(sx, frameWidth, phase);
                int[] yuv = rgbToLimitedBt709(rgb[0], rgb[1], rgb[2]);
                int i = y * uvWidth + x;
                frame[uBase + i] = (byte) yuv[1];
                frame[vBase + i] = (byte) yuv[2];
            }
        }
    }

    protected static void fillCpuBars(byte[] frame, int frameWidth, int frameHeight, long frameIndex) {
        int phase = (int) (frameIndex % Math.max(1, frameWidth));
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                int[] rgb = cpuBarRgb(x, frameWidth, phase);
                int i = (y * frameWidth + x) * 4;
                frame[i] = (byte) rgb[0];
                frame[i + 1] = (byte) rgb[1];
                frame[i + 2] = (byte) rgb[2];
                frame[i + 3] = (byte) 255;
            }
        }
    }

    protected static int[] cpuBarRgb(int x, int frameWidth, int phase) {
        int r;
        int g;
        int b;
        switch ((x * 8) / Math.max(1, frameWidth)) {
            case 0 -> {
                r = 255;
                g = 0;
                b = 0;
            }
            case 1 -> {
                r = 0;
                g = 255;
                b = 0;
            }
            case 2 -> {
                r = 0;
                g = 0;
                b = 255;
            }
            case 3 -> {
                r = 255;
                g = 255;
                b = 0;
            }
            case 4 -> {
                r = 0;
                g = 255;
                b = 255;
            }
            case 5 -> {
                r = 255;
                g = 0;
                b = 255;
            }
            case 6 -> {
                r = 255;
                g = 255;
                b = 255;
            }
            default -> {
                r = 32;
                g = 32;
                b = 32;
            }
        }
        if (Math.abs(((x + phase) % frameWidth) - frameWidth / 2) < 6) {
            r = 255;
            g = 255;
            b = 255;
        }
        return new int[] { r, g, b };
    }

    protected static int[] rgbToLimitedBt709(int r, int g, int b) {
        int y = Math.round(16.0F + (65.481F * r + 128.553F * g + 24.966F * b) / 255.0F);
        int u = Math.round(128.0F + (-37.797F * r - 74.203F * g + 112.0F * b) / 255.0F);
        int v = Math.round(128.0F + (112.0F * r - 93.786F * g - 18.214F * b) / 255.0F);
        return new int[] { clampByte(y), clampByte(u), clampByte(v) };
    }

    protected static int clampByte(int value) {
        return value < 0 ? 0 : value > 255 ? 255 : value;
    }

    protected static void readRawVideoLoop(InputStream stdout, int targetWidth, int targetHeight, int fps,
            long generation)
            throws IOException {
        int frameSize = targetWidth * targetHeight * 4;
        long frameDelayMs = fps > 0 ? Math.max(1L, 1000L / fps) : 50L;
        while (LEGACY_WORKER.isActive(generation)) {
            long startNs = System.nanoTime();
            byte[] frame = new byte[frameSize];
            int totalRead = 0;
            while (totalRead < frameSize) {
                int n = stdout.read(frame, totalRead, frameSize - totalRead);
                if (n < 0) {
                    return;
                }
                totalRead += n;
            }
            Minecraft.getInstance().execute(() -> {
                if (LEGACY_WORKER.isActive(generation)) {
                    uploadFrame(frame, targetWidth, targetHeight);
                }
            });

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            long sleepMs = frameDelayMs - elapsedMs;
            if (sleepMs > 0L) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    protected static void logProcessStderr(Process process) {
        try (InputStream in = process.getErrorStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    String line = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (!line.isBlank()) {
                        LOGGER.error("ffmpeg-test-pattern: {}", line);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    protected static void uploadFrame(byte[] rgba, int frameWidth, int frameHeight) {
        uploadFrameOnRenderThread(rgba, frameWidth, frameHeight);
    }

    protected static long uploadFrameSync(DecodedFrame frame, int frameWidth, int frameHeight, long generation) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !LEGACY_WORKER.isActive(generation) || !isActiveProjectorValid()) {
            return -1L;
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        DecodedFrame retained = frame.retain();
        try {
            minecraft.execute(() -> {
                try (retained) {
                    if (!LEGACY_WORKER.isActive(generation) || !isActiveProjectorValid()) {
                        future.complete(-1L);
                        return;
                    }
                    long startNs = System.nanoTime();
                    boolean ok = uploadDecodedFrameOnRenderThread(retained, frameWidth, frameHeight);
                    future.complete(ok ? System.nanoTime() - startNs : -1L);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            retained.close();
            throw e;
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException e) {
            LOGGER.error("视频渲染上传帧失败", e);
            return -1L;
        }
    }

    protected static boolean uploadDecodedFrameOnRenderThread(DecodedFrame frame, int frameWidth, int frameHeight) {
        if (isCustomYuvShaderAvailable() && frame != null && isYuvFrameFormat(frame.format())) {
            return uploadYuvFrameOnRenderThread(frame, frameWidth, frameHeight);
        }
        return uploadFrameOnRenderThread(Yuv420pConverter.toUploadRgba(frame, frameWidth, frameHeight), frameWidth,
                frameHeight);
    }

    public static long uploadFrameSyncForBench(byte[] rgba, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return -1L;
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            long startNs = System.nanoTime();
            boolean ok = uploadFrameOnRenderThread(rgba, frameWidth, frameHeight);
            future.complete(ok ? System.nanoTime() - startNs : -1L);
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException e) {
            LOGGER.error("视频渲染 bench 上传帧失败", e);
            return -1L;
        }
    }

    public static long uploadDecodedFrameSyncForBench(Fmp4NativeVideoDecoder.DecodedFrame frame, int frameWidth,
            int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || frame == null) {
            return -1L;
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        Fmp4NativeVideoDecoder.DecodedFrame retained = frame.retain();
        try {
            minecraft.execute(() -> {
                try (DecodedFrame wrapped = DecodedFrame.wrap(retained)) {
                    long startNs = System.nanoTime();
                    boolean ok = uploadDecodedFrameOnRenderThread(wrapped, frameWidth, frameHeight);
                    future.complete(ok ? System.nanoTime() - startNs : -1L);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            retained.close();
            throw e;
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException e) {
            LOGGER.error("视频渲染 bench decoded frame 上传失败", e);
            return -1L;
        }
    }

    public static long uploadPackedBytesSyncForBench(byte[] packedRgbaBytes, int textureWidth, int textureHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return -1L;
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            long startNs = System.nanoTime();
            boolean ok = uploadPackedBytesOnRenderThread(packedRgbaBytes, textureWidth, textureHeight);
            future.complete(ok ? System.nanoTime() - startNs : -1L);
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException e) {
            LOGGER.error("视频渲染 bench packed 上传失败", e);
            return -1L;
        }
    }

    public static long uploadYuv420FrameSyncForBench(byte[] yuv420p, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return -1L;
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            long startNs = System.nanoTime();
            boolean ok = uploadYuv420FrameOnRenderThreadForBench(yuv420p, frameWidth, frameHeight);
            future.complete(ok ? System.nanoTime() - startNs : -1L);
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException e) {
            LOGGER.error("视频渲染 bench YUV420P 三平面上传失败", e);
            return -1L;
        }
    }

    public static long uploadNv12FrameSyncForBench(byte[] nv12, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return -1L;
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            long startNs = System.nanoTime();
            boolean ok = uploadNv12FrameOnRenderThreadForBench(nv12, frameWidth, frameHeight);
            future.complete(ok ? System.nanoTime() - startNs : -1L);
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException e) {
            LOGGER.error("视频渲染 bench NV12 双平面上传失败", e);
            return -1L;
        }
    }

    protected static boolean uploadYuv420FrameOnRenderThreadForBench(byte[] yuv420p, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        if (!YUV_UPLOAD_PLANES) {
            LOGGER.warn("YUV420P bench 诊断：ncpb.video.yuv.upload_planes=false，跳过 RED8 三平面纹理创建，临时 CPU 转 RGBA 上传");
            return uploadFrameOnRenderThread(Yuv420pConverter.yuv420pToRgba(yuv420p, frameWidth, frameHeight),
                    frameWidth, frameHeight);
        }
        if (!isCustomYuvShaderAvailable()) {
            return uploadFrameOnRenderThread(Yuv420pConverter.yuv420pToRgba(yuv420p, frameWidth, frameHeight),
                    frameWidth, frameHeight);
        }
        // real_bench 是“真实播放链路压测”，不是纯上传微基准；复用 preview 的 YUV 纹理集，
        // 这样 SubmitCustomGeometry 能把正在压测的帧真正画到世界里。独立 bench 纹理仅保留给未来纯上传对照。
        ensureYuvTextureSet(Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P);
        boolean ok = LEGACY_TEXTURES.yuv() instanceof Yuv420pTextureSet yuv420pTextures
                && yuv420pTextures.upload(yuv420p, frameWidth, frameHeight);
        if (!ok) {
            LOGGER.warn("视频 YUV420P bench 帧大小不足: bytes={}, expected={}",
                    yuv420p != null ? yuv420p.length : 0, frameWidth * frameHeight * 3 / 2);
            return false;
        }
        width = frameWidth;
        height = frameHeight;
        hasFrame = true;
        return true;
    }

    protected static boolean uploadNv12FrameOnRenderThreadForBench(byte[] nv12, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        if (!isCustomYuvShaderAvailable()) {
            return uploadFrameOnRenderThread(Yuv420pConverter.nv12ToRgba(nv12, frameWidth, frameHeight), frameWidth,
                    frameHeight);
        }
        ensureYuvTextureSet(Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12);
        boolean ok = LEGACY_TEXTURES.yuv() instanceof Nv12TextureSet nv12Textures
                && nv12Textures.upload(nv12, frameWidth, frameHeight);
        if (!ok) {
            LOGGER.warn("视频 NV12 bench 帧大小不足: bytes={}, expected={}",
                    nv12 != null ? nv12.length : 0, frameWidth * frameHeight * 3 / 2);
            return false;
        }
        width = frameWidth;
        height = frameHeight;
        hasFrame = true;
        return true;
    }

    protected static boolean uploadPackedBytesOnRenderThread(byte[] packedRgbaBytes, int textureWidth,
            int textureHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        int byteCount = textureWidth * textureHeight * 4;
        if (packedRgbaBytes.length < byteCount) {
            LOGGER.warn("视频 packed 帧大小不足: {} < {}", packedRgbaBytes.length, byteCount);
            return false;
        }

        ensurePackedBenchTexture(textureWidth, textureHeight);
        DynamicTexture packedTexture = LEGACY_TEXTURES.packed();
        if (packedTexture == null) {
            return false;
        }
        NativeImage image = packedTexture.getPixels();
        if (image == null || image.isClosed()) {
            return false;
        }

        VideoFrameUploader.uploadPackedRgbaBytes(image, packedRgbaBytes, textureWidth, textureHeight);
        packedTexture.upload();
        return true;
    }

    protected static boolean uploadFrameOnRenderThread(byte[] rgba, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        if (rgba.length < frameWidth * frameHeight * 4) {
            LOGGER.warn("视频帧大小不足: {} < {}", rgba.length, frameWidth * frameHeight * 4);
            return false;
        }

        ensureTexture(frameWidth, frameHeight);
        DynamicTexture rgbaTexture = LEGACY_TEXTURES.rgba();
        if (rgbaTexture == null) {
            return false;
        }
        NativeImage image = rgbaTexture.getPixels();
        if (image == null || image.isClosed()) {
            return false;
        }

        VideoFrameUploader.uploadRgba(image, rgba, frameWidth, frameHeight);
        rgbaTexture.upload();
        hasFrame = true;
        return true;
    }

    protected static boolean uploadYuvFrameOnRenderThread(DecodedFrame frame, int frameWidth, int frameHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        if (!YUV_UPLOAD_PLANES) {
            LOGGER.warn("YUV preview 诊断：ncpb.video.yuv.upload_planes=false，跳过多平面纹理创建，临时 CPU 转 RGBA 上传");
            return uploadFrameOnRenderThread(Yuv420pConverter.toUploadRgba(frame, frameWidth, frameHeight), frameWidth,
                    frameHeight);
        }
        ensureYuvTextureSet(frame.format());
        if (!uploadYuvFrameData(LEGACY_TEXTURES.yuv(), frame, frameWidth, frameHeight)) {
            LOGGER.warn("YUV 视频帧大小不足或格式错误: format={}, bytes={}", frame != null ? frame.format() : null,
                    frame != null ? frame.byteLength() : 0);
            return false;
        }
        width = frameWidth;
        height = frameHeight;
        hasFrame = true;
        return true;
    }

    protected static boolean uploadYuvFrameData(VideoYuvTextureSet textureSet, DecodedFrame frame, int width,
            int height) {
        if (textureSet == null || frame == null || textureSet.format() != frame.format()) {
            return false;
        }
        java.nio.ByteBuffer buffer = frame.buffer();
        if (buffer != null) {
            return textureSet.upload(buffer, frame.byteLength(), width, height);
        }
        return textureSet.upload(frame.data(), width, height);
    }

    protected static void ensureYuvTextureSet(Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
        Fmp4NativeVideoDecoder.DecodedFrame.Format normalized = format == Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                ? Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
                : Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
        VideoYuvTextureSet current = LEGACY_TEXTURES.yuv();
        if (current != null && current.format() == normalized) {
            return;
        }
        // 三平面/双平面实现复用固定纹理 ID；先释放旧集合，再注册 replacement，
        // 避免旧集合的 close 误释放刚注册的新纹理。
        LEGACY_TEXTURES.replaceYuv(null);
        VideoYuvTextureSet replacement;
        if (normalized == Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P) {
            replacement = new Yuv420pTextureSet(YUV_TEXTURE_Y_ID, YUV_TEXTURE_U_ID, YUV_TEXTURE_V_ID,
                    "bili_video_billboard_preview_yuv420p");
        } else {
            replacement = new Nv12TextureSet(YUV_TEXTURE_Y_ID, YUV_TEXTURE_U_ID, YUV_TEXTURE_Y_ID,
                    "bili_video_billboard_preview_nv12");
        }
        LEGACY_TEXTURES.replaceYuv(replacement);
    }

    protected static void ensureTexture(int frameWidth, int frameHeight) {
        DynamicTexture current = LEGACY_TEXTURES.rgba();
        if (current != null) {
            NativeImage image = current.getPixels();
            if (image != null && !image.isClosed()
                    && image.getWidth() == frameWidth && image.getHeight() == frameHeight) {
                width = frameWidth;
                height = frameHeight;
                return;
            }
        }
        releaseTexture();
        width = frameWidth;
        height = frameHeight;
        DynamicTexture replacement = new DynamicTexture("bili_video_billboard_preview", frameWidth, frameHeight,
                false);
        LEGACY_TEXTURES.replaceRgba(replacement);
        Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, replacement);
    }

    protected static void ensurePackedBenchTexture(int textureWidth, int textureHeight) {
        DynamicTexture current = LEGACY_TEXTURES.packed();
        if (current != null) {
            NativeImage image = current.getPixels();
            if (image != null && !image.isClosed()
                    && image.getWidth() == textureWidth && image.getHeight() == textureHeight) {
                return;
            }
        }
        releasePackedBenchTexture();
        DynamicTexture replacement = new DynamicTexture("bili_video_packed_bench", textureWidth, textureHeight,
                false);
        LEGACY_TEXTURES.replacePacked(replacement);
        Minecraft.getInstance().getTextureManager().register(PACKED_BENCH_TEXTURE_ID, replacement);
        LOGGER.info("视频 packed bench 动态纹理已创建: {} ({}x{}), fastNativeUpload={}; 仅用于上传计时，不参与 billboard 渲染",
                PACKED_BENCH_TEXTURE_ID, textureWidth, textureHeight,
                VideoFrameUploader.fastNativeUploadAvailable());
    }

    protected static void releaseTexture() {
        LEGACY_TEXTURES.clear();
    }

    protected static void releasePackedBenchTexture() {
        LEGACY_TEXTURES.replacePacked(null);
    }

    protected static void disposeLegacyRgbaTexture(DynamicTexture rgbaTexture) {
        Minecraft.getInstance().getTextureManager().release(TEXTURE_ID);
        rgbaTexture.close();
    }

    protected static void disposeLegacyYuvTextures(VideoYuvTextureSet textures) {
        textures.close();
    }

    protected static void disposeLegacyPackedTexture(DynamicTexture packedTexture) {
        Minecraft.getInstance().getTextureManager().release(PACKED_BENCH_TEXTURE_ID);
        packedTexture.close();
    }

}
