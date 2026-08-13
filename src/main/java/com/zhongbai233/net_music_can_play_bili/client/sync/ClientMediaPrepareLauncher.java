package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedMediaPlaybackLauncher;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared asynchronous prepare/launch flow for synchronized client media
 * playback.
 */
public final class ClientMediaPrepareLauncher {
    private static final System.Logger LOGGER = System.getLogger(ClientMediaPrepareLauncher.class.getName());
    private static final ClientMediaPrepareOwnerRegistry SCHEDULED_PREPARES =
            new ClientMediaPrepareOwnerRegistry();
    private static final ClientMediaPrepareOwnerRegistry SCHEDULED_LYRICS =
            new ClientMediaPrepareOwnerRegistry();

    private ClientMediaPrepareLauncher() {
    }

    public static void preparePlaybackAsync(ClientMediaSyncPayload payload, UUID sourceId,
            ClientMediaPreparePolicy policy) {
        if (payload == null || sourceId == null || policy == null) {
            return;
        }
        ClientMediaPrepareOwnerRegistry.Key key = keyFor(payload, sourceId);
        if (key == null) {
            return;
        }
        PendingPrepare pending = new PendingPrepare();
        if (!SCHEDULED_PREPARES.tryRegister(key, pending)) {
            policy.onPrepareDuplicate(payload, sourceId);
            return;
        }
        boolean loadLyrics = policy.shouldLoadLyrics(payload, sourceId);
        long started = System.currentTimeMillis();
        policy.onPrepareStarted(payload, sourceId, loadLyrics);
        CancellableTaskFuture<ClientMediaPreparer.PreparedMedia> prepare = ClientMediaPreparer.prepareAudioOnlyAsync(
                payload.rawUrl(), payload.playUrl(), payload.songName(), policy.allowDolby(payload, sourceId));
        pending.bind(prepare);
        policy.onPrepareScheduled(payload, sourceId);
        prepare.completeOnTimeout(null, Math.max(3L, policy.prepareTimeoutSeconds()), TimeUnit.SECONDS)
                .whenComplete((prepared, error) -> {
                    if (prepared == null || error != null) {
                        prepare.cancelWorker();
                    } else {
                        policy.onPrepareCompleted(payload, sourceId, prepared, System.currentTimeMillis() - started);
                    }
                    SCHEDULED_PREPARES.remove(key, pending);
                    Minecraft client = Minecraft.getInstance();
                    client.execute(() -> finishPrepareOnClient(payload, sourceId, policy, loadLyrics, prepared, error));
                });
    }

    private static void finishPrepareOnClient(ClientMediaSyncPayload payload, UUID sourceId,
            ClientMediaPreparePolicy policy, boolean loadLyrics, ClientMediaPreparer.PreparedMedia prepared,
            Throwable error) {
        ClientMediaPlaybackRegistry.ActivePlayback current = ClientMediaPlaybackRegistry.get(sourceId);
        if (current == null || !payload.playbackSessionId().equals(current.playbackSessionId())) {
            return;
        }
        if (!policy.canHear(sourceId, payload.headphoneRouted())) {
            policy.stop(sourceId);
            policy.onPrepareCancelledCannotHear(payload, sourceId);
            return;
        }
        if (error != null) {
            policy.onPrepareFailed(payload, sourceId, error);
        } else if (prepared == null) {
            policy.onPrepareTimeout(payload, sourceId);
        }

        long startOffsetMillis = policy.startOffsetMillis(payload, current);
        long totalMillis = policy.totalMillis(payload, current);
        SyncedMediaPlaybackLauncher.LaunchResult launch = SyncedMediaPlaybackLauncher.fromPrepared(
                payload.rawUrl(), payload.songName(), prepared, payload.playUrl(), payload.sessionId(),
                startOffsetMillis, totalMillis, null, sourceId);
        ClientMediaPlaybackRegistry.put(sourceId, current.withLyrics(null, "", ""));
        if (loadLyrics) {
            loadLyricsAsync(keyFor(payload, sourceId), payload, sourceId, policy);
        }
        policy.onLaunch(payload, sourceId, startOffsetMillis, launch.playUrl());
        SyncedMediaPlaybackLauncher.play(launch, payload.songName(),
                (url, lyricRecord) -> policy.createSound(sourceId, payload, url, lyricRecord, startOffsetMillis));
    }

    static void removeScheduledForDevice(UUID sourceId) {
        if (sourceId != null) {
            PlaybackSourceId parsedSourceId = PlaybackSourceId.of(sourceId);
            SCHEDULED_PREPARES.cancelSource(parsedSourceId);
            SCHEDULED_LYRICS.cancelSource(parsedSourceId);
        }
    }

    static void onSessionAccepted(UUID sourceId, PlaybackSessionId sessionId) {
        if (sourceId != null && sessionId != null) {
            removeScheduledForDevice(sourceId);
        }
    }

    static void clearScheduled() {
        SCHEDULED_PREPARES.clear();
        SCHEDULED_LYRICS.clear();
    }

    private static ClientMediaPrepareOwnerRegistry.Key keyFor(ClientMediaSyncPayload payload, UUID sourceId) {
        PlaybackSessionId sessionId = payload.playbackSessionId().orElse(null);
        return sessionId != null
                ? new ClientMediaPrepareOwnerRegistry.Key(PlaybackSourceId.of(sourceId), sessionId,
                        payload.headphoneRouted())
                : null;
    }

    private static void loadLyricsAsync(ClientMediaPrepareOwnerRegistry.Key key, ClientMediaSyncPayload payload,
            UUID sourceId,
            ClientMediaPreparePolicy policy) {
        PendingPrepare pending = new PendingPrepare();
        SCHEDULED_LYRICS.replace(key, pending);
        CancellableTaskFuture<com.github.tartaricacid.netmusic.api.lyric.LyricRecord> lyric =
                ClientMediaPreparer.buildLyricAsync(payload.rawUrl(), payload.songName());
        pending.bind(lyric);
        lyric.whenComplete((record, error) -> {
            SCHEDULED_LYRICS.remove(key, pending);
            if (error != null) {
                if (!(error instanceof java.util.concurrent.CancellationException)) {
                    LOGGER.log(System.Logger.Level.DEBUG,
                            "{0} 客户端歌词后台解析失败: source={1} session={2} song=''{3}'' reason={4}",
                            policy.lyricLogLabel(), sourceId, payload.sessionId(), payload.songName(), error.toString());
                }
                return;
            }
            if (record != null) {
                Minecraft.getInstance().execute(() -> ClientMediaPlaybackRegistry.computeIfPresent(sourceId,
                        (ignored, active) -> payload.playbackSessionId().equals(active.playbackSessionId())
                                ? active.withLyrics(record, "", "")
                                : active));
            }
        });
    }

    private static final class PendingPrepare implements ClientMediaPrepareOwnerRegistry.Owner {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<CancellableTaskFuture<?>> task = new AtomicReference<>();

        void bind(CancellableTaskFuture<?> value) {
            if (!task.compareAndSet(null, value)) {
                value.cancel(true);
                return;
            }
            if (cancelled.get()) {
                value.cancel(true);
            }
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            CancellableTaskFuture<?> value = task.get();
            if (value != null) {
                value.cancel(true);
            }
        }
    }
}
