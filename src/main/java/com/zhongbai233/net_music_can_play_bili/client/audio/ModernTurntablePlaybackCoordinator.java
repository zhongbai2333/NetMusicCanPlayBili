package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.github.tartaricacid.netmusic.client.audio.MusicPlayManager;
import com.github.tartaricacid.netmusic.client.audio.NetMusicSound;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.sync.ModernTurntablePlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.media.sync.AudioStartupSync;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 现代唱片机客户端播放命令的唯一编排入口。
 *
 * <p>
 * {@link #play(ClientPlaybackCommand)} 必须在 Minecraft 客户端线程调用。
 * </p>
 */
public final class ModernTurntablePlaybackCoordinator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long AUDIO_PREPARE_TIMEOUT_SECONDS = Math.max(3L,
            Long.getLong("ncpb.bili.audio.prepare_timeout_seconds", 20L));
    private static final AtomicLong COMPAT_PREPARE_SEQUENCE = new AtomicLong();
    private static final ConcurrentHashMap<BlockPos, Long> LATEST_COMPAT_PREPARE = new ConcurrentHashMap<>();

    private ModernTurntablePlaybackCoordinator() {
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
        ClientMediaPreparer.prepareAudioOnlyAsync(command.rawUrl(), command.playUrl(), command.songName(), false)
                .completeOnTimeout(null, AUDIO_PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((prepared, error) -> minecraft.execute(
                        () -> finishCompatiblePrepare(command, sourcePos, generation, prepared, error)));
    }

    private static void finishCompatiblePrepare(ClientPlaybackCommand command, BlockPos sourcePos, long generation,
            ClientMediaPreparer.PreparedMedia prepared, Throwable error) {
        if (!LATEST_COMPAT_PREPARE.remove(sourcePos, generation)) {
            return;
        }
        if (error != null) {
            LOGGER.warn("普通唱片机 B站音频后台准备失败，使用同步消息中的直链: song='{}' reason={}",
                    command.songName(), error.toString());
        }
        if (prepared == null) {
            prepared = new ClientMediaPreparer.PreparedMedia(command.playUrl(), null);
        }
        String playUrl = prepared.playUrl();
        BiliPlaybackDiagnostics.beginPlayback(command.songName(), command.rawUrl(), playUrl);
        LOGGER.debug("B站/NetMusic 普通唱片机兼容播放: song='{}' audioHost={}", command.songName(),
                ClientMediaPreparer.hostOf(playUrl));
        MusicPlayManager.play(playUrl, command.songName(),
                url -> new NetMusicSound(sourcePos, url, command.remainingSeconds(), null));
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
        if (minecraft.options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F) {
            return;
        }

        if (command.minecartAnchor() != null && command.hasSession()) {
            ClientMinecartAudioAnchors.register(command.sessionId(), command.minecartAnchor().entityId(),
                    command.minecartAnchor().entityUuid());
        }
        if (command.hasSession()
                && !ModernTurntablePlaybackTracker.tryStart(sourcePos, command.sessionId(),
                        command.remainingSeconds())) {
            syncVideo(command);
            return;
        }
        if (command.hasSession()) {
            bindSessionResources(sourcePos, command.sessionId());
        }
        syncVideo(command);
        long prepareStartedNanos = System.nanoTime();
        var prepare = ClientMediaPreparer.prepareAudioOnlyAsync(command.rawUrl(), command.playUrl(),
                command.songName(), true)
                .completeOnTimeout(null, AUDIO_PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (command.hasSession() && !ModernTurntablePlaybackTracker.onCancel(sourcePos, command.sessionId(),
                () -> prepare.cancel(false))) {
            prepare.cancel(false);
            return;
        }
        prepare.whenComplete((prepared, error) -> minecraft.execute(
                () -> finishModernPrepare(command, sourcePos, prepareStartedNanos, prepared, error)));
    }

    private static void finishModernPrepare(ClientPlaybackCommand command, BlockPos sourcePos,
            long prepareStartedNanos, ClientMediaPreparer.PreparedMedia prepared, Throwable error) {
        if (command.hasSession()
                && !ModernTurntablePlaybackTracker.isActiveSession(sourcePos, command.sessionId())) {
            return;
        }
        if (error != null) {
            LOGGER.warn("现代唱片机 B站音频后台准备失败，使用同步消息中的直链: pos={} session={} song='{}' reason={}",
                    sourcePos, command.sessionId(), command.songName(), error.toString());
        }
        if (prepared == null) {
            prepared = new ClientMediaPreparer.PreparedMedia(command.playUrl(), null);
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
        if (command.hasSession() && !launch.requestToken().isBlank()
                && !ModernTurntablePlaybackTracker.onCancel(sourcePos, command.sessionId(),
                        () -> HttpAudioStreamHandler.cancelRequest(launch.requestToken()))) {
            HttpAudioStreamHandler.cancelRequest(launch.requestToken());
            return;
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
                        command.rawUrl(), command.songName(), command.durationMillis()));
        if (!submitted) {
            ModernTurntablePlaybackTracker.finish(sourcePos, command.sessionId());
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

    private static void syncVideo(ClientPlaybackCommand command) {
        ModernTurntableVideoClient.syncFromPlayback(command.rawUrl(), sourcePos(command), command.syncMetadata());
    }

    private static void loadLyricsAsync(ClientPlaybackCommand command) {
        ClientMediaPreparer.buildLyricAsync(command.rawUrl(), command.songName()).whenComplete((record, error) -> {
            if (error != null || record == null) {
                if (error != null) {
                    LOGGER.debug("现代唱片机歌词后台解析失败: song='{}' session={} reason={}", command.songName(),
                            command.sessionId(), error.toString());
                }
                return;
            }
            Minecraft.getInstance().execute(() -> {
                var level = Minecraft.getInstance().level;
                BlockPos sourcePos = sourcePos(command);
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

    private static void bindSessionResources(BlockPos sourcePos, String sessionId) {
        ModernTurntablePlaybackTracker.onCancel(sourcePos, sessionId,
                () -> ClientMinecartAudioAnchors.forget(sessionId));
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
}