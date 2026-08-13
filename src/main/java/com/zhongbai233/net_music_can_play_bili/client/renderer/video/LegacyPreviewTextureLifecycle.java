package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.Objects;
import java.util.function.Consumer;

/** Legacy singleton 预览的 RGBA、YUV 和 packed bench 纹理所有者。 */
final class LegacyPreviewTextureLifecycle<R, Y, P> {
    private final Consumer<? super R> rgbaDisposer;
    private final Consumer<? super Y> yuvDisposer;
    private final Consumer<? super P> packedDisposer;

    private volatile R rgba;
    private volatile Y yuv;
    private volatile P packed;

    LegacyPreviewTextureLifecycle(Consumer<? super R> rgbaDisposer, Consumer<? super Y> yuvDisposer,
            Consumer<? super P> packedDisposer) {
        this.rgbaDisposer = Objects.requireNonNull(rgbaDisposer, "rgbaDisposer");
        this.yuvDisposer = Objects.requireNonNull(yuvDisposer, "yuvDisposer");
        this.packedDisposer = Objects.requireNonNull(packedDisposer, "packedDisposer");
    }

    synchronized void replaceRgba(R replacement) {
        R previous = rgba;
        if (previous == replacement) {
            return;
        }
        rgba = replacement;
        dispose(previous, rgbaDisposer);
    }

    synchronized void replaceYuv(Y replacement) {
        Y previous = yuv;
        if (previous == replacement) {
            return;
        }
        yuv = replacement;
        dispose(previous, yuvDisposer);
    }

    synchronized void replacePacked(P replacement) {
        P previous = packed;
        if (previous == replacement) {
            return;
        }
        packed = replacement;
        dispose(previous, packedDisposer);
    }

    synchronized void clear() {
        R previousRgba = rgba;
        Y previousYuv = yuv;
        P previousPacked = packed;
        rgba = null;
        yuv = null;
        packed = null;

        Throwable failure = null;
        failure = disposeCapturing(previousRgba, rgbaDisposer, failure);
        failure = disposeCapturing(previousYuv, yuvDisposer, failure);
        failure = disposeCapturing(previousPacked, packedDisposer, failure);
        rethrow(failure);
    }

    R rgba() {
        return rgba;
    }

    Y yuv() {
        return yuv;
    }

    P packed() {
        return packed;
    }

    boolean hasRgbaOrPacked() {
        return rgba != null || packed != null;
    }

    boolean hasYuv() {
        return yuv != null;
    }

    private static <T> void dispose(T value, Consumer<? super T> disposer) {
        if (value != null) {
            disposer.accept(value);
        }
    }

    private static <T> Throwable disposeCapturing(T value, Consumer<? super T> disposer, Throwable failure) {
        if (value == null) {
            return failure;
        }
        try {
            disposer.accept(value);
        } catch (Throwable error) {
            if (failure == null) {
                return error;
            }
            failure.addSuppressed(error);
        }
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("legacy preview texture disposal failed", failure);
        }
    }
}
