package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.github.tartaricacid.netmusic.client.audio.MusicPlayManager;
import com.github.tartaricacid.netmusic.client.audio.NetMusicSound;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPrepareProperties;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.sync.ModernTurntablePlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.media.sync.AudioStartupSync;
import com.zhongbai233.net_music_can_play_bili.media.sync.MediaRequestToken;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackDemandIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;

/**
 * 现代唱片机客户端播放命令的唯一编排入口。
 *
 * <p>
 * {@link #play(ClientPlaybackCommand)} 必须在 Minecraft 客户端线程调用。
 * </p>
 */
public final class ModernTurntablePlaybackCoordinator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long AUDIO_PREPARE_TIMEOUT_SECONDS =
            ClientMediaPrepareProperties.settings().modernPrepareTimeoutSeconds();
    private static final long INDEXED_IDLE_GRACE_MILLIS = 1_500L;
    private static final AtomicLong COMPAT_PREPARE_SEQUENCE = new AtomicLong();
    private static final ConcurrentHashMap<BlockPos, Long> LATEST_COMPAT_PREPARE = new ConcurrentHashMap<>();
        private static final ConcurrentHashMap<BlockPos, CancellableTaskFuture<ClientMediaPreparer.PreparedMedia>> COMPAT_PREPARES =
            new ConcurrentHashMap<>();
    private static final AudioPlaybackDemandIndex<ClientPlaybackCommand> INDEXED_DEMAND =
            new AudioPlaybackDemandIndex<>();
    private static final ConcurrentHashMap<PlaybackSourceId,
            CancellableTaskFuture<ClientMediaPreparer.PreparedMedia>> INDEXED_PREPARES = new ConcurrentHashMap<>();

    private ModernTurntablePlaybackCoordinator() {
    }

    public static void clearPendingPrepares() {
        COMPAT_PREPARES.forEach((sourcePos, prepare) -> {
            if (COMPAT_PREPARES.remove(sourcePos, prepare)) {
                prepare.cancel(true);
            }
        });
        LATEST_COMPAT_PREPARE.clear();
        INDEXED_PREPARES.values().forEach(prepare -> prepare.cancel(true));
        INDEXED_PREPARES.clear();
        INDEXED_DEMAND.clear();
    }

    /** 普通唱片机的 B站选曲兼容入口；Mixin 只负责把协议消息冻结成命令。 */
    public static void playCompatible(ClientPlaybackCommand command) {
        if (command == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> playCompatible(command));
            return;
        }
        BlockPos sourcePos = sourcePos(command);
        long generation = COMPAT_PREPARE_SEQUENCE.incrementAndGet();
        LATEST_COMPAT_PREPARE.put(sourcePos, generation);
        CancellableTaskFuture<ClientMediaPreparer.PreparedMedia> prepare = ClientMediaPreparer.prepareAudioOnlyAsync(
            command.rawUrl(), command.playUrl(), command.songName(), false);
        CancellableTaskFuture<ClientMediaPreparer.PreparedMedia> replaced = COMPAT_PREPARES.put(sourcePos, prepare);
        if (replaced != null) {
            replaced.cancel(true);
        }
        prepare
                .completeOnTimeout(null, AUDIO_PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .whenComplete((prepared, error) -> {
                if (prepared == null || error != null) {
                prepare.cancelWorker();
                }
                COMPAT_PREPARES.remove(sourcePos, prepare);
                minecraft.execute(
                    () -> finishCompatiblePrepare(command, sourcePos, generation, prepared, error));
            });
    }

    private static void finishCompatiblePrepare(ClientPlaybackCommand command, BlockPos sourcePos, long generation,
            ClientMediaPreparer.PreparedMedia prepared, Throwable error) {
        if (!LATEST_COMPAT_PREPARE.remove(sourcePos, generation)) {
            return;
        }
        if (error != null || prepared == null) {
            LOGGER.warn("普通唱片机 B站音频后台准备失败，停止音频提交: song='{}' reason={}",
                    command.songName(), error != null ? error.toString() : "timeout-or-empty-result");
            return;
        }
        if (prepared.audioPresence() != ClientMediaPreparer.AudioPresence.PRESENT) {
            LOGGER.warn("普通唱片机 B站音频不可提交: song='{}' presence={}",
                    command.songName(), prepared.audioPresence());
            return;
        }
        String playUrl = prepared.playUrl();
        if (!PlayableMediaUrl.isHttp(playUrl)) {
            LOGGER.warn("普通唱片机拒绝非 HTTP(S) 解析结果: song='{}' value='{}'", command.songName(), playUrl);
            return;
        }
        BiliPlaybackDiagnostics.beginPlayback(command.songName(), command.rawUrl(), playUrl);
        LOGGER.debug("B站/NetMusic 普通唱片机兼容播放: song='{}' audioHost={}", command.songName(),
                ClientMediaPreparer.hostOf(playUrl));
        try {
            MusicPlayManager.play(playUrl, command.songName(),
                    url -> new NetMusicSound(sourcePos, url, command.remainingSeconds(), null));
        } catch (RuntimeException launchError) {
            LOGGER.warn("普通唱片机音频提交失败: song='{}' value='{}' reason={}", command.songName(), playUrl,
                    launchError.toString());
        }
    }

    public static void play(ClientPlaybackCommand command) {
        if (command == null) {
            return;
        }
        BlockPos sourcePos = sourcePos(command);
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> play(command));
            return;
        }
        String liveRoomId = com.zhongbai233.net_music_can_play_bili.bili.BiliLiveRoomInput
                .roomIdFromPlaceholder(com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync
                        .strip(command.playUrl()));
        if (!liveRoomId.isEmpty()) {
            playLive(command, sourcePos, liveRoomId);
            return;
        }

        if (command.minecartAnchor() != null && command.hasSession()) {
            ClientMinecartAudioAnchors.register(command.sessionId(), command.minecartAnchor().entityId(),
                    command.minecartAnchor().entityUuid());
        }
        if (command.hasSession() && !admitsCommand(minecraft, command, sourcePos)) {
            return;
        }
        if (command.hasSession()
                && !ModernTurntablePlaybackTracker.tryStart(sourcePos, command.sessionId(),
                        command.remainingSeconds())) {
            announceIndexed(command);
            syncVideo(command);
            tickIndexedPlaybackDemand();
            return;
        }
        if (command.hasSession()) {
            bindSessionResources(sourcePos, command.sessionId());
        }
        syncVideo(command);
        announceIndexed(command);
        tickIndexedPlaybackDemand();
    }

    private static void queueModernPrepare(ClientPlaybackCommand command, BlockPos sourcePos) {
        Minecraft minecraft = Minecraft.getInstance();
        long prepareStartedNanos = System.nanoTime();
        CancellableTaskFuture<ClientMediaPreparer.PreparedMedia> prepare = ClientMediaPreparer.prepareAudioOnlyAsync(
                command.rawUrl(), command.playUrl(), command.songName(), true);
        PlaybackSourceId sourceId = sourceId(command);
        CancellableTaskFuture<ClientMediaPreparer.PreparedMedia> replaced = INDEXED_PREPARES.put(sourceId, prepare);
        if (replaced != null) {
            replaced.cancel(true);
        }
        var completion = prepare.completeOnTimeout(null, AUDIO_PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (command.hasSession() && !ModernTurntablePlaybackTracker.onCancel(sourcePos, command.sessionId(),
            () -> prepare.cancel(true))) {
            prepare.cancel(true);
            return;
        }
        completion.whenComplete((prepared, error) -> {
            INDEXED_PREPARES.remove(sourceId, prepare);
            if (prepared == null || error != null) {
            prepare.cancelWorker();
            }
            minecraft.execute(
                () -> finishModernPrepare(command, sourcePos, prepareStartedNanos, prepared, error));
        });
    }

    private static void finishModernPrepare(ClientPlaybackCommand command, BlockPos sourcePos,
            long prepareStartedNanos, ClientMediaPreparer.PreparedMedia prepared, Throwable error) {
        AudioPlaybackDemandIndex.Snapshot<ClientPlaybackCommand> demand = INDEXED_DEMAND
                .snapshot(sourceId(command)).orElse(null);
        if (demand == null || demand.state() != AudioPlaybackDemandIndex.State.STARTING
                || demand.endpointIds().isEmpty()) {
            return;
        }
        if (command.hasSession()
                && !ModernTurntablePlaybackTracker.isActiveSession(sourcePos, command.sessionId())) {
            return;
        }
        if (error != null) {
            LOGGER.warn("现代唱片机 B站音频后台准备失败，停止音频提交: pos={} session={} song='{}' reason={}",
                    sourcePos, command.sessionId(), command.songName(), error.toString());
        }
        if (prepared == null) {
            prepared = new ClientMediaPreparer.PreparedMedia("", null, ClientMediaPreparer.AudioPresence.FAILED);
        }
        if (prepared.audioPresence() == ClientMediaPreparer.AudioPresence.FAILED) {
            LOGGER.warn("现代唱片机音频解析失败，跳过无效回退并暂缓同会话重试: pos={} session={} song='{}'",
                    sourcePos, command.sessionId(), command.songName());
            syncVideo(command);
            ModernTurntablePlaybackTracker.suppressRestart(sourcePos, command.sessionId());
            ModernTurntablePlaybackTracker.finish(sourcePos, command.sessionId());
            removeIndexed(command);
            return;
        }

        if (prepared.audioPresence() == ClientMediaPreparer.AudioPresence.ABSENT) {
            LOGGER.debug("现代唱片机确认纯视频媒体，跳过音频提交: pos={} session={} song='{}'",
                    sourcePos, command.sessionId(), command.songName());
            syncVideo(command);
            removeIndexed(command);
            return;
        }
        long prepareMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - prepareStartedNanos));
        final long launchElapsedMillis = AudioStartupSync.compensatedOffsetMillis(
                command.elapsedMillis(), command.totalMillis(), prepareMillis);
        SyncedMediaPlaybackLauncher.LaunchResult launch;
        try {
            launch = SyncedMediaPlaybackLauncher.fromPrepared(
                    command.rawUrl(), command.songName(), prepared, command.playUrl(), command.sessionId(),
                    launchElapsedMillis, command.totalMillis(), sourcePos, null, command.minecartAnchor());
        } catch (RuntimeException launchError) {
            LOGGER.error("现代唱片机客户端提交播放失败: pos={} session={} song='{}'", sourcePos,
                    command.sessionId(), command.songName(), launchError);
            ModernTurntablePlaybackTracker.fail(sourcePos, command.sessionId());
            return;
        }
        if (launch == null) {
            ModernTurntablePlaybackTracker.finish(sourcePos, command.sessionId());
            return;
        }
        if (command.hasSession() && launch.requestToken().isPresent()) {
            MediaRequestToken requestToken = launch.requestToken().orElseThrow();
            if (!ModernTurntablePlaybackTracker.onCancel(sourcePos, command.sessionId(),
                    () -> HttpAudioStreamHandler.cancelRequest(requestToken))) {
                HttpAudioStreamHandler.cancelRequest(requestToken);
                return;
            }
        }

        LOGGER.debug(
                "现代唱片机客户端接管播放: song='{}' session={} pos={} elapsed={}ms total={}ms biliSelection={} lyricsAsync={} audioHost={} videoSync=scheduled",
                command.songName(), command.sessionId(), sourcePos, launchElapsedMillis,
                command.totalMillis(), command.biliSelection(), command.loadLyrics(),
                ClientMediaPreparer.hostOf(launch.playUrl()));
        if (command.loadLyrics()) {
            loadLyricsAsync(command);
        }
        boolean submitted = SyncedMediaPlaybackLauncher.play(launch, command.songName(),
                (url, lyricRecord) -> new ModernTurntableSound(sourcePos, url,
                        command.remainingSeconds(), lyricRecord, command.sessionId(), launchElapsedMillis,
                        command.rawUrl(), command.songName(), command.durationMillis(), sourceId(command)),
                false);
        if (!submitted) {
            ModernTurntablePlaybackTracker.finish(sourcePos, command.sessionId());
            removeIndexed(command);
            return;
        }
        // 视频准入由投影面可见性独立驱动，不等待 OpenAL 音频时间线。
        if (command.hasSession()) {
            syncVideo(command);
        }
    }

    /**
     * B站直播播放：跳过直链解析与歌词，占位地址原样注册请求 token，
     * 由 BiliLiveAudioStreamHandler 在声音线程解析真实直播流并输出到 OpenAL。
     */
    private static void playLive(ClientPlaybackCommand command, BlockPos sourcePos, String roomId) {
        if (command.hasSession()
                && !ModernTurntablePlaybackTracker.tryStart(sourcePos, command.sessionId(),
                        command.remainingSeconds())) {
            announceIndexed(command);
            tickIndexedPlaybackDemand();
            return;
        }
        if (command.hasSession()) {
            bindSessionResources(sourcePos, command.sessionId());
        }
        syncVideo(command);
        announceIndexed(command);
        tickIndexedPlaybackDemand();
    }

    private static void launchLive(ClientPlaybackCommand command, BlockPos sourcePos, String roomId) {
        SyncedMediaPlaybackLauncher.LaunchResult launch;
        try {
            launch = SyncedMediaPlaybackLauncher.fromPrepared(command.rawUrl(), command.songName(),
                    new ClientMediaPreparer.PreparedMedia(command.playUrl(), null), command.playUrl(),
                    command.sessionId(), 0L, 0L, sourcePos, null, null);
        } catch (RuntimeException launchError) {
            LOGGER.error("直播机客户端提交播放失败: pos={} session={} room={}", sourcePos,
                    command.sessionId(), roomId, launchError);
            ModernTurntablePlaybackTracker.fail(sourcePos, command.sessionId());
            return;
        }
        if (launch == null) {
            ModernTurntablePlaybackTracker.finish(sourcePos, command.sessionId());
            return;
        }
        if (command.hasSession() && launch.requestToken().isPresent()) {
            MediaRequestToken requestToken = launch.requestToken().orElseThrow();
            if (!ModernTurntablePlaybackTracker.onCancel(sourcePos, command.sessionId(),
                    () -> HttpAudioStreamHandler.cancelRequest(requestToken))) {
                HttpAudioStreamHandler.cancelRequest(requestToken);
                return;
            }
        }
        if (command.hasSession()) {
            ModernTurntablePlaybackTracker.onCancel(sourcePos, command.sessionId(),
                    () -> com.zhongbai233.net_music_can_play_bili.client.LiveStreamerVideoClient
                            .forget(command.sessionId()));
        }
        LOGGER.debug("直播机客户端接管播放: room={} session={} pos={}", roomId, command.sessionId(), sourcePos);
        boolean submitted = SyncedMediaPlaybackLauncher.play(launch, command.songName(),
                (url, ignoredLyric) -> new LiveStreamerSound(sourcePos, url, command.remainingSeconds(),
                        command.sessionId(), sourceId(command)),
                false);
        if (!submitted) {
            ModernTurntablePlaybackTracker.finish(sourcePos, command.sessionId());
            removeIndexed(command);
        }
    }

    /** Client-tick entry: metadata remains idle until an indexed endpoint is actually audible. */
    public static void tickIndexedPlaybackDemand() {
        for (AudioPlaybackDemandIndex.SourceSnapshot<ClientPlaybackCommand> sourceSnapshot
                : INDEXED_DEMAND.snapshots()) {
            ClientPlaybackCommand command = sourceSnapshot.playback().payload();
            PlaybackSessionId sessionId = sourceSnapshot.playback().sessionId();
            BlockPos pos = sourcePos(command);
            if (!ModernTurntablePlaybackTracker.isActiveSession(pos, sessionId.value())) {
                LOGGER.debug("索引播放移除非活动会话: source={} pos={} session={} state={}",
                        sourceSnapshot.sourceId(), pos, sessionId, sourceSnapshot.playback().state());
                INDEXED_DEMAND.remove(sourceSnapshot.sourceId(), sessionId);
                continue;
            }
            Set<UUID> demands = new HashSet<>(ClientAudioEndpointIndex.audibleDemands(sourceSnapshot.sourceId()));
            demands.addAll(ClientAudioEndpointIndex.anticipatedDemands(sourceSnapshot.sourceId()));
            if (ClientAudioOutputRegistry.hasPreparationDemand(pos, sourceSnapshot.sourceId(),
                    sessionId.value())) {
                demands.add(sourceSnapshot.sourceId().value());
            }
            INDEXED_DEMAND.updateDemand(sourceSnapshot.sourceId(), sessionId, demands,
                    System.currentTimeMillis());
            if (INDEXED_DEMAND.claimStopAfterIdle(sourceSnapshot.sourceId(), sessionId,
                    System.currentTimeMillis(), INDEXED_IDLE_GRACE_MILLIS)) {
                LOGGER.debug("索引播放范围外退役: source={} pos={} session={} demands={}",
                        sourceSnapshot.sourceId(), pos, sessionId, demands.size());
                CancellableTaskFuture<ClientMediaPreparer.PreparedMedia> prepare =
                        INDEXED_PREPARES.remove(sourceSnapshot.sourceId());
                if (prepare != null) {
                    prepare.cancel(true);
                }
                ModernTurntablePlaybackTracker.retireForDemandIdle(pos, sessionId.value());
                continue;
            }
            ClientPlaybackCommand admitted = INDEXED_DEMAND.claimStart(sourceSnapshot.sourceId(), sessionId)
                    .orElse(null);
            if (admitted == null) {
                continue;
            }
            LOGGER.debug("索引播放需求重启: source={} pos={} session={} demands={}",
                    sourceSnapshot.sourceId(), pos, sessionId, demands.size());
            String roomId = com.zhongbai233.net_music_can_play_bili.bili.BiliLiveRoomInput.roomIdFromPlaceholder(
                    PlaybackSync.strip(admitted.playUrl()));
            if (!roomId.isEmpty()) {
                launchLive(admitted, sourcePos(admitted), roomId);
            } else {
                queueModernPrepare(admitted, sourcePos(admitted));
            }
        }
    }

    public static ClientPlaybackCommand command(net.minecraft.core.BlockPos sourcePos, String rawUrl, String playUrl,
            String songName, int remainingSeconds,
            com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync.Metadata sync,
            com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync.MinecartAnchor minecartAnchor,
            boolean biliSelection) {
        var metadata = sync != null ? sync
                : new com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync.Metadata("", 0L, 0L);
        return new ClientPlaybackCommand(sourcePos.getX(), sourcePos.getY(), sourcePos.getZ(), rawUrl, playUrl,
                songName, remainingSeconds,
                metadata.sessionId(), metadata.elapsedMillis(), metadata.totalMillis(), minecartAnchor, biliSelection,
                GeneralConfig.ENABLE_PLAYER_LYRICS.get());
    }

    private static boolean admitsCommand(Minecraft minecraft, ClientPlaybackCommand command, BlockPos sourcePos) {
        String authoritativeSessionId = "";
        boolean authoritativeSourcePresent = false;
        var level = minecraft.level;
        if (level != null && level.getBlockEntity(sourcePos) instanceof ModernTurntableBlockEntity turntable) {
            authoritativeSourcePresent = true;
            if (turntable.isPlaying()) {
                authoritativeSessionId = turntable.getPlaybackSyncMetadata().sessionId();
            }
        }
        String trackedSessionId = ModernTurntablePlaybackTracker.currentSessionId(sourcePos, command.sessionId());
        boolean explicitlyStopped = ModernTurntablePlaybackTracker.wasExplicitlyStopped(
                sourcePos, command.sessionId());
        ModernTurntableCommandAdmissionPolicy.Decision decision = ModernTurntableCommandAdmissionPolicy.decide(
                command.sessionId(), authoritativeSessionId, trackedSessionId, authoritativeSourcePresent,
                explicitlyStopped);
        if (!decision.accepted()) {
            LOGGER.debug(
                    "丢弃乱序现代唱片机播放命令: pos={} incomingSession={} authoritativeSession={} trackedSession={} decision={}",
                    sourcePos, command.sessionId(), authoritativeSessionId, trackedSessionId, decision);
        }
        return decision.accepted();
    }

    private static void syncVideo(ClientPlaybackCommand command) {
        ModernTurntableVideoClient.syncFromPlayback(command.rawUrl(), sourcePos(command), command.syncMetadata());
    }

    private static void loadLyricsAsync(ClientPlaybackCommand command) {
        CancellableTaskFuture<com.github.tartaricacid.netmusic.api.lyric.LyricRecord> lyric =
                ClientMediaPreparer.buildLyricAsync(command.rawUrl(), command.songName());
        BlockPos sourcePos = sourcePos(command);
        if (command.hasSession() && !ModernTurntablePlaybackTracker.onCancel(sourcePos, command.sessionId(),
                () -> lyric.cancel(true))) {
            lyric.cancel(true);
            return;
        }
        lyric.whenComplete((record, error) -> {
            if (error != null || record == null) {
                if (error != null) {
                    LOGGER.debug("现代唱片机歌词后台解析失败: song='{}' session={} reason={}", command.songName(),
                            command.sessionId(), error.toString());
                }
                return;
            }
            Minecraft.getInstance().execute(() -> {
                var level = Minecraft.getInstance().level;
                if (level == null || !ModernTurntablePlaybackTracker.isActiveSession(sourcePos,
                        command.sessionId())) {
                    return;
                }
                if (level.getBlockEntity(sourcePos) instanceof ModernTurntableBlockEntity turntable
                        && turntable.isPlaying()) {
                    turntable.setClientLyricRecord(record, command.sessionId());
                }
            });
        });
    }

    /** 现代唱片机会话完整结束；具体资源由 ClientPlaybackSession cancellation token 释放。 */
    static void finishSession(BlockPos sourcePos, String sessionId) {
        ModernTurntablePlaybackTracker.finish(sourcePos, sessionId);
    }

    /** 服务端权威停止；exact session 匹配避免迟到的旧 stop 终止新唱片。 */
    public static void stop(BlockPos sourcePos, String sessionId) {
        if (sourcePos == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> stop(sourcePos, sessionId));
            return;
        }
        ModernTurntablePlaybackTracker.explicitStop(sourcePos, sessionId);
        removeIndexed(sourcePos, sessionId);
    }

    private static void bindSessionResources(BlockPos sourcePos, String sessionId) {
        ModernTurntablePlaybackTracker.onCancel(sourcePos, sessionId,
                () -> ClientMinecartAudioAnchors.forget(sessionId));
        ModernTurntablePlaybackTracker.onCancel(sourcePos, sessionId,
            () -> ModernTurntableVideoClient.forgetSession(sessionId));
        ModernTurntablePlaybackTracker.onCancel(sourcePos, sessionId,
                () -> VideoBillboardPreview.stopIfSession(sessionId));
        ModernTurntablePlaybackTracker.onCancel(sourcePos, sessionId,
                () -> ModernTurntablePlaybackDiagnostics.finish(sessionId));
        ModernTurntablePlaybackTracker.onCancel(sourcePos, sessionId,
                () -> clearLyricRecord(sourcePos, sessionId));
    }

    private static void clearLyricRecord(BlockPos sourcePos, String sessionId) {
        Minecraft minecraft = Minecraft.getInstance();
        Runnable clear = () -> {
            if (minecraft.level != null
                    && minecraft.level.getBlockEntity(sourcePos) instanceof ModernTurntableBlockEntity turntable) {
                turntable.clearClientLyricRecord(sessionId);
            }
        };
        if (minecraft.isSameThread()) {
            clear.run();
        } else {
            minecraft.execute(clear);
        }
    }

    /** 同一 session 自动续播时只退役旧声音资源，不结束逻辑会话。 */
    static void retireStreamForRecovery(ModernTurntableSound sound,
            SyncedStreamRecoveryRegistry.Registration recoveryRegistration) {
        ModernTurntablePlaybackTracker.unregisterSound(sound);
        SyncedStreamRecoveryRegistry.unregister(recoveryRegistration);
    }

    private static BlockPos sourcePos(ClientPlaybackCommand command) {
        return new BlockPos(command.sourceX(), command.sourceY(), command.sourceZ());
    }

    private static void announceIndexed(ClientPlaybackCommand command) {
        PlaybackSessionId sessionId = command.playbackSessionId().orElse(null);
        if (sessionId != null) {
            INDEXED_DEMAND.announce(sourceId(command), sessionId, command);
        }
    }

    static void markIndexedStreamPlaying(BlockPos pos, PlaybackSourceId sourceId, String sessionId) {
        PlaybackSessionId parsed = PlaybackSessionId.parse(sessionId).orElse(null);
        if (parsed == null) {
            return;
        }
        if (sourceId != null && INDEXED_DEMAND.markPlaying(sourceId, parsed)) {
            LOGGER.debug("索引播放流已就绪: source={} pos={} session={}", sourceId, pos, parsed);
            return;
        }
        INDEXED_DEMAND.snapshots().stream()
                .filter(snapshot -> snapshot.playback().sessionId().equals(parsed)
                        && sourcePos(snapshot.playback().payload()).equals(pos))
                .findFirst()
                .ifPresentOrElse(snapshot -> {
                    boolean marked = INDEXED_DEMAND.markPlaying(snapshot.sourceId(), parsed);
                    LOGGER.debug("索引播放流按位置就绪: source={} pos={} session={} marked={}",
                            snapshot.sourceId(), pos, parsed, marked);
                }, () -> LOGGER.debug("索引播放迟到流被拒绝: source={} pos={} session={}", sourceId, pos, parsed));
    }

    private static void removeIndexed(ClientPlaybackCommand command) {
        command.playbackSessionId().ifPresent(sessionId ->
                INDEXED_DEMAND.remove(sourceId(command), sessionId));
    }

    private static void removeIndexed(BlockPos pos, String sessionId) {
        PlaybackSessionId parsed = PlaybackSessionId.parse(sessionId).orElse(null);
        if (parsed == null) {
            return;
        }
        INDEXED_DEMAND.snapshots().stream()
                .filter(snapshot -> snapshot.playback().sessionId().equals(parsed)
                        && sourcePos(snapshot.playback().payload()).equals(pos))
                .forEach(snapshot -> INDEXED_DEMAND.remove(snapshot.sourceId(), parsed));
    }

    private static PlaybackSourceId sourceId(ClientPlaybackCommand command) {
        return PlaybackSync.parsePlaybackSourceId(command.playUrl()).orElseGet(() -> PlaybackSourceId.of(
                UUID.nameUUIDFromBytes(("legacy-block:" + command.sourceX() + ':' + command.sourceY() + ':'
                        + command.sourceZ()).getBytes(StandardCharsets.UTF_8))));
    }

    public static List<IndexedDemandDebug> indexedDemandDebugSnapshots() {
        return INDEXED_DEMAND.snapshots().stream().map(snapshot -> {
            ClientPlaybackCommand command = snapshot.playback().payload();
            BlockPos pos = sourcePos(command);
            return new IndexedDemandDebug(snapshot.sourceId(), snapshot.playback().sessionId(), pos,
                    snapshot.playback().state(), snapshot.playback().endpointIds().size(),
                    INDEXED_PREPARES.containsKey(snapshot.sourceId()), ClientAudioOutputPolicy.volume(pos));
        }).toList();
    }

    public record IndexedDemandDebug(PlaybackSourceId sourceId, PlaybackSessionId sessionId,
            BlockPos sourcePos, AudioPlaybackDemandIndex.State state, int demandCount,
            boolean preparingUrl, float volume) {
    }
}
