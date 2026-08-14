package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver.VideoCandidate;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Synchronized playback sessions, replacement handoffs, pending starts, and projector ownership. */
abstract class VideoBillboardSessionSupport extends VideoBillboardDecoderSupport {
    public static void startSynced(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, BlockPos anchorPos, String decoderOverride) {
        startSynced(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, 0L, anchorPos,
                false, decoderOverride);
    }

    public static void startSynced(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, BlockPos anchorPos, boolean preferNative,
            String decoderOverride) {
        startSynced(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, 0L, anchorPos,
                preferNative, decoderOverride);
    }

    public static void startSynced(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, BlockPos anchorPos, boolean preferNative,
            String decoderOverride) {
        startSynced(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, totalMillis,
                anchorPos != null ? List.of(anchorPos) : List.of(), preferNative, decoderOverride);
    }

    public static void startSynced(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> anchorPositions,
            boolean preferNative, String decoderOverride) {
        startSynced(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, totalMillis,
                anchorPositions, (BlockPos) null, preferNative, decoderOverride);
    }

    public static void startSynced(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> anchorPositions,
            BlockPos turntablePos, boolean preferNative, String decoderOverride) {
        VideoPlaybackAnchor anchor = VideoPlaybackAnchor.turntable(turntablePos, sessionId, Math.max(0L, totalMillis));
        startSynced(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, totalMillis,
                anchorPositions, anchor, preferNative, decoderOverride);
    }

    /**
     * 直播机视频入口：解码源是 {@code LiveVideoSampleBus}，播放时钟锚定直播机处的
     * OpenAL 可听位置，无总时长与 seek。
     */
    public static void startLiveSession(String busUrl, int targetWidth, int targetHeight, int fps,
            String sessionId, Collection<BlockPos> projectorPositions, BlockPos livePos) {
        if (sessionId == null || sessionId.isBlank() || busUrl == null || busUrl.isBlank()) {
            return;
        }
        VideoPlaybackAnchor anchor = new LiveVideoPlaybackAnchor(livePos, sessionId);
        startOrUpdateInstance(busUrl, targetWidth, targetHeight, fps, 7, sessionId, 0L, 0L,
                projectorPositions, anchor, true, null);
    }

