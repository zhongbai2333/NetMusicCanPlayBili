package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackDemandIndex;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackApproachPredictor;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;
import java.util.List;

/** Metadata-only scheduler shared by MP4 and Pad; URL preparation starts on audible demand. */
public final class ClientMediaDemandScheduler {
    private static final long IDLE_GRACE_MILLIS = 1_500L;
    private static final AudioPlaybackDemandIndex<Pending> DEMAND = new AudioPlaybackDemandIndex<>();

    private ClientMediaDemandScheduler() {
    }

    public static void schedule(ClientMediaSyncPayload payload, UUID sourceId, ClientMediaPreparePolicy policy) {
        PlaybackSessionId sessionId = payload != null ? payload.playbackSessionId().orElse(null) : null;
        if (sourceId == null || sessionId == null || policy == null) {
            return;
        }
        PlaybackSourceId source = PlaybackSourceId.of(sourceId);
        DEMAND.announce(source, sessionId, new Pending(payload, sourceId, policy));
        tick();
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.options == null) {
            return;
        }
        boolean gameAudioEnabled = minecraft.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F
                && minecraft.options.getSoundSourceVolume(SoundSource.RECORDS) > 0.0F;
        for (AudioPlaybackDemandIndex.SourceSnapshot<Pending> snapshot : DEMAND.snapshots()) {
            Pending pending = snapshot.playback().payload();
            PlaybackSessionId sessionId = snapshot.playback().sessionId();
            ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(pending.sourceId());
            if (active == null || !active.playbackSessionId().filter(sessionId::equals).isPresent()) {
                DEMAND.remove(snapshot.sourceId(), sessionId);
                continue;
            }
            boolean preparationDemand = gameAudioEnabled && hasPreparationDemand(pending, active);
            DEMAND.updateDemand(snapshot.sourceId(), sessionId,
                    preparationDemand ? Set.of(pending.sourceId()) : Set.of(), System.currentTimeMillis());
            if (DEMAND.claimStopAfterIdle(snapshot.sourceId(), sessionId,
                    System.currentTimeMillis(), IDLE_GRACE_MILLIS)) {
                ClientMediaPrepareLauncher.removeScheduledForDevice(pending.sourceId());
                ClientMediaSoundRegistry.removeAndDiscard(pending.sourceId());
                continue;
            }
            Pending admitted = DEMAND.claimStart(snapshot.sourceId(), sessionId).orElse(null);
            if (admitted != null) {
                ClientMediaPrepareLauncher.preparePlaybackAsync(admitted.payload(), admitted.sourceId(),
                        admitted.policy());
            }
        }
    }

    public static void markPlaying(UUID sourceId, String sessionId) {
        PlaybackSessionId parsed = PlaybackSessionId.parse(sessionId).orElse(null);
        if (sourceId != null && parsed != null) {
            DEMAND.markPlaying(PlaybackSourceId.of(sourceId), parsed);
        }
    }

    public static void markLaunchFailed(UUID sourceId, String sessionId) {
        PlaybackSessionId parsed = PlaybackSessionId.parse(sessionId).orElse(null);
        if (sourceId != null && parsed != null) {
            DEMAND.remove(PlaybackSourceId.of(sourceId), parsed);
        }
    }

    public static void remove(UUID sourceId) {
        if (sourceId == null) {
            return;
        }
        PlaybackSourceId source = PlaybackSourceId.of(sourceId);
        DEMAND.snapshot(source).ifPresent(snapshot -> DEMAND.remove(source, snapshot.sessionId()));
    }

    public static void clear() {
        DEMAND.clear();
    }

    public static List<DemandDebug> debugSnapshots() {
        return DEMAND.snapshots().stream().map(snapshot -> {
            Pending pending = snapshot.playback().payload();
            ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(pending.sourceId());
            Vec3 position = active != null ? active.sourceLocation().position() : Vec3.ZERO;
            return new DemandDebug(snapshot.sourceId(), snapshot.playback().sessionId(), position,
                    active != null ? active.volume() : 0.0F, pending.payload().headphoneRouted(),
                    snapshot.playback().state(), snapshot.playback().endpointIds().size());
        }).toList();
    }

    public record DemandDebug(PlaybackSourceId sourceId, PlaybackSessionId sessionId, Vec3 position,
            float volume, boolean headphoneRouted, AudioPlaybackDemandIndex.State state, int demandCount) {
    }

    private static boolean hasAudibleDemand(Pending pending,
            ClientMediaPlaybackRegistry.ActivePlayback active) {
        if (!pending.policy().canHear(pending.sourceId(), pending.payload().headphoneRouted())
                || active.volume() <= 0.0F) {
            return false;
        }
        if (pending.payload().headphoneRouted() || ClientMediaPlayback.isLocalPlayerSource(pending.sourceId())) {
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 listener = minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
        Vec3 source = ClientMediaAudioRouting.audiblePosition(pending.sourceId(), false);
        return AudioPlaybackRange.evaluateSphere((float) listener.distanceTo(source),
                AudioPlaybackRange.DEFAULT_DISTANCE, active.volume(), false).audible();
    }

    private static boolean hasPreparationDemand(Pending pending,
            ClientMediaPlaybackRegistry.ActivePlayback active) {
        if (hasAudibleDemand(pending, active)) {
            return true;
        }
        if (!pending.policy().canHear(pending.sourceId(), pending.payload().headphoneRouted())
                || active.volume() <= 0.0F || pending.payload().headphoneRouted()
                || ClientMediaPlayback.isLocalPlayerSource(pending.sourceId())) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        Vec3 listener = minecraft.player.position(), velocity = minecraft.player.getDeltaMovement();
        Vec3 source = ClientMediaAudioRouting.audiblePosition(pending.sourceId(), false);
        AudioPlaybackRange.Profile profile = AudioPlaybackRange.profile(AudioPlaybackRange.DEFAULT_DISTANCE,
                active.volume(), active.volume());
        return PlaybackApproachPredictor.willEnterSphere(listener.x, listener.y, listener.z,
                velocity.x, velocity.y, velocity.z, source.x, source.y, source.z, profile.fadeEndDistance());
    }
    private record Pending(ClientMediaSyncPayload payload, UUID sourceId, ClientMediaPreparePolicy policy) {
    }
}
