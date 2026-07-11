package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedMediaPlaybackLauncher;
import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Shared asynchronous prepare/launch flow for synchronized client media
 * playback.
 */
public final class ClientMediaPrepareLauncher {
    private ClientMediaPrepareLauncher() {
    }

    public static void preparePlaybackAsync(ClientMediaSyncPayload payload, UUID sourceId,
            ClientMediaPreparePolicy policy) {
        if (payload == null || sourceId == null || policy == null) {
            return;
        }
        if (!ClientMediaPrepareTracker.markScheduled(sourceId, payload.sessionId(), payload.headphoneRouted())) {
            policy.onPrepareDuplicate(payload, sourceId);
            return;
        }
        policy.onPrepareScheduled(payload, sourceId);
        boolean loadLyrics = policy.shouldLoadLyrics(payload, sourceId);
        CompletableFuture.supplyAsync(() -> {
            long started = System.currentTimeMillis();
            policy.onPrepareStarted(payload, sourceId, loadLyrics);
            ClientMediaPreparer.PreparedMedia prepared = ClientMediaPreparer.prepareAudioOnly(payload.rawUrl(),
                    payload.playUrl(), payload.songName(), policy.allowDolby(payload, sourceId));
            policy.onPrepareCompleted(payload, sourceId, prepared, System.currentTimeMillis() - started);
            return prepared;
        }).completeOnTimeout(null, Math.max(3L, policy.prepareTimeoutSeconds()), TimeUnit.SECONDS)
                .whenComplete((prepared, error) -> {
                    ClientMediaPrepareTracker.complete(sourceId, payload.sessionId(), payload.headphoneRouted());
                    Minecraft client = Minecraft.getInstance();
                    client.execute(() -> finishPrepareOnClient(payload, sourceId, policy, loadLyrics, prepared, error));
                });
    }

    private static void finishPrepareOnClient(ClientMediaSyncPayload payload, UUID sourceId,
            ClientMediaPreparePolicy policy, boolean loadLyrics, ClientMediaPreparer.PreparedMedia prepared,
            Throwable error) {
        ClientMediaPlaybackRegistry.ActivePlayback current = ClientMediaPlaybackRegistry.get(sourceId);
        if (current == null || !payload.sessionId().equals(current.sessionId())) {
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
            policy.loadLyricsAsync(sourceId, payload.sessionId(), payload.rawUrl(), payload.songName());
        }
        policy.onLaunch(payload, sourceId, startOffsetMillis, launch.playUrl());
        SyncedMediaPlaybackLauncher.play(launch, payload.songName(),
                (url, lyricRecord) -> policy.createSound(sourceId, payload, url, lyricRecord, startOffsetMillis));
    }
}