    public static void startSyncedCandidates(List<VideoCandidate> candidates, int targetWidth, int targetHeight,
            int fps, String sessionId, long startOffsetMillis, long totalMillis,
            Collection<BlockPos> anchorPositions, BlockPos turntablePos, boolean preferNative,
            String decoderOverride) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        VideoCandidate preferred = candidates.get(0);
        VideoPlaybackAnchor anchor = VideoPlaybackAnchor.turntable(turntablePos, sessionId, Math.max(0L, totalMillis));
        startOrUpdateInstance(preferred.url(), targetWidth, targetHeight, fps, preferred.codecId(), sessionId,
                startOffsetMillis, totalMillis, anchorPositions, anchor, preferNative, decoderOverride,
                candidates);
    }

    static void startSynced(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> anchorPositions,
            VideoPlaybackAnchor anchor, boolean preferNative, String decoderOverride) {
        if (sessionId != null && !sessionId.isBlank()) {
            startOrUpdateInstance(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis,
                    totalMillis, anchorPositions, anchor, preferNative, decoderOverride);
            return;
        }
        startInternal(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, sessionId,
                startOffsetMillis, totalMillis, anchorPositions, true);
    }

    protected static void startOrUpdateInstance(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> anchorPositions,
            VideoPlaybackAnchor anchor, boolean preferNative, String decoderOverride) {
        startOrUpdateInstance(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis,
                totalMillis, anchorPositions, anchor, preferNative, decoderOverride,
                List.of(new VideoCandidate(videoUrl, codecId, targetWidth, targetHeight, fps, 0)));
    }

    protected static void startOrUpdateInstance(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            String sessionId, long startOffsetMillis, long totalMillis, Collection<BlockPos> anchorPositions,
            VideoPlaybackAnchor anchor, boolean preferNative, String decoderOverride, List<VideoCandidate> candidates) {
        PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (parsedSessionId == null) {
            return;
        }
        String normalizedSessionId = parsedSessionId.value();
        if (!com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection.allowMediaStart()) {
            stopIfSession(normalizedSessionId);
            return;
        }
        List<BlockPos> projectors = immutablePositions(anchorPositions);
        boolean hasHolographicConsumer = hasHolographicTurntableConsumer(anchor);
        if (projectors.isEmpty() && !hasHolographicConsumer) {
            stopIfSession(normalizedSessionId);
            return;
        }
        VideoPlaybackInstance existing = SESSION_INSTANCES.get(normalizedSessionId);
        long normalizedOffset = Math.max(0L, startOffsetMillis);
        if (existing != null) {
            existing.replaceProjectors(projectors);
            if (existing.canChaseToOffset(normalizedOffset)
                    || existing.requestSyncedReseek(normalizedOffset)) {
                return;
            }
        }
        VideoPlaybackInstance instance = new VideoPlaybackInstance(videoUrl, targetWidth, targetHeight, fps, codecId,
                normalizedSessionId,
                normalizedOffset, Math.max(0L, totalMillis), projectors, anchor, preferNative, decoderOverride,
                candidates);
        startProjectionInstance(instance);
    }

    /**
     * Serializes physical decoder ownership for one projection source. An
     * instance may be constructed while the old owner drains, but it is never
     * published or started until all four close handoff signals complete
     * normally.
     */
    protected static synchronized void startProjectionInstance(VideoPlaybackInstance instance) {
        String sessionId = instance.sessionId();
        Object ownerKey = instance.replacementOwnerKey();

        PendingProjectionStart duplicate = PENDING_PROJECTION_STARTS.get(sessionId);
        if (duplicate != null && Objects.equals(duplicate.ownerKey(), ownerKey)) {
            List<BlockPos> positions = instance.projectorPositions();
            if (!positions.isEmpty()) {
                duplicate.instance().replaceProjectors(positions);
            }
            if (instance.hasGuiConsumer()) {
                duplicate.instance().setGuiConsumer(true);
            }
            instance.abandonBeforeStart();
            return;
        }

        for (PendingProjectionStart pending : List.copyOf(PENDING_PROJECTION_STARTS.values())) {
            if (pending.sessionId().equals(sessionId) || Objects.equals(pending.ownerKey(), ownerKey)) {
                cancelPendingProjectionStart(pending);
            }
        }
        ProjectionReplacementGate.CloseHandoff replacementBarrier =
                ProjectionReplacementGate.CloseHandoff.completed();
        for (VideoPlaybackInstance current : SESSION_INSTANCES.instances()) {
            if (current.sessionId().equals(sessionId)
                    || Objects.equals(current.replacementOwnerKey(), ownerKey)) {
                // The same session reuses deterministic texture identifiers even
                // if its logical owner key changes, so carry the old render/native
                // handoff into the desired owner's gate as well.
                replacementBarrier = composeCloseHandoffs(replacementBarrier, current.closeHandoff());
                SESSION_INSTANCES.remove(current.sessionId(), current);
            }
        }

        ProjectionReplacementGate.Intent<Object> intent = PROJECTION_REPLACEMENTS.beginIntent(
                ownerKey, sessionId, replacementBarrier);
        PendingProjectionStart pending = new PendingProjectionStart(sessionId, ownerKey, instance, intent);
        PENDING_PROJECTION_STARTS.put(sessionId, pending);
        continueProjectionStart(pending, PROJECTION_REPLACEMENTS.evaluate(intent), null);
    }

    protected static synchronized void continueProjectionStart(PendingProjectionStart pending,
            ProjectionReplacementGate.Decision decision, Throwable failure) {
        if (PENDING_PROJECTION_STARTS.get(pending.sessionId()) != pending
                || !PROJECTION_REPLACEMENTS.isCurrent(pending.intent())) {
            abandonPendingProjectionStart(pending, false, failure);
            return;
        }
        if (failure != null || decision == ProjectionReplacementGate.Decision.FAIL_CLOSED) {
            abandonPendingProjectionStart(pending, true, failure);
            return;
        }
        if (decision == ProjectionReplacementGate.Decision.WAIT) {
            PROJECTION_REPLACEMENTS.waitFor(pending.intent(), PROJECTION_REPLACEMENT_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS).whenComplete((next, error) ->
                            executeProjectionContinuation(() -> continueProjectionStart(
                                    pending,
                                    next != null ? next : ProjectionReplacementGate.Decision.FAIL_CLOSED,
                                    error)));
            return;
        }

        AtomicBoolean published = new AtomicBoolean(false);
        boolean committed;
        try {
            committed = PROJECTION_REPLACEMENTS.commitIfOpen(pending.intent(), () -> {
                if (PENDING_PROJECTION_STARTS.get(pending.sessionId()) != pending) {
                    throw new IllegalStateException("projection replacement intent lost before publication");
                }
                // Install both the owner and session barriers before publication.
                // Registry removal can now race only into a born-pending handoff,
                // never into an unguarded decoder-start window.
                PROJECTION_REPLACEMENTS.retainCommitted(
                        pending.intent(), pending.instance().closeHandoff());
                SESSION_INSTANCES.replace(pending.sessionId(), pending.instance());
                pending.instance().start();
                PENDING_PROJECTION_STARTS.remove(pending.sessionId(), pending);
                PENDING_SESSIONS.clearLoading(pending.sessionId());
                published.set(true);
            });
        } catch (RuntimeException | Error publicationFailure) {
            // A thread-start or publication failure must not leave an orphaned
            // registry owner whose born-pending handoff can never be sealed.
            // Conditional removal retains the handoff before stop; the explicit
            // abandon is idempotent and also covers failure before publication.
            SESSION_INSTANCES.remove(pending.sessionId(), pending.instance());
            PENDING_PROJECTION_STARTS.remove(pending.sessionId(), pending);
            pending.instance().abandonBeforeStart();
            PENDING_SESSIONS.markFailure(pending.sessionId(), pending.instance().projectorPositions());
            LOGGER.error("投影视频替换实例发布或启动失败，已回滚并保持 fail-closed: session={} owner={}",
                    pending.sessionId(), pending.ownerKey(), publicationFailure);
            return;
        }
        if (!committed || !published.get()) {
            abandonPendingProjectionStart(pending, false, null);
        }
    }

    protected static void executeProjectionContinuation(Runnable continuation) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            continuation.run();
        } else {
            minecraft.execute(continuation);
        }
    }

    protected static void abandonPendingProjectionStart(PendingProjectionStart pending,
            boolean markFailure, Throwable failure) {
        PENDING_PROJECTION_STARTS.remove(pending.sessionId(), pending);
        pending.instance().abandonBeforeStart();
        if (markFailure) {
            PENDING_SESSIONS.markFailure(pending.sessionId(), pending.instance().projectorPositions());
            LOGGER.error("投影视频旧实例未正常物理收敛，禁止启动替换实例: session={} owner={}",
                    pending.sessionId(), pending.ownerKey(), failure);
        }
    }

    protected static synchronized void cancelPendingProjectionStart(PendingProjectionStart pending) {
        if (!PENDING_PROJECTION_STARTS.remove(pending.sessionId(), pending)) {
            return;
        }
        // Supersede both domain epochs before sealing the never-started
        // instance. Its born-pending handoff was never active ownership and
        // therefore must not become a retained barrier.
        PROJECTION_REPLACEMENTS.cancelIntent(pending.intent());
        pending.instance().abandonBeforeStart();
    }

    protected static synchronized void cancelPendingProjectionStart(String sessionId) {
        PendingProjectionStart pending = PENDING_PROJECTION_STARTS.get(sessionId);
        if (pending != null) {
            cancelPendingProjectionStart(pending);
        }
    }

    protected static synchronized void cancelAllPendingProjectionStarts() {
        for (PendingProjectionStart pending : List.copyOf(PENDING_PROJECTION_STARTS.values())) {
            cancelPendingProjectionStart(pending);
        }
    }

    protected static synchronized void detachPendingProjectionConsumer(BlockPos projectorPos) {
        for (PendingProjectionStart pending : List.copyOf(PENDING_PROJECTION_STARTS.values())) {
            pending.instance().removeProjector(projectorPos);
            if (!pending.instance().hasVideoConsumer()) {
                cancelPendingProjectionStart(pending);
            }
        }
    }

    protected static void disposeSessionInstance(VideoPlaybackInstance instance) {
        Object ownerKey = instance.replacementOwnerKey();
        ProjectionReplacementGate.CloseHandoff handoff = instance.closeHandoff();
        // Retain first: logical registry detach must never create an admission
        // gap, even if stop itself throws before it can signal close progress.
        PROJECTION_REPLACEMENTS.retainCloseHandoff(ownerKey, instance.sessionId(), handoff);
        instance.stop();
    }

    protected static ProjectionReplacementGate.CloseHandoff composeCloseHandoffs(
            ProjectionReplacementGate.CloseHandoff retained,
            ProjectionReplacementGate.CloseHandoff proposed) {
        if (retained == null || retained == proposed) {
            return proposed;
        }
        return new ProjectionReplacementGate.CloseHandoff(
                CompletableFuture.allOf(retained.closeReturned(), proposed.closeReturned()),
                CompletableFuture.allOf(retained.nativeTermination(), proposed.nativeTermination()),
                CompletableFuture.allOf(retained.decodeExit(), proposed.decodeExit()),
                CompletableFuture.allOf(retained.renderRelease(), proposed.renderRelease()));
    }

    protected static void startInternal(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, String sessionId, long startOffsetMillis,
            long totalMillis, Collection<BlockPos> anchorPositions, boolean catchUpDropsEnabled) {
        startInternal(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, sessionId,
                startOffsetMillis, totalMillis, anchorPositions, catchUpDropsEnabled, false);
    }

    protected static void startInternal(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId,
            boolean preferNative, String decoderOverride, String sessionId, long startOffsetMillis,
            long totalMillis, Collection<BlockPos> anchorPositions, boolean catchUpDropsEnabled,
            boolean forceRgbaOutput) {
        if (!com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection.allowMediaStart()) {
            return;
        }
        if (videoUrl == null || videoUrl.isBlank()) {
            return;
        }
        String normalizedSession = sessionId != null ? sessionId : "";
        long normalizedOffset = Math.max(0L, startOffsetMillis);
        if (!normalizedSession.isBlank() && LEGACY_WORKER.isRunning()
                && LEGACY_PREVIEW.matchesSession(normalizedSession)) {
            replaceActiveProjectors(anchorPositions);
            if (isSessionRunningAtOffset(normalizedSession, normalizedOffset)) {
                return;
            }
            stopForReplace();
        }
        if (!normalizedSession.isBlank() && LEGACY_WORKER.isRunning()) {
            stopForReplace();
        }
        long generation = LEGACY_WORKER.tryBegin();
        if (generation == LegacyPreviewWorkerLifecycle.REJECTED_GENERATION) {
            return;
        }

        hasFrame = false;
        activeNetworkFailure = false;
        width = Math.max(1, targetWidth);
        height = Math.max(1, targetHeight);
        int protectedFps = protectedUploadFps(targetWidth, targetHeight, fps);
        if (protectedFps < Math.max(1, fps)) {
            LOGGER.warn("视频分辨率过高，限制上传/显示帧率以保护游戏 FPS: {}x{} @ {}fps -> {}fps。可用 -Dbili.video.protect_game_fps=false 关闭",
                    targetWidth, targetHeight, Math.max(1, fps), protectedFps);
        }
        activeFps = protectedFps;
        activeStartOffsetMillis = normalizedOffset;
        activeStartNanoTime = System.nanoTime();
        List<BlockPos> projectors = immutablePositions(anchorPositions);
        PlaybackRequest request = new PlaybackRequest(videoUrl, targetWidth, targetHeight, protectedFps, codecId,
                preferNative, decoderOverride, normalizedSession, startOffsetMillis, totalMillis, projectors,
                forceRgbaOutput);
        LEGACY_PREVIEW.begin(normalizedSession, projectors, request);
        BlockPos primaryProjector = LEGACY_PREVIEW.primaryProjector();
        if (primaryProjector != null) {
            anchorX = primaryProjector.getX() + 0.5D;
            anchorY = primaryProjector.getY() + 1.8D;
            anchorZ = primaryProjector.getZ() + 0.5D;
            anchorYawDeg = 0.0F;
            anchorInitialized = true;
        } else {
            anchorInitialized = false;
        }

        Thread thread = NetMusicThreadFactory.daemonThread("bili-video-billboard-preview",
                () -> decodeLoop(videoUrl, targetWidth, targetHeight, protectedFps, codecId, preferNative,
                        decoderOverride, startOffsetMillis, totalMillis, generation, catchUpDropsEnabled,
                        forceRgbaOutput));
        if (!LEGACY_WORKER.bindWorker(generation, thread)) {
            return;
        }
        thread.start();
        LOGGER.info("视频 billboard 预览已启动: {}x{} @ {}fps, renderBackend={}, decodeFormat={}, catchUpDrops={}", width,
                height,
                activeFps, RENDER_BACKEND, YUV_DECODE_BACKEND
                        ? (isCustomYuvShaderAvailable() ? yuvDecodeFormat().name() + "→RGB(shader)"
                                : yuvDecodeFormat().name() + "→RGBA(cpu/iris-fallback)")
                        : "RGBA",
                catchUpDropsEnabled);
    }

    protected static int protectedUploadFps(int frameWidth, int frameHeight, int requestedFps) {
        int fps = Math.max(1, requestedFps);
        if (!PROTECT_GAME_FPS) {
            return fps;
        }
        long pixels = (long) Math.max(1, frameWidth) * Math.max(1, frameHeight);
        if (pixels >= 7000L * 4000L) {
            return Math.min(fps, Math.max(1, PROTECTED_8K_FPS));
        }
        if (pixels >= 3000L * 1600L) {
            return Math.min(fps, Math.max(1, PROTECTED_4K_FPS));
        }
        return fps;
    }

    public static void startTestPattern(int targetWidth, int targetHeight, int fps) {
        long generation = LEGACY_WORKER.tryBegin();
        if (generation == LegacyPreviewWorkerLifecycle.REJECTED_GENERATION) {
            return;
        }

        hasFrame = false;
        activeNetworkFailure = false;
        anchorInitialized = false;
        width = Math.max(1, targetWidth);
        height = Math.max(1, targetHeight);
        activeFps = Math.max(1, fps);
        activeStartOffsetMillis = 0L;
        activeStartNanoTime = System.nanoTime();

        Thread thread = NetMusicThreadFactory.daemonThread("bili-video-billboard-test-pattern",
                () -> decodeTestPatternLoop(targetWidth, targetHeight, fps, generation));
        if (!LEGACY_WORKER.bindWorker(generation, thread)) {
            return;
        }
        thread.start();
        LOGGER.info("视频 billboard 本地测试图预览已启动: {}x{} @ {}fps, pixelMode={}", targetWidth,
                targetHeight, fps, VideoFrameUploader.pixelMode());
    }

    public static void stop() {
        cancelAllPendingProjectionStarts();
        SESSION_INSTANCES.clear();
        PENDING_SESSIONS.clear();
        LegacyPreviewWorkerLifecycle.Detached<Thread, AutoCloseable> detached = LEGACY_WORKER.stopAndDetach();
        hasFrame = false;
        activeNetworkFailure = false;
        anchorInitialized = false;
        activeFps = 0;
        activeStartOffsetMillis = 0L;
        activeStartNanoTime = 0L;
        LEGACY_PREVIEW.clear();
        berManagedProjectorPositions.clear();
        BER_SUBMITTED_PROJECTORS.clear();
        PROJECTOR_VISIBILITY_CACHE.clear();
        resetLocalRenderAnchors();
        closeActiveDecoderAsync(detached.decoder());
        Thread thread = detached.worker();
        if (thread != null) {
            thread.interrupt();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            releaseTexture();
        } else {
            minecraft.execute(VideoBillboardPreview::releaseTexture);
        }
    }

    protected static void stopForReplace() {
        LegacyPreviewWorkerLifecycle.Detached<Thread, AutoCloseable> detached = LEGACY_WORKER.stopAndDetach();
        hasFrame = false;
        activeNetworkFailure = false;
        LEGACY_PREVIEW.clearForReplacement();
        activeFps = 0;
        activeStartOffsetMillis = 0L;
        activeStartNanoTime = 0L;
        closeActiveDecoderAsync(detached.decoder());
        Thread thread = detached.worker();
        if (thread != null) {
            thread.interrupt();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            releaseTexture();
        } else {
            minecraft.execute(VideoBillboardPreview::releaseTexture);
        }
    }

    public static void stopIfSession(String sessionId) {
        String normalized = sessionId != null ? sessionId : "";
        cancelPendingProjectionStart(normalized);
        PENDING_SESSIONS.clearSession(normalized);
        // YUV 立即渲染路径未激活时捕获的姿态不会被消费，会话结束必须显式清理。
        PlaybackSessionId.parse(normalized).ifPresent(playbackSessionId ->
                PROJECTOR_IMMEDIATE_POSES.keySet().removeIf(
                        key -> key.playbackSessionId().equals(playbackSessionId)));
        SESSION_INSTANCES.remove(normalized);
        if (!normalized.isBlank() && LEGACY_PREVIEW.matchesSession(normalized)) {
            stop();
        }
    }

    public static void stopIfProjector(BlockPos projectorPos) {
        if (projectorPos == null) {
            return;
        }
        berManagedProjectorPositions.remove(projectorPos);
        BER_SUBMITTED_PROJECTORS.removeIf(key -> key.projectorPos().equals(projectorPos));
        PROJECTOR_VISIBILITY_CACHE.remove(projectorPos);
        PROJECTOR_IMMEDIATE_POSES.keySet().removeIf(key -> key.projectorPos().equals(projectorPos));
        PENDING_SESSIONS.detachProjector(projectorPos);
        detachPendingProjectionConsumer(projectorPos);
        SESSION_INSTANCES.forEach(instance -> instance.removeProjector(projectorPos));
        SESSION_INSTANCES.removeIf(instance -> !instance.hasProjectors() && !instance.hasVideoConsumer());
        boolean requiredProjector = LEGACY_PREVIEW.requiresProjector();
        LEGACY_PREVIEW.detachProjector(projectorPos);
        if (requiredProjector && LEGACY_PREVIEW.projectors().isEmpty()) {
            stop();
        }
    }

    public static void attachProjectorToTurntable(BlockPos turntablePos, BlockPos projectorPos) {
        if (turntablePos == null || projectorPos == null) {
            return;
        }
        berManagedProjectorPositions.add(projectorPos.immutable());
        for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            if (instance.isForTurntable(turntablePos)) {
                instance.addProjector(projectorPos);
            }
        }
    }

    public static boolean isProjectorRenderedByBer(BlockPos projectorPos) {
        return projectorPos != null && berManagedProjectorPositions.contains(projectorPos);
    }

    /** 由 BER 在真正通过引擎裁剪并进入提交阶段时调用。 */
    public static void markProjectorSubmittedByBer(String sessionId, BlockPos projectorPos) {
        PlaybackSessionId.parse(sessionId).ifPresent(playbackSessionId ->
                markProjectorSubmittedByBer(playbackSessionId, projectorPos));
    }

    public static void markProjectorSubmittedByBer(PlaybackSessionId playbackSessionId, BlockPos projectorPos) {
        if (playbackSessionId != null && projectorPos != null) {
            BER_SUBMITTED_PROJECTORS.markSubmitted(
                    new BerProjectorSubmission(playbackSessionId, projectorPos.immutable()));
        }
    }

    /**
     * 接受当前帧或上一帧的标记，以兼容 BER submit 与全局几何事件在不同渲染后端下的先后顺序。
     */
    static boolean wasProjectorRecentlySubmittedByBer(String sessionId, BlockPos projectorPos) {
        PlaybackSessionId playbackSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
        if (playbackSessionId == null || projectorPos == null) {
            return false;
        }
        return BER_SUBMITTED_PROJECTORS.wasRecentlySubmitted(
                new BerProjectorSubmission(playbackSessionId, projectorPos));
    }

    static void beginBerVisibilityFrame() {
        BER_SUBMITTED_PROJECTORS.beginFrame();
    }

    public static boolean hasSessionForTurntable(BlockPos turntablePos) {
        if (turntablePos == null) {
            return false;
        }
        for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            if (instance.isForTurntable(turntablePos)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasSessionForTurntable(BlockPos turntablePos, String sessionId) {
        if (turntablePos == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            if (instance.isForTurntable(turntablePos) && instance.isSession(sessionId)) {
                return true;
            }
        }
        return LEGACY_WORKER.isRunning() && LEGACY_WORKER.isStarted()
                && LEGACY_PREVIEW.matchesSession(sessionId);
    }

    public static boolean isSessionRunning(String sessionId) {
        String normalized = sessionId != null ? sessionId : "";
        VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
        if (instance != null) {
            return instance.isRunning();
        }
        return LEGACY_WORKER.isRunning() && LEGACY_WORKER.isStarted()
                && !normalized.isBlank() && LEGACY_PREVIEW.matchesSession(normalized);
    }

    public static synchronized void updateSessionProjectors(String sessionId,
            Collection<BlockPos> projectorPositions) {
        String normalized = sessionId != null ? sessionId : "";
        if (normalized.isBlank()) {
            return;
        }
        List<BlockPos> positions = immutablePositions(projectorPositions);
        PENDING_SESSIONS.updateProjectors(normalized, positions);
        PendingProjectionStart pending = PENDING_PROJECTION_STARTS.get(normalized);
        if (pending != null) {
            pending.instance().replaceProjectors(positions);
            if (!pending.instance().hasVideoConsumer()) {
                cancelPendingProjectionStart(pending);
            }
            return;
        }
        VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
        if (instance != null) {
            instance.replaceProjectors(projectorPositions);
            return;
        }
        if (LEGACY_WORKER.isRunning() && LEGACY_PREVIEW.matchesSession(normalized)) {
            replaceActiveProjectors(projectorPositions);
        }
    }

    public static void beginPendingLoading(String sessionId, Collection<BlockPos> projectorPositions) {
        String normalized = sessionId != null ? sessionId : "";
        List<BlockPos> positions = immutablePositions(projectorPositions);
        if (normalized.isBlank() || positions.isEmpty()) {
            return;
        }
        PENDING_SESSIONS.beginLoading(normalized, positions);
    }

    public static void clearPendingLoading(String sessionId) {
        String normalized = sessionId != null ? sessionId : "";
        if (!normalized.isBlank()) {
            PENDING_SESSIONS.clearLoading(normalized);
        }
    }

    protected static boolean hasHolographicTurntableConsumer(VideoPlaybackAnchor anchor) {
        if (anchor == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return false;
        }
        for (var binding : HolographicGlassesClient.screenBindings()) {
            if (binding.source() != null && binding.source().isTurntable()
                    && minecraft.level.dimension().equals(binding.source().dimension())
                    && anchor.isForTurntable(binding.source().pos())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSessionRunningAtOffset(String sessionId, long requestedOffsetMillis) {
        return isSessionRunningAtOffset(sessionId, requestedOffsetMillis, 1_500L);
    }

    public static boolean isSessionRunningAtOffset(String sessionId, long requestedOffsetMillis,
            long toleranceMillis) {
        if (!isSessionRunning(sessionId)) {
            return false;
        }
        VideoPlaybackInstance instance = SESSION_INSTANCES.get(sessionId);
        if (instance != null) {
            return instance.isRunningAtOffset(Math.max(0L, requestedOffsetMillis), Math.max(0L, toleranceMillis));
        }
        long expectedOffset = activeStartOffsetMillis
                + Math.max(0L, (System.nanoTime() - activeStartNanoTime) / 1_000_000L);
        return Math.abs(expectedOffset - Math.max(0L, requestedOffsetMillis)) < Math.max(0L, toleranceMillis);
    }

    public static boolean canSessionChaseToOffset(String sessionId, long requestedOffsetMillis) {
        if (!isSessionRunning(sessionId)) {
            return false;
        }
        VideoPlaybackInstance instance = SESSION_INSTANCES.get(sessionId);
        if (instance != null) {
            return instance.canChaseToOffset(Math.max(0L, requestedOffsetMillis));
        }
        return isSessionRunningAtOffset(sessionId, requestedOffsetMillis,
                VideoPipelineProperties.chaseWindowMillis());
    }

    public static boolean isSessionWaitingForFirstFrame(String sessionId) {
        String normalized = sessionId != null ? sessionId : "";
        if (normalized.isBlank()) {
            return false;
        }
        VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
        if (instance != null) {
            return instance.ensureFirstFrameProgress();
        }
        return LEGACY_WORKER.isRunning() && LEGACY_WORKER.isStarted()
                && LEGACY_PREVIEW.matchesSession(normalized) && !hasFrame;
    }

    public static VideoStatus getStatusForProjector(BlockPos projectorPos) {
        for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            if (instance.containsProjector(projectorPos)) {
                return instance.status();
            }
        }
        if (!LEGACY_WORKER.isRunning() || !LEGACY_WORKER.isStarted()) {
            return VideoStatus.empty();
        }
        if (projectorPos != null && !LEGACY_PREVIEW.projectors().isEmpty()
                && !LEGACY_PREVIEW.projectors().contains(projectorPos)) {
            return VideoStatus.empty();
        }
        return new VideoStatus(width, height, activeFps, hasFrame, !LEGACY_PREVIEW.sessionId().isBlank());
    }

    public static VideoSyncStatus getSyncStatus(String sessionId) {
        String normalized = sessionId != null ? sessionId : "";
        VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
        if (instance != null) {
            return new VideoSyncStatus(instance.isRunning(), instance.hasFrame(), instance.mediaMillis(),
                    instance.queuedMediaMillis(), instance.status().width(), instance.status().height(),
                    instance.status().fps());
        }
        if (LEGACY_WORKER.isRunning() && LEGACY_WORKER.isStarted()
                && !normalized.isBlank() && LEGACY_PREVIEW.matchesSession(normalized)) {
            long mediaMillis = activeStartOffsetMillis
                    + Math.max(0L, (System.nanoTime() - activeStartNanoTime) / 1_000_000L);
            return new VideoSyncStatus(true, hasFrame, mediaMillis, -1L, width, height, activeFps);
        }
        return VideoSyncStatus.empty();
    }

    protected static void replaceActiveProjectors(Collection<BlockPos> projectorPositions) {
        PROJECTOR_VISIBILITY_CACHE.clear();
        anchorInitialized = false;
        LEGACY_PREVIEW.replaceProjectors(immutablePositions(projectorPositions));
    }

}
