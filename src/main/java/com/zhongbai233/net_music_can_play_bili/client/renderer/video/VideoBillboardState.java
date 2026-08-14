package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureFlags;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleVideoStatePolicy;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared static state and public data contracts for the video billboard pipeline. */
abstract class VideoBillboardState {
    protected static final Logger LOGGER = LogUtils.getLogger();
    protected static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "dynamic/bili_video_preview");
    protected static final Identifier YUV_TEXTURE_Y_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "dynamic/bili_video_preview_y");
    protected static final Identifier YUV_TEXTURE_U_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "dynamic/bili_video_preview_u");
    protected static final Identifier YUV_TEXTURE_V_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "dynamic/bili_video_preview_v");
    protected static final Identifier PACKED_BENCH_TEXTURE_ID = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "dynamic/bili_video_packed_bench");
    protected static final Identifier LOADING_PROGRESS_FRAME_TEXTURE = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/video_loading/progress_frame_204x10.png");
    protected static final Identifier LOADING_PROGRESS_SEGMENT_TEXTURE = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/video_loading/progress_segment_42x6.png");
    protected static final Identifier NETWORK_ERROR_PLACEHOLDER_TEXTURE = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "textures/gui/video_loading/network_error_base.png");
    protected static final Identifier CONTROL_CONSOLE_IDLE_TEXTURE = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID,
            ControlConsoleVideoArtwork.texturePath(ControlConsoleVideoStatePolicy.State.IDLE));
    protected static final Identifier CONTROL_CONSOLE_BUFFERING_TEXTURE = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID,
            ControlConsoleVideoArtwork.texturePath(ControlConsoleVideoStatePolicy.State.BUFFERING));
    protected static final Identifier CONTROL_CONSOLE_ERROR_TEXTURE = Identifier.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID,
            ControlConsoleVideoArtwork.texturePath(ControlConsoleVideoStatePolicy.State.ERROR));
    protected static final boolean NETWORK_ERROR_PLACEHOLDER_ENABLED =
            VideoPipelineProperties.networkErrorPlaceholderEnabled();
    protected static final int LOADING_PLACEHOLDER_WIDTH = 320;
    protected static final int LOADING_PLACEHOLDER_HEIGHT = 180;
    protected static final int LOADING_PROGRESS_X = 58;
    protected static final int LOADING_PROGRESS_Y = 126;
    protected static final int LOADING_PROGRESS_W = 204;
    protected static final int LOADING_PROGRESS_H = 10;
    protected static final int LOADING_PROGRESS_SEGMENT_W = 42;
    protected static final int LOADING_PROGRESS_SEGMENT_H = 6;
    static final int MIN_ADAPTIVE_WIDTH = VideoFeatureFlags.advancedInt("bili.video.adaptive.min_width", 640);
    static final long ADAPTIVE_FRAME_BUDGET_NS = VideoFeatureFlags
            .advancedLong("bili.video.adaptive.frame_budget_ms", 12L) * 1_000_000L;
    static final int ADAPTIVE_BAD_FRAME_THRESHOLD = VideoFeatureFlags
            .advancedInt("bili.video.adaptive.bad_frames", 45);
    protected static final boolean PROTECT_GAME_FPS = VideoFeatureFlags
            .advancedBoolean("bili.video.protect_game_fps", true);
    protected static final int PROTECTED_4K_FPS = VideoFeatureFlags.advancedInt("bili.video.protect.4k_fps", 3);
    protected static final int PROTECTED_8K_FPS = VideoFeatureFlags.advancedInt("bili.video.protect.8k_fps", 1);
    static final int MAX_CATCH_UP_DROPS_PER_TICK = VideoFeatureFlags.advancedInt(
            "bili.video.sync.max_drop_frames", 12);
    protected static final double DISTANCE = 3.0D;
    protected static final float HEIGHT = 1.35F;
    protected static final boolean CPU_BARS = VideoFeatureFlags.advancedBoolean("ncpb.video.cpu_bars", false);
    protected static final VideoPipelineProperties.Billboard BILLBOARD_PROPERTIES =
            VideoPipelineProperties.billboard();
    protected static final VideoPipelineProperties.YuvImmediate YUV_IMMEDIATE_PROPERTIES =
            VideoPipelineProperties.yuvImmediate();
    protected static final VideoPipelineProperties.Visibility VISIBILITY_PROPERTIES =
            VideoPipelineProperties.visibility();
    protected static final boolean WORLD_ANCHORED = BILLBOARD_PROPERTIES.worldAnchored();
    protected static final String YUV_IMMEDIATE_STAGE = YUV_IMMEDIATE_PROPERTIES.stage();
    protected static final String YUV_IMMEDIATE_COORDS = YUV_IMMEDIATE_PROPERTIES.coordinates();
    protected static final String YUV_IMMEDIATE_POSE = YUV_IMMEDIATE_PROPERTIES.pose();
    protected static final boolean YUV_DEBUG_LOG = YUV_IMMEDIATE_PROPERTIES.debugLog();
    protected static final double WORLD_ANCHOR_DISTANCE = BILLBOARD_PROPERTIES.worldAnchorDistance();
    static final double AUDIO_SYNC_RANGE_SQR = BILLBOARD_PROPERTIES.audioSyncRangeSqr();
    protected static final double VIEW_DOT_THRESHOLD = VISIBILITY_PROPERTIES.viewDotThreshold();
    protected static final boolean VIEW_OCCLUSION_CHECK = VISIBILITY_PROPERTIES.occlusionCheck();
    protected static final long VIEW_OCCLUSION_CACHE_NANOS = VISIBILITY_PROPERTIES.occlusionCacheNanos();
    protected static final double VIEW_SAMPLE_EDGE_SCALE = VISIBILITY_PROPERTIES.sampleEdgeScale();
    protected static final double MAX_RENDER_DISTANCE_SQR = VISIBILITY_PROPERTIES.maxRenderDistanceSqr();
    static final String RENDER_BACKEND = BILLBOARD_PROPERTIES.renderBackend();
    static final boolean NV12_DECODE_BACKEND = RENDER_BACKEND.equals("nv12")
            || RENDER_BACKEND.equals("nv12_shader")
            || RENDER_BACKEND.equals("yuv");
    static final boolean YUV420_DECODE_BACKEND = RENDER_BACKEND.equals("yuv420")
            || RENDER_BACKEND.equals("yuv420_shader")
            || RENDER_BACKEND.equals("yuv420_cpu");
    static final boolean YUV_DECODE_BACKEND = NV12_DECODE_BACKEND || YUV420_DECODE_BACKEND;
    static final boolean CUSTOM_YUV_SHADER_BACKEND = RENDER_BACKEND.equals("yuv")
            || RENDER_BACKEND.equals("yuv420")
            || RENDER_BACKEND.equals("yuv420_shader")
            || RENDER_BACKEND.equals("nv12")
            || RENDER_BACKEND.equals("nv12_shader");
    protected static final boolean YUV_UPLOAD_PLANES = BILLBOARD_PROPERTIES.yuvUploadPlanes();

    protected static final ProjectionReplacementGate<Object> PROJECTION_REPLACEMENTS =
            new ProjectionReplacementGate<>();
    protected static final Map<String, PendingProjectionStart> PENDING_PROJECTION_STARTS =
            new ConcurrentHashMap<>();
    protected static final long PROJECTION_REPLACEMENT_TIMEOUT_MILLIS = Math.max(1L,
            VideoPipelineProperties.timing().decoderRestartCloseTimeoutMillis());
    protected static final VideoSessionInstanceRegistry<VideoPlaybackInstance> SESSION_INSTANCES =
            new VideoSessionInstanceRegistry<>(VideoBillboardPreview::disposeSessionInstance);
    protected static final PendingVideoSessionRegistry<BlockPos> PENDING_SESSIONS =
            new PendingVideoSessionRegistry<>();
    protected static final VideoResourceDiagnosticsCollector<VideoPlaybackInstance> RESOURCE_DIAGNOSTICS =
            new VideoResourceDiagnosticsCollector<>(instance -> instance.isRunning(),
                    instance -> instance.hasTerminalFailure(), instance -> instance.projectorCount(),
                    instance -> instance.hasGuiConsumer());
    protected static final LegacyPreviewSessionState<BlockPos, PlaybackRequest> LEGACY_PREVIEW =
            new LegacyPreviewSessionState<>();
    protected static final LegacyPreviewWorkerLifecycle<Thread, AutoCloseable> LEGACY_WORKER =
            new LegacyPreviewWorkerLifecycle<>();
    protected static final LegacyPreviewTextureLifecycle<DynamicTexture, VideoYuvTextureSet, DynamicTexture>
            LEGACY_TEXTURES = new LegacyPreviewTextureLifecycle<>(
                    VideoBillboardPreview::disposeLegacyRgbaTexture,
                    VideoBillboardPreview::disposeLegacyYuvTextures,
                    VideoBillboardPreview::disposeLegacyPackedTexture);

    protected static final AtomicBoolean loggedIrisYuvRenderType = new AtomicBoolean(false);

    public record ProjectorFrameSnapshot(boolean hasFrame, boolean yuv, Identifier rgbaTexture, Identifier yTexture,
            Identifier uTexture, Identifier vTexture, Fmp4NativeVideoDecoder.DecodedFrame.Format format, int width,
            int height, boolean emissiveRgba, boolean loadingProgressOverlay, float rgbaDepthOffset) {
        public static ProjectorFrameSnapshot empty() {
            return new ProjectorFrameSnapshot(false, false, null, null, null, null,
                    Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, 0, 0, false, false, 0.0F);
        }
    }

    protected record PendingProjectionStart(String sessionId, Object ownerKey,
            VideoPlaybackInstance instance, ProjectionReplacementGate.Intent<Object> intent) {
        protected PendingProjectionStart {
            sessionId = sessionId != null ? sessionId : "";
            Objects.requireNonNull(ownerKey, "ownerKey");
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(intent, "intent");
        }
    }

        public record ControlConsoleVideoSnapshot(String sessionId, ControlConsoleVideoStatePolicy.State state,
            ProjectorFrameSnapshot frame) {
        }

        public record ResourceDiagnostics(int instances, int runningInstances, int failedInstances, int pendingLoading,
            int pendingFailure, int projectorReferences, int berManagedProjectors, int guiConsumers,
            int activeCloseZombies, long lateCloseConvergences) {
        }

        public enum BenchUploadFormat {
        RGBA,
        YUV420P,
        NV12
        }

        public record BenchUploadResources(boolean rgbaTexture, boolean yuvTextures, long textureStagingBytes,
            long gpuPboBytes) {
        }

        public record BenchDecoderState(boolean present, long generation, long decoderStartOffsetMillis,
                String restartState) {
            static BenchDecoderState empty() {
                return new BenchDecoderState(false, -1L, -1L, "ABSENT");
            }
        }

    protected static final AtomicBoolean loggedYuvImmediateStage = new AtomicBoolean(false);
    protected static volatile int width;
    protected static volatile int height;
    protected static volatile int activeFps;
    protected static volatile long activeStartOffsetMillis;
    protected static volatile long activeStartNanoTime;
    protected static volatile boolean hasFrame;
    protected static volatile boolean activeNetworkFailure;
    protected static volatile boolean anchorInitialized;
    protected static volatile double anchorX;
    protected static volatile double anchorY;
    protected static volatile double anchorZ;
    protected static volatile float anchorYawDeg;
    protected static volatile boolean cameraContinuityInitialized;
    protected static volatile double lastCameraX;
    protected static volatile double lastCameraY;
    protected static volatile double lastCameraZ;
    protected static volatile String lastCameraDimension;
    protected static final double CAMERA_TELEPORT_RESET_DISTANCE_SQR =
            BILLBOARD_PROPERTIES.projectorTeleportResetDistanceSqr();
    protected static volatile boolean firstImmediateQuadLogged;
    protected static volatile boolean loggedProjectorYuvImmediate;
    /** 由 BER 路径管理的投影仪；这是生命周期状态，不代表当前帧可见。 */
    protected static final Set<BlockPos> berManagedProjectorPositions = new CopyOnWriteArraySet<>();
    /** BER 最近实际提交投影面的渲染帧，用于向解码/上传管线传播视锥可见性。 */
    protected static final RecentFrameVisibility<BerProjectorSubmission> BER_SUBMITTED_PROJECTORS = new RecentFrameVisibility<>(
            1L);
    protected static final Map<ProjectorImmediateKey, ProjectorImmediatePose> PROJECTOR_IMMEDIATE_POSES = new ConcurrentHashMap<>();
    protected static final ConcurrentHashMap<BlockPos, VisibilitySample> PROJECTOR_VISIBILITY_CACHE = new ConcurrentHashMap<>();
    protected static volatile boolean firstPreviewSubmitLogged;

    protected record BerProjectorSubmission(PlaybackSessionId playbackSessionId, BlockPos projectorPos) {
    }

    protected record PlaybackRequest(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, String sessionId, long startOffsetMillis,
            long totalMillis, List<BlockPos> anchorPositions, long startedNanoTime, boolean forceRgbaOutput) {
        PlaybackRequest(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
                boolean preferNative, String decoderOverride, String sessionId, long startOffsetMillis,
                long totalMillis, Collection<BlockPos> anchorPositions, boolean forceRgbaOutput) {
            this(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, sessionId,
                    startOffsetMillis, totalMillis, anchorPositions == null ? List.of() : anchorPositions.stream()
                            .filter(Objects::nonNull).map(pos -> pos.immutable()).toList(),
                    System.nanoTime(), forceRgbaOutput);
        }
    }

    protected record ProjectorImmediateKey(PlaybackSessionId playbackSessionId, BlockPos projectorPos) {
    }

    protected record ProjectorImmediatePose(Matrix4f pose, float halfHeight) {
    }

    protected record VisibilitySample(long createdNanoTime, int thresholdKey, boolean visible) {
    }

    protected static final class DecodedFrame implements AutoCloseable {
        protected final Fmp4NativeVideoDecoder.DecodedFrame.Format format;
        protected final byte[] data;
        protected final ByteBuffer buffer;
        protected final int byteLength;
        protected final AutoCloseable delegate;
        protected final long ptsNanos;
        protected final AtomicBoolean closed = new AtomicBoolean(false);

        protected DecodedFrame(byte[] rgba, AutoCloseable delegate) {
            this(Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, rgba, null,
                    rgba != null ? rgba.length : 0, delegate, -1L);
        }

        protected DecodedFrame(byte[] rgba, AutoCloseable delegate, long ptsNanos) {
            this(Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, rgba, null,
                    rgba != null ? rgba.length : 0, delegate, ptsNanos);
        }

        protected DecodedFrame(Fmp4NativeVideoDecoder.DecodedFrame.Format format, byte[] data, AutoCloseable delegate,
                long ptsNanos) {
            this(format, data, null, data != null ? data.length : 0, delegate, ptsNanos);
        }

        protected DecodedFrame(Fmp4NativeVideoDecoder.DecodedFrame.Format format, byte[] data, ByteBuffer buffer,
                int byteLength, AutoCloseable delegate, long ptsNanos) {
            this.format = format != null ? format : Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA;
            this.data = data;
            this.buffer = buffer;
            this.byteLength = Math.max(0, byteLength);
            this.delegate = delegate;
            this.ptsNanos = ptsNanos;
        }

        static DecodedFrame wrap(byte[] rgba) {
            return rgba != null ? new DecodedFrame(rgba, null) : null;
        }

        static DecodedFrame wrap(Fmp4NativeVideoDecoder.DecodedFrame frame) {
            if (frame == null) {
                return null;
            }
            ByteBuffer buffer = frame.buffer();
            return buffer != null
                    ? new DecodedFrame(frame.format(), null, buffer, frame.byteLength(), frame, frame.ptsNanos())
                    : new DecodedFrame(frame.format(), frame.data(), frame, frame.ptsNanos());
        }

        Fmp4NativeVideoDecoder.DecodedFrame.Format format() {
            return format;
        }

        byte[] data() {
            if (data == null && buffer != null) {
                ByteBuffer src = buffer();
                byte[] copy = new byte[src.remaining()];
                src.get(copy);
                return copy;
            }
            return data;
        }

        ByteBuffer buffer() {
            if (buffer == null) {
                return null;
            }
            ByteBuffer duplicate = buffer.duplicate();
            duplicate.position(0);
            duplicate.limit(Math.min(duplicate.capacity(), byteLength()));
            return duplicate.slice().order(buffer.order());
        }

        int byteLength() {
            if (byteLength > 0) {
                return byteLength;
            }
            return data != null ? data.length : 0;
        }

        byte[] rgba() {
            if (format != Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA) {
                throw new IllegalStateException("decoded frame is " + format + ", not RGBA");
            }
            return data;
        }

        long ptsNanos() {
            return ptsNanos;
        }

        long nativeGetNanos() {
            return delegate instanceof Fmp4NativeVideoDecoder.DecodedFrame nativeFrame
                    ? nativeFrame.nativeGetNanos() : -1L;
        }

        long queueWaitNanos() {
            return delegate instanceof Fmp4NativeVideoDecoder.DecodedFrame nativeFrame
                    ? nativeFrame.queueWaitNanos() : -1L;
        }

        DecodedFrame retain() {
            if (delegate instanceof Fmp4NativeVideoDecoder.DecodedFrame nativeFrame) {
                Fmp4NativeVideoDecoder.DecodedFrame retained = nativeFrame.retain();
                ByteBuffer retainedBuffer = retained.buffer();
                return retainedBuffer != null
                        ? new DecodedFrame(retained.format(), null, retainedBuffer, retained.byteLength(), retained,
                                ptsNanos)
                        : new DecodedFrame(retained.format(), retained.data(), retained, ptsNanos);
            }
            return new DecodedFrame(format, data, buffer, byteLength, null, ptsNanos);
        }

        @Override
        public void close() {
            if (delegate != null && closed.compareAndSet(false, true)) {
                try {
                    delegate.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public record VideoStatus(int width, int height, int fps, boolean hasFrame, boolean synced,
            int requestedQuality, int actualQuality, int codecId, String backend, String fallbackReason) {
        public VideoStatus(int width, int height, int fps, boolean hasFrame, boolean synced) {
            this(width, height, fps, hasFrame, synced, 0, 0, 0, "unknown", "");
        }

        public VideoStatus {
            requestedQuality = Math.max(0, requestedQuality);
            actualQuality = Math.max(0, actualQuality);
            backend = backend == null || backend.isBlank() ? "unknown" : backend;
            fallbackReason = fallbackReason == null ? "" : fallbackReason;
        }

        static VideoStatus empty() {
            return new VideoStatus(0, 0, 0, false, false);
        }

        public boolean active() {
            return width > 0 && height > 0 && fps > 0;
        }

        public String codecLabel() {
            return codecId == 13 ? "AV1" : codecId == 7 ? "H.264" : codecId > 0 ? "codec-" + codecId : "unknown";
        }
    }

    public record VideoSyncStatus(boolean running, boolean hasFrame, long mediaMillis, long queuedMediaMillis,
            int width, int height, int fps) {
        static VideoSyncStatus empty() {
            return new VideoSyncStatus(false, false, -1L, -1L, 0, 0, 0);
        }
    }

}
