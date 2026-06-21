package com.zhongbai233.net_music_can_play_bili.client;

import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedMediaPlaybackLauncher;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.MediaTimelineClock;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackTimelinePacket;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class MP4ClientPlayback {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, ActivePlayback> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<UUID, MP4MovingSound> ACTIVE_SOUNDS = new ConcurrentHashMap<>();
    private static final Set<String> STREAM_RETRY_SESSIONS = ConcurrentHashMap.newKeySet();
    private static final Set<String> SOUND_PREPARES = ConcurrentHashMap.newKeySet();
    private static final Set<String> STARTED_SOUND_SESSIONS = ConcurrentHashMap.newKeySet();
    private static final long STREAM_RETRY_DELAY_MILLIS = 750L;
    private static final long PREPARE_TIMEOUT_SECONDS = Math.max(3L,
            Long.getLong("bili.mp4.client_prepare_timeout_seconds", 12L));

    private MP4ClientPlayback() {
    }

    public static void handleSync(MP4PlaybackSyncPacket payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        UUID sourceId = payload.sourceId() != null ? payload.sourceId() : payload.ownerId();
        if (!payload.playing()) {
            stop(sourceId);
            return;
        }
        if (!canHear(sourceId, payload.headphoneRouted())) {
            stop(sourceId);
            LOGGER.trace("MP4 客户端忽略非当前耳机绑定播放: source={} session={} headphoneRouted={} equipped={}",
                    sourceId, payload.sessionId(), payload.headphoneRouted(), HeadphoneClientState.equipped());
            return;
        }
        if (payload.playUrl().isBlank() || payload.sessionId().isBlank()) {
            return;
        }
        SourceLocation sourceLocation = SourceLocation.from(payload);
        ActivePlayback previous = ACTIVE.get(sourceId);
        if (!MP4HandheldVideoClient.isDeviceInHotbar(sourceId)) {
            MP4HandheldVideoClient.stop(sourceId, "等待快捷栏");
        }
        if (previous != null && payload.sessionId().equals(previous.sessionId())) {
            ActivePlayback updated = previous.withServerElapsed(Math.max(0L, payload.elapsedMillis()),
                    Math.max(0L, payload.durationSeconds()) * 1000L)
                    .withSourceLocation(sourceLocation)
                    .withHeadphoneRouted(payload.headphoneRouted());
            ACTIVE.put(sourceId, updated);
            updateVolume(sourceId, payload.volumePerMille() / 1000.0F);
            if (shouldRebuildSound(sourceId, payload)) {
                LOGGER.debug("MP4 客户端重建声音实例: source={} session={} headphoneRouted={} elapsed={}ms",
                        sourceId, payload.sessionId(), payload.headphoneRouted(), payload.elapsedMillis());
                preparePlaybackAsync(payload, sourceId);
            }
            return;
        }

        ACTIVE.put(sourceId, new ActivePlayback(payload.sessionId(), payload.queueIndex(), payload.songName(),
                payload.rawUrl(),
                MediaTimelineClock.start(payload.sessionId(), Math.max(0L, payload.elapsedMillis()),
                        Math.max(0L, payload.durationSeconds()) * 1000L),
                null, "", "", payload.volumePerMille() / 1000.0F, sourceLocation, payload.headphoneRouted()));
        preparePlaybackAsync(payload, sourceId);
    }

    public static void handleTimeline(MP4PlaybackTimelinePacket payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || payload.sourceId() == null) {
            return;
        }
        if (!canHear(payload.sourceId(), payload.headphoneRouted())) {
            stop(payload.sourceId());
            return;
        }
        ActivePlayback previous = ACTIVE.get(payload.sourceId());
        if (previous == null || payload.sessionId() == null || !payload.sessionId().equals(previous.sessionId())) {
            return;
        }
        ACTIVE.put(payload.sourceId(), previous.withServerElapsed(Math.max(0L, payload.elapsedMillis()),
                previous.durationMillis()).withHeadphoneRouted(payload.headphoneRouted()));
        updateVolume(payload.sourceId(), payload.volumePerMille() / 1000.0F);
    }

    private static void preparePlaybackAsync(MP4PlaybackSyncPacket payload, UUID sourceId) {
        String prepareKey = soundPrepareKey(sourceId, payload.sessionId(), payload.headphoneRouted());
        if (!SOUND_PREPARES.add(prepareKey)) {
            return;
        }
        UUID localPlayerId = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getUUID() : null;
        boolean loadLyrics = localPlayerId != null && localPlayerId.equals(payload.ownerId())
                && GeneralConfig.ENABLE_PLAYER_LYRICS.get();
        CompletableFuture.supplyAsync(() -> {
            long started = System.currentTimeMillis();
            LOGGER.trace("MP4 客户端准备播放开始: owner={} source={} session={} song='{}' host={} lyrics={}",
                    payload.ownerId(), sourceId, payload.sessionId(), payload.songName(),
                    ClientMediaPreparer.hostOf(payload.playUrl()), loadLyrics);
            ClientMediaPreparer.PreparedMedia prepared = ClientMediaPreparer.prepareAudioOnly(payload.rawUrl(),
                    payload.playUrl(), payload.songName(), true);
            LOGGER.debug("MP4 客户端准备播放完成: owner={} source={} session={} cost={}ms host={}",
                    payload.ownerId(), sourceId, payload.sessionId(), System.currentTimeMillis() - started,
                    prepared != null ? ClientMediaPreparer.hostOf(prepared.playUrl()) : "unknown");
            return prepared;
        }).completeOnTimeout(null, PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((prepared, error) -> {
            SOUND_PREPARES.remove(prepareKey);
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                ActivePlayback current = ACTIVE.get(sourceId);
                if (current == null || !payload.sessionId().equals(current.sessionId())) {
                    return;
                }
                if (!canHear(sourceId, payload.headphoneRouted())) {
                    stop(sourceId);
                    LOGGER.trace("MP4 客户端准备完成后取消非当前耳机绑定播放: source={} session={} headphoneRouted={} equipped={}",
                            sourceId, payload.sessionId(), payload.headphoneRouted(), HeadphoneClientState.equipped());
                    return;
                }
                if (error != null) {
                    LOGGER.warn("MP4 客户端异步准备播放失败，使用服务端直链继续: owner={} session={} song='{}' reason={}",
                            payload.ownerId(), payload.sessionId(), payload.songName(), error.toString());
                } else if (prepared == null) {
                    LOGGER.warn(
                            "MP4 客户端准备播放超时或无结果，使用服务端直链继续: owner={} source={} session={} song='{}' timeout={}s host={}",
                            payload.ownerId(), sourceId, payload.sessionId(), payload.songName(),
                            PREPARE_TIMEOUT_SECONDS, ClientMediaPreparer.hostOf(payload.playUrl()));
                }
                long startOffsetMillis = isWhitelistPreviewSession(payload.sessionId())
                        ? Math.max(0L, payload.elapsedMillis())
                        : current.elapsedMillis();
                long totalMillis = current.durationMillis() > 0L
                        ? current.durationMillis()
                        : Math.max(0L, payload.durationSeconds()) * 1000L;
                SyncedMediaPlaybackLauncher.LaunchResult launch = SyncedMediaPlaybackLauncher.fromPrepared(
                        payload.rawUrl(), payload.songName(), prepared, payload.playUrl(), payload.sessionId(),
                        startOffsetMillis, totalMillis, null, sourceId);
                ACTIVE.put(sourceId, current.withLyrics(null, "", ""));
                if (loadLyrics) {
                    loadLyricsAsync(sourceId, payload.sessionId(), payload.rawUrl(), payload.songName());
                }
                LOGGER.trace("MP4 客户端开始播放: owner={} source={} type={} song='{}' session={} offset={}ms host={}",
                        payload.ownerId(), sourceId, payload.sourceType(), payload.songName(), payload.sessionId(),
                        startOffsetMillis, ClientMediaPreparer.hostOf(launch.playUrl()));
                SyncedMediaPlaybackLauncher.play(launch, payload.songName(),
                        (url, lyricRecord) -> new MP4MovingSound(sourceId, url, payload.durationSeconds(), lyricRecord,
                                payload.sessionId(), startOffsetMillis, payload.volumePerMille() / 1000.0F,
                                payload.headphoneRouted()));
            });
        });
    }

    private static boolean shouldRebuildSound(UUID sourceId, MP4PlaybackSyncPacket payload) {
        MP4MovingSound sound = ACTIVE_SOUNDS.get(sourceId);
        if (sound == null || !payload.sessionId().equals(sound.sessionId()) || sound.stopped()) {
            return true;
        }
        if (sound.headphoneRouted() != payload.headphoneRouted()) {
            sound.discardWithoutFinishing();
            ACTIVE_SOUNDS.remove(sourceId, sound);
            return true;
        }
        return false;
    }

    private static String soundPrepareKey(UUID sourceId, String sessionId, boolean headphoneRouted) {
        return String.valueOf(sourceId) + ':' + sessionId + ':' + headphoneRouted;
    }

    private static void loadLyricsAsync(UUID sourceId, String sessionId, String rawUrl, String songName) {
        ClientMediaPreparer.buildLyricAsync(rawUrl, songName).whenComplete((record, error) -> {
            if (error != null) {
                LOGGER.debug("MP4 客户端歌词后台解析失败: source={} session={} song='{}' reason={}", sourceId,
                        sessionId, songName, error.toString());
                return;
            }
            if (record == null) {
                return;
            }
            Minecraft.getInstance().execute(() -> ACTIVE.computeIfPresent(sourceId,
                    (ignored, active) -> sessionId.equals(active.sessionId()) ? active.withLyrics(record, "", "")
                            : active));
        });
    }

    public static boolean isCurrent(UUID ownerId, String sessionId) {
        if (ownerId == null || sessionId == null || sessionId.isBlank()) {
            return true;
        }
        ActivePlayback active = ACTIVE.get(ownerId);
        return active != null && sessionId.equals(active.sessionId());
    }

    public static boolean hasLocalPlayback(UUID ownerId) {
        return ownerId != null && ACTIVE.containsKey(ownerId);
    }

    public static boolean canHear(UUID sourceId, boolean headphoneRouted) {
        if (!HeadphoneClientState.equipped()) {
            return !headphoneRouted;
        }
        return headphoneRouted && HeadphoneClientState.handlesMp4(sourceId);
    }

    public static Vec3 sourcePosition(UUID sourceId) {
        ActivePlayback active = sourceId != null ? ACTIVE.get(sourceId) : null;
        if (active == null) {
            return null;
        }
        if (active.headphoneRouted()) {
            return localHeadPosition();
        }
        return active.sourceLocation().position();
    }

    public static boolean followsLocalPlayerFront(UUID sourceId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        ActivePlayback active = sourceId != null ? ACTIVE.get(sourceId) : null;
        if (active == null) {
            return false;
        }
        return active.headphoneRouted() || isLocalPlayerSource(sourceId);
    }

    public static boolean isLocalPlayerSource(UUID sourceId) {
        Minecraft minecraft = Minecraft.getInstance();
        ActivePlayback active = sourceId != null ? ACTIVE.get(sourceId) : null;
        return minecraft.player != null && active != null
                && active.sourceLocation().sourceType() == MP4PlaybackSyncPacket.SOURCE_PLAYER
                && minecraft.player.getId() == active.sourceLocation().sourceEntityId();
    }

    static Vec3 localHeadPosition() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return Vec3.ZERO;
        }
        return minecraft.player.position().add(0.0D, minecraft.player.getEyeHeight(), 0.0D);
    }

    public static void registerSound(UUID ownerId, String sessionId, MP4MovingSound sound) {
        if (isCurrent(ownerId, sessionId) && sound != null) {
            ACTIVE_SOUNDS.put(ownerId, sound);
        }
    }

    public static void markSoundStarted(UUID ownerId, String sessionId, long startOffsetMillis, long totalMillis) {
        if (ownerId != null && sessionId != null && !sessionId.isBlank() && isCurrent(ownerId, sessionId)) {
            if (STARTED_SOUND_SESSIONS.add(soundSessionKey(ownerId, sessionId))) {
                ACTIVE.computeIfPresent(ownerId, (ignored, active) -> active.reanchoredAtSoundStart(
                        Math.max(0L, startOffsetMillis), Math.max(0L, totalMillis)));
            }
        }
    }

    public static boolean hasStartedSound(UUID ownerId, String sessionId) {
        return ownerId != null && sessionId != null
                && STARTED_SOUND_SESSIONS.contains(soundSessionKey(ownerId, sessionId));
    }

    public static void updateVolume(UUID ownerId, float volume) {
        if (ownerId == null) {
            return;
        }
        float clamped = Math.max(0.0F, Math.min(1.0F, volume));
        ACTIVE.computeIfPresent(ownerId, (ignored, active) -> active.withVolume(clamped));
        DolbyAudioRegistry.setOwnerVolume(ownerId, perceivedGain(clamped));
        MP4MovingSound sound = ACTIVE_SOUNDS.get(ownerId);
        if (sound != null) {
            sound.setMp4Volume(clamped);
        }
    }

    static float perceivedGain(float sliderValue) {
        float clamped = Math.max(0.0F, Math.min(1.0F, sliderValue));
        return clamped * clamped;
    }

    public static void updateLyric(UUID ownerId, String sessionId, LyricRecord record, int lyricTick) {
        if (ownerId == null || record == null || !isCurrent(ownerId, sessionId)) {
            return;
        }
        String current = currentLineAt(record.getLyrics(), lyricTick);
        String translated = currentLineAt(record.getTransLyrics(), lyricTick);
        ACTIVE.computeIfPresent(ownerId, (ignored, active) -> active.withLyrics(record, current, translated));
    }

    public static long localElapsedMillis() {
        ActivePlayback active = localPlayback();
        return active != null ? active.elapsedMillis() : -1L;
    }

    public static long localElapsedMillis(UUID deviceId) {
        ActivePlayback active = deviceId != null ? ACTIVE.get(deviceId) : localPlayback();
        return active != null ? active.elapsedMillis() : -1L;
    }

    public static long localDurationMillis() {
        ActivePlayback active = localPlayback();
        return active != null ? active.durationMillis() : 0L;
    }

    public static long localDurationMillis(UUID deviceId) {
        ActivePlayback active = deviceId != null ? ACTIVE.get(deviceId) : localPlayback();
        return active != null ? active.durationMillis() : 0L;
    }

    public static long localVisualMillis() {
        ActivePlayback active = localPlayback();
        return active != null ? active.visualMillis() : -1L;
    }

    public static long localPacingMillis() {
        ActivePlayback active = localPlayback();
        return active != null ? active.pacingMillis() : -1L;
    }

    public static float localProgress() {
        ActivePlayback active = localPlayback();
        if (active == null || active.durationMillis() <= 0L) {
            return -1.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, active.elapsedMillis() / (float) active.durationMillis()));
    }

    public static String localSongName() {
        ActivePlayback active = localPlayback();
        return active != null ? active.songName() : "";
    }

    public static String localSongName(UUID deviceId) {
        ActivePlayback active = deviceId != null ? ACTIVE.get(deviceId) : localPlayback();
        return active != null ? active.songName() : "";
    }

    public static HandheldMediaPlayback localVideoPlayback() {
        ActivePlayback active = localPlayback();
        if (active == null) {
            return HandheldMediaPlayback.EMPTY;
        }
        return new HandheldMediaPlayback(active.sessionId(), active.rawUrl(), active.songName(),
                active.timelineSnapshot(),
                MP4FocusState.subtitleAiEnabled());
    }

    public static HandheldMediaPlayback localVideoPlayback(UUID deviceId) {
        ActivePlayback active = deviceId != null ? ACTIVE.get(deviceId) : localPlayback();
        if (active == null) {
            return HandheldMediaPlayback.EMPTY;
        }
        MP4Item.State state = MP4Item.State.DEFAULT;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null && deviceId != null) {
            state = MP4Client.stateForHeldRender(MP4Item.findByDeviceId(minecraft.player, deviceId));
        }
        return new HandheldMediaPlayback(active.sessionId(), active.rawUrl(), active.songName(),
                active.timelineSnapshot(),
                state.subtitleAiEnabled());
    }

    public static String localLyricLine() {
        ActivePlayback active = localPlayback();
        return active != null ? active.lyricLineAtCurrentTime(false) : "";
    }

    public static String localLyricLine(UUID deviceId) {
        ActivePlayback active = deviceId != null ? ACTIVE.get(deviceId) : localPlayback();
        return active != null ? active.lyricLineAtCurrentTime(false) : "";
    }

    public static String localTranslatedLyricLine() {
        ActivePlayback active = localPlayback();
        return active != null ? active.lyricLineAtCurrentTime(true) : "";
    }

    public static String localTranslatedLyricLine(UUID deviceId) {
        ActivePlayback active = deviceId != null ? ACTIVE.get(deviceId) : localPlayback();
        return active != null ? active.lyricLineAtCurrentTime(true) : "";
    }

    public static void syncFocusedUiProgress() {
        float progress = localProgress();
        if (progress >= 0.0F) {
            MP4FocusState.setMediaProgressFromPlayback(progress);
        }
    }

    public static void onSoundCompleted(UUID ownerId, String sessionId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !isCurrent(ownerId, sessionId)) {
            finish(ownerId, sessionId);
            return;
        }
        ActivePlayback active = ACTIVE.get(ownerId);
        ItemStack stack = MP4Item.findByDeviceId(minecraft.player, ownerId);
        if (active == null || !(stack.getItem() instanceof MP4Item)
                || active.sourceLocation().sourceType() != MP4PlaybackSyncPacket.SOURCE_PLAYER) {
            finish(ownerId, sessionId);
            return;
        }
        int queueSize = MP4FocusState.queueSize();
        if (queueSize <= 0) {
            MP4FocusState.setPlaying(false);
            MP4Client.updateFocusedLocalState();
            sendControl(com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket.Action.STOP, 0L);
            return;
        }
        if (MP4FocusState.repeatMode() == 1) {
            MP4FocusState.setMediaProgress(0.0F);
            MP4Client.updateFocusedLocalState();
            sendControl(com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket.Action.RESTART, 0L);
            return;
        }
        if (MP4FocusState.selectedQueueIndex() < queueSize - 1) {
            MP4FocusState.nextTrack();
            MP4Client.updateFocusedLocalState();
            sendControl(com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket.Action.RESTART, 0L);
        } else if (MP4FocusState.repeatMode() == 2) {
            MP4FocusState.selectQueueIndexForPlayback(0);
            MP4Client.updateFocusedLocalState();
            sendControl(com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket.Action.RESTART, 0L);
        } else {
            MP4FocusState.setPlaying(false);
            MP4FocusState.setMediaProgress(0.0F);
            MP4Client.updateFocusedLocalState();
            sendControl(com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket.Action.STOP, 0L);
        }
    }

    public static void finish(UUID ownerId, String sessionId) {
        if (ownerId != null && sessionId != null && !sessionId.isBlank()) {
            ACTIVE.computeIfPresent(ownerId, (ignored, active) -> active.sessionId().equals(sessionId) ? null : active);
            ACTIVE_SOUNDS.computeIfPresent(ownerId,
                    (ignored, sound) -> sessionId.equals(sound.sessionId()) ? null : sound);
            STARTED_SOUND_SESSIONS.remove(soundSessionKey(ownerId, sessionId));
        }
    }

    public static void stop(UUID ownerId) {
        if (ownerId != null) {
            ACTIVE.remove(ownerId);
            ACTIVE_SOUNDS.remove(ownerId);
            STARTED_SOUND_SESSIONS.removeIf(key -> key.startsWith(ownerId.toString() + ":"));
            STREAM_RETRY_SESSIONS.removeIf(key -> key.startsWith(ownerId.toString() + ":"));
            SOUND_PREPARES.removeIf(key -> key.startsWith(ownerId.toString() + ":"));
            MP4HandheldVideoClient.stop(ownerId, "播放已停止");
        }
    }

    public static void clearAll() {
        ACTIVE.clear();
        ACTIVE_SOUNDS.clear();
        STARTED_SOUND_SESSIONS.clear();
        STREAM_RETRY_SESSIONS.clear();
        SOUND_PREPARES.clear();
        MP4HandheldVideoClient.clearAll();
    }

    public static boolean retryAfterStreamFailure(UUID ownerId, String sessionId, Throwable error) {
        if (ownerId == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        ActivePlayback active = ACTIVE.get(ownerId);
        if (active == null || !sessionId.equals(active.sessionId())) {
            return false;
        }
        String retryKey = retryKey(ownerId, sessionId);
        if (!STREAM_RETRY_SESSIONS.add(retryKey)) {
            return false;
        }

        int queueIndex = active.queueIndex();
        int volumePerMille = Math.round(active.volume() * 1000.0F);
        long targetMillis = Math.max(0L, active.elapsedMillis());
        LOGGER.warn("MP4 音频流初始化失败，将自动刷新直链并重试一次: owner={} session={} song='{}' at={}ms reason={}",
                ownerId, sessionId, active.songName(), targetMillis,
                error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage());

        CompletableFuture.delayedExecutor(STREAM_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS).execute(() -> {
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                ActivePlayback current = ACTIVE.get(ownerId);
                if (client.player == null || client.getConnection() == null || current == null
                        || !sessionId.equals(current.sessionId())) {
                    return;
                }
                ItemStack stack = MP4Item.findByDeviceId(client.player, ownerId);
                if (!(stack.getItem() instanceof MP4Item)) {
                    return;
                }
                long retryTargetMillis = current.elapsedMillis();
                client.getConnection()
                        .send(new com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket(
                                com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket.Action.SEEK,
                                queueIndex,
                                volumePerMille,
                                retryTargetMillis,
                                ownerId));
            });
        });
        return true;
    }

    public static boolean hasPendingStreamRetry(UUID ownerId, String sessionId) {
        return ownerId != null && sessionId != null && STREAM_RETRY_SESSIONS.contains(retryKey(ownerId, sessionId));
    }

    private static String retryKey(UUID ownerId, String sessionId) {
        return ownerId + ":" + sessionId;
    }

    private static String soundSessionKey(UUID ownerId, String sessionId) {
        return ownerId + ":" + sessionId;
    }

    private static boolean isWhitelistPreviewSession(String sessionId) {
        return sessionId != null && sessionId.contains("-whitelist-preview-");
    }

    static long elapsedMillis(UUID sourceId, String sessionId, long fallbackMillis) {
        ActivePlayback active = sourceId != null ? ACTIVE.get(sourceId) : null;
        if (active == null || sessionId == null || !sessionId.equals(active.sessionId())) {
            return Math.max(0L, fallbackMillis);
        }
        return active.elapsedMillis();
    }

    public static MediaTimelineClock.TimelineSnapshot timelineSnapshot(UUID sourceId) {
        ActivePlayback active = sourceId != null ? ACTIVE.get(sourceId) : null;
        return active != null ? active.timelineSnapshot() : MediaTimelineClock.TimelineSnapshot.EMPTY;
    }

    public static MP4Item.State overlayPlaybackState(UUID deviceId, MP4Item.State baseState) {
        if (baseState == null) {
            baseState = MP4Item.State.DEFAULT;
        }
        ActivePlayback active = deviceId != null ? ACTIVE.get(deviceId) : null;
        if (active == null) {
            return baseState;
        }
        int progressPerMille = baseState.progressPerMille();
        long durationMillis = active.durationMillis();
        if (durationMillis > 0L) {
            progressPerMille = (int) Math.round(Math.max(0L, Math.min(durationMillis,
                    active.elapsedMillis())) * 1000.0D / durationMillis);
        }
        return new MP4Item.State(true, baseState.shuffle(), baseState.videoEnabled(), baseState.landscape(),
                baseState.qualityIndex(), active.queueIndex(), baseState.queueScrollOffset(),
                Math.round(active.volume() * 1000.0F), baseState.repeatMode(), baseState.playlistOpen(),
                baseState.lyricsEnabled(), baseState.subtitleMode(), baseState.subtitleAiEnabled(),
                Math.max(0, Math.min(1000, progressPerMille)), baseState.rotationHintShown());
    }

    private static ActivePlayback localPlayback() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return null;
        }
        ItemStack stack = MP4FocusState.active()
                ? minecraft.player.getItemInHand(MP4FocusState.hand())
                : MP4Item.findAnyInInventory(minecraft.player);
        UUID deviceId = MP4Item.readDeviceId(stack);
        ActivePlayback byDevice = deviceId != null ? ACTIVE.get(deviceId) : null;
        if (byDevice != null) {
            return byDevice;
        }
        return ACTIVE.get(minecraft.player.getUUID());
    }

    private static void sendControl(
            com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket.Action action,
            long targetMillis) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        ItemStack stack = minecraft.player != null
                ? (MP4FocusState.active()
                        ? minecraft.player.getItemInHand(MP4FocusState.hand())
                        : MP4Item.findAnyInInventory(minecraft.player))
                : ItemStack.EMPTY;
        if (stack.isEmpty()) {
            return;
        }
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId == null) {
            minecraft.getConnection().send(new com.zhongbai233.net_music_can_play_bili.network.MP4EnsureDeviceIdPacket(
                    MP4FocusState.active() ? MP4FocusState.hand() : net.minecraft.world.InteractionHand.MAIN_HAND));
            return;
        }
        minecraft.getConnection().send(new com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket(
                action, MP4FocusState.selectedQueueIndex(), Math.round(MP4FocusState.volume() * 1000.0F),
                Math.max(0L, targetMillis), deviceId));
    }

    private static String currentLineAt(it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap<String> lyrics, int tick) {
        if (lyrics == null || lyrics.isEmpty()) {
            return "";
        }
        int key = lyrics.firstIntKey();
        for (int candidate : lyrics.keySet().toIntArray()) {
            if (candidate > tick) {
                break;
            }
            key = candidate;
        }
        String line = lyrics.get(key);
        return line != null ? line : "";
    }

    private record ActivePlayback(String sessionId, int queueIndex, String songName, String rawUrl,
            MediaTimelineClock timeline,
            LyricRecord lyricRecord, String currentLyric,
            String translatedLyric, float volume, SourceLocation sourceLocation, boolean headphoneRouted) {
        long elapsedMillis() {
            return timeline.mediaMillis();
        }

        long visualMillis() {
            return timeline.visualMillis();
        }

        long pacingMillis() {
            return timeline.pacingMillis();
        }

        long durationMillis() {
            return timeline.totalMillis();
        }

        MediaTimelineClock.TimelineSnapshot timelineSnapshot() {
            return timeline.snapshot();
        }

        ActivePlayback withLyrics(LyricRecord record, String current, String translated) {
            return new ActivePlayback(sessionId, queueIndex, songName, rawUrl, timeline, record,
                    current != null ? current : "",
                    translated != null ? translated : "", volume, sourceLocation, headphoneRouted);
        }

        String lyricLineAtCurrentTime(boolean translated) {
            if (lyricRecord == null) {
                return translated ? translatedLyric : currentLyric;
            }
            long mediaMillis = timeline.mediaMillis();
            if (mediaMillis < 0L) {
                return "";
            }
            int lyricTick = (int) Math.min(Integer.MAX_VALUE, mediaMillis / 50L);
            return currentLineAt(translated ? lyricRecord.getTransLyrics() : lyricRecord.getLyrics(), lyricTick);
        }

        ActivePlayback withVolume(float newVolume) {
            return new ActivePlayback(sessionId, queueIndex, songName, rawUrl, timeline, lyricRecord, currentLyric,
                    translatedLyric, newVolume, sourceLocation, headphoneRouted);
        }

        ActivePlayback withServerElapsed(long serverElapsedMillis, long serverDurationMillis) {
            timeline.observeServer(serverElapsedMillis,
                    serverDurationMillis > 0L ? serverDurationMillis : timeline.totalMillis());
            return this;
        }

        ActivePlayback reanchoredAtSoundStart(long startOffsetMillis, long totalMillis) {
            timeline.reanchor(startOffsetMillis, totalMillis > 0L ? totalMillis : timeline.totalMillis());
            return this;
        }

        ActivePlayback withSourceLocation(SourceLocation newSourceLocation) {
            return new ActivePlayback(sessionId, queueIndex, songName, rawUrl, timeline, lyricRecord, currentLyric,
                    translatedLyric, volume, newSourceLocation, headphoneRouted);
        }

        ActivePlayback withHeadphoneRouted(boolean routed) {
            return new ActivePlayback(sessionId, queueIndex, songName, rawUrl, timeline, lyricRecord, currentLyric,
                    translatedLyric, volume, sourceLocation, routed);
        }
    }

    private record SourceLocation(int sourceType, int sourceEntityId, double x, double y, double z) {
        static SourceLocation from(MP4PlaybackSyncPacket payload) {
            return new SourceLocation(payload.sourceType(), payload.sourceEntityId(), payload.sourceX(),
                    payload.sourceY(), payload.sourceZ());
        }

        Vec3 position() {
            Minecraft minecraft = Minecraft.getInstance();
            if (sourceType == MP4PlaybackSyncPacket.SOURCE_PLAYER && minecraft.level != null) {
                Entity entity = minecraft.level.getEntity(sourceEntityId);
                if (entity != null) {
                    return entity.position().add(0.0D, 1.2D, 0.0D);
                }
            }
            if (sourceType == MP4PlaybackSyncPacket.SOURCE_ITEM && minecraft.level != null) {
                Entity entity = minecraft.level.getEntity(sourceEntityId);
                if (entity != null) {
                    return entity.position().add(0.0D, 0.25D, 0.0D);
                }
            }
            if (sourceType == MP4PlaybackSyncPacket.SOURCE_CONTAINER_ENTITY && minecraft.level != null) {
                Entity entity = minecraft.level.getEntity(sourceEntityId);
                if (entity != null) {
                    return entity.position().add(0.0D, 0.5D, 0.0D);
                }
            }
            if (sourceType == MP4PlaybackSyncPacket.SOURCE_BLOCK) {
                return new Vec3(x, y, z);
            }
            return new Vec3(x, y, z);
        }

    }
}