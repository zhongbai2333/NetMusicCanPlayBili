package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry.ActivePlayback;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import net.minecraft.client.Minecraft;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAreaAudioZoneRegistry;

import java.util.Optional;
import java.util.UUID;

/** Shared client-side synchronized media packet handler. */
public final class ClientMediaSyncHandler {
    private ClientMediaSyncHandler() {
    }

    public static void handleSync(ClientMediaSyncPayload payload, ClientMediaSyncPolicy policy) {
        handleSync(payload, policy, 0L);
    }

    public static void handleSync(ClientMediaSyncPayload payload, ClientMediaSyncPolicy policy, long receivedNanos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || payload == null || policy == null) {
            return;
        }
        UUID sourceId = payload.sourceId() != null ? payload.sourceId() : payload.ownerId();
        policy.onSyncReceived(payload, sourceId);
        if (!payload.playing()) {
            ClientAreaAudioZoneRegistry.removeMovingZone(sourceId);
            policy.stop(sourceId);
            return;
        }
        if (!policy.canHear(sourceId, payload.headphoneRouted())) {
            ClientAreaAudioZoneRegistry.removeMovingZone(sourceId);
            policy.stop(sourceId);
            policy.onIgnoredCannotHear(payload, sourceId);
            return;
        }
        Optional<PlaybackSessionId> playbackSessionId = payload.playbackSessionId();
        if (payload.playUrl() == null || payload.playUrl().isBlank() || playbackSessionId.isEmpty()) {
            return;
        }
        ClientAreaAudioZoneRegistry.setMovingZone(sourceId, payload.areaAudioZone());

        ClientMediaPlaybackRegistry.SourceLocation sourceLocation = ClientMediaPlaybackRegistry.SourceLocation
                .from(payload);
        ActivePlayback previous = ClientMediaPlaybackRegistry.get(sourceId);
        String physicalToken = com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync.parseTerminalToken(payload.playUrl());
        boolean refreshedTransport = !physicalToken.isEmpty() && !ClientMediaTerminalRegistry.current(
                sourceId, payload.sessionId(), physicalToken);
        policy.beforeRegisterPlayback(payload, sourceId);
        if (previous != null && playbackSessionId.equals(previous.playbackSessionId())) {
            PlaybackSessionId retainedSessionId = playbackSessionId.orElseThrow();
            ActivePlayback updated = previous.withServerElapsed(Math.max(0L, payload.elapsedMillis()),
                    Math.max(0L, payload.durationSeconds()) * 1000L)
                    .withSourceLocation(sourceLocation)
                    .withHeadphoneRouted(payload.headphoneRouted());
            ClientMediaPlaybackRegistry.put(sourceId, updated);
            ClientMediaPlaybackRegistry.captureStartupPacing(sourceId, payload, receivedNanos);
            policy.updateVolume(sourceId, payload.volumePerMille() / 1000.0F);
            if (refreshedTransport) ClientMediaRetryHandler.onSessionRefreshed(sourceId, retainedSessionId);
            boolean rebuildSound = policy.shouldRebuildSound(sourceId, payload);
            if (refreshedTransport || rebuildSound) {
                policy.onRebuildSound(payload, sourceId);
                schedulePlayback(payload, sourceId, policy);
            }
            return;
        }

        PlaybackSessionId acceptedSessionId = playbackSessionId.orElseThrow();
        ClientMediaPlaybackRegistry.put(sourceId, ClientMediaPlaybackRegistry.createFromSync(payload));
        ClientMediaPlaybackRegistry.captureStartupPacing(sourceId, payload, receivedNanos);
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
            ClientAreaAudioZoneRegistry.removeMovingZone(payload.sourceId());
            policy.stop(payload.sourceId());
            return;
        }
        ActivePlayback previous = ClientMediaPlaybackRegistry.get(payload.sourceId());
        Optional<PlaybackSessionId> playbackSessionId = payload.playbackSessionId();
        if (previous == null || playbackSessionId.isEmpty()
                || !playbackSessionId.equals(previous.playbackSessionId())) {
            return;
        }
        ClientAreaAudioZoneRegistry.setMovingZone(payload.sourceId(), payload.areaAudioZone());
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
