package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import net.minecraft.resources.Identifier;

/** Static loading, Iris-warning, idle, and network-error placeholder assets. */
final class VideoPlaceholderFrames {
    static final int WIDTH = 320;
    static final int HEIGHT = 180;
    static final double IRIS_VIEW_DEPTH_OFFSET = VideoPipelineProperties.presentation().irisWarningViewDepthOffset();
    static final float IRIS_LOCAL_DEPTH_OFFSET = VideoPipelineProperties.presentation().irisWarningLocalDepthOffset();
    static final boolean NETWORK_ERROR_ENABLED = VideoPipelineProperties.networkErrorPlaceholderEnabled();

    private static final Identifier[] LOADING_TEXTURES = new Identifier[] {
            texture("loading_base_phase0.png"), texture("loading_base_phase1.png"),
            texture("loading_base_phase2.png"), texture("loading_base_phase3.png")
    };
    private static final Identifier IRIS_WARNING = texture("iris_translucent_warning_base.png");
    private static final Identifier NETWORK_ERROR = texture("network_error_base.png");
    private static final Identifier IDLE = texture("idle_base.png");

    private VideoPlaceholderFrames() {
    }

    static VideoBillboardPreview.ProjectorFrameSnapshot snapshot(Kind kind, long startedNanoTime) {
        if (kind == Kind.LOADING) {
            return loading(startedNanoTime);
        }
        boolean irisWarning = kind == Kind.IRIS_WARNING;
        return new VideoBillboardPreview.ProjectorFrameSnapshot(true, false,
                texture(kind, startedNanoTime), null, null, null,
                Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, WIDTH, HEIGHT,
                !irisWarning, kind == Kind.LOADING, irisWarning ? IRIS_LOCAL_DEPTH_OFFSET : 0.0F);
    }

    static VideoBillboardPreview.ProjectorFrameSnapshot loading(long startedNanoTime) {
        return new VideoBillboardPreview.ProjectorFrameSnapshot(true, false,
                texture(Kind.LOADING, startedNanoTime), null, null, null,
                Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, WIDTH, HEIGHT, true, true, 0.0F);
    }

    static VideoBillboardPreview.ProjectorFrameSnapshot idle() {
        return new VideoBillboardPreview.ProjectorFrameSnapshot(true, false, IDLE, null, null, null,
                Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, WIDTH, HEIGHT, true, false, 0.0F);
    }

    static Identifier texture(Kind kind, long startedNanoTime) {
        if (kind == Kind.NETWORK_ERROR) {
            return NETWORK_ERROR;
        }
        if (kind == Kind.IRIS_WARNING) {
            return IRIS_WARNING;
        }
        long elapsedNs = Math.max(0L, System.nanoTime() - startedNanoTime);
        return LOADING_TEXTURES[(int) ((elapsedNs / 300_000_000L) % LOADING_TEXTURES.length)];
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, "textures/gui/video_loading/" + name);
    }

    enum Kind {
        LOADING,
        IRIS_WARNING,
        NETWORK_ERROR
    }
}
