package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry.ActivePlayback;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import net.minecraft.client.Minecraft;

import java.util.Optional;
import java.util.UUID;

/** Shared client-side synchronized media packet handler. */
public final class ClientMediaSyncHandler {
    private ClientMediaSyncHandler() {
    }

    public static void handleSync(ClientMediaSyncPayload payload, ClientMediaSyncPolicy policy) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || payload == null || policy == null) {
            return;
        }
        UUID sourceId = payload.sourceId() != null ? payload.sourceId() : payload.ownerId();
        policy.onSyncReceived(payload, sourceId);
        if (!payload.playing()) {
            policy.stop(sourceId);
            return;
        }
        if (!policy.canHear(sourceId, payload.headphoneRouted())) {
            policy.stop(sourceId);
            policy.onIgnoredCannotHear(payload, sourceId);
            return;
        }
        Optional<PlaybackSessionId> playbackSessionId = payload.playbackSessionId();
        if (payload.playUrl() == null || payload.playUrl().isBlank() || playbackSessionId.isEmpty()) {
            return;
        }

        ClientMediaPlaybackRegistry.SourceLocation sourceLocation = ClientMediaPlaybackRegistry.SourceLocation
                .from(payload);
        ActivePlayback previous = ClientMediaPlaybackRegistry.get(sourceId);
        policy.beforeRegisterPlayback(payload, sourceId);
        if (previous != null && playbackSessionId.equals(previous.playbackSessionId())) {
            PlaybackSessionId retainedSessionId = playbackSessionId.orElseThrow();
            ActivePlayback updated = previous.withServerElapsed(Math.max(0L, payload.elapsedMillis()),
                    Math.max(0L, payload.durationSeconds()) * 1000L)
                    .withSourceLocation(sourceLocation)
                    .withHeadphoneRouted(payload.headphoneRouted());
            ClientMediaPlaybackRegistry.put(sourceId, updated);
            policy.updateVolume(sourceId, payload.volumePerMille() / 1000.0F);
            ClientMediaRetryHandler.onSessionRefreshed(sourceId, retainedSessionId);
            if (policy.shouldRebuildSound(sourceId, payload)) {
                policy.onRebuildSound(payload, sourceId);
                schedulePlayback(payload, sourceId, policy);
            }
            return;
        }

        PlaybackSessionId acceptedSessionId = playbackSessionId.orElseThrow();
        ClientMediaPlaybackRegistry.put(sourceId, ClientMediaPlaybackRegistry.createFromSync(payload));
        ClientMediaRetryHandler.onSessionAccepted(sourceId, acceptedSessionId);
        ClientMediaPrepareLauncher.onSessionAccepted(sourceId, acceptedSessionId);
        ClientMediaSoundRegistry.onSessionAccepted(sourceId, acceptedSessionId);
        policy.afterRegisterPlayback(payload, sourceId);
        schedulePlayback(payload, sourceId, policy);
    }

    public static void handleTimeline(ClientMediaTimelinePayload payload, ClientMediaSyncPolicy policy) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || payload == null || payload.sourceId() == null
                || policy == null) {
            return;
        }
        if (!policy.canHear(payload.sourceId(), payload.headphoneRouted())) {
            policy.stop(payload.sourceId());
            return;
        }
        ActivePlayback previous = ClientMediaPlaybackRegistry.get(payload.sourceId());
        Optional<PlaybackSessionId> playbackSessionId = payload.playbackSessionId();
        if (previous == null || playbackSessionId.isEmpty()
                || !playbackSessionId.equals(previous.playbackSessionId())) {
            return;
        }
        ClientMediaPlaybackRegistry.put(payload.sourceId(),
                previous.withServerElapsed(Math.max(0L, payload.elapsedMillis()),
                        previous.durationMillis()).withHeadphoneRouted(payload.headphoneRouted()));
        policy.updateVolume(payload.sourceId(), payload.volumePerMille() / 1000.0F);
    }

    private static void schedulePlayback(ClientMediaSyncPayload payload, UUID sourceId,
            ClientMediaSyncPolicy policy) {
        ClientMediaPreparePolicy preparePolicy = policy.preparePolicy(payload);
        if (preparePolicy != null) {
            ClientMediaDemandScheduler.schedule(payload, sourceId, preparePolicy);
        } else {
            policy.preparePlayback(payload, sourceId);
        }
    }
}
