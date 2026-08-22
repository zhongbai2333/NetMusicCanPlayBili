package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requirePcmQuality;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackCoordinator;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedMediaSound;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.net.URI;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class RealMp3SoundEngineScenario implements BenchClientScenario {
    private static final long FIRST_OFFSET_MILLIS = 5_000L;
    private static final long SECOND_OFFSET_MILLIS = 12_000L;
    private static final long PAUSE_HOLD_NANOS = 750_000_000L;
    private static final long PAUSE_POSITION_TOLERANCE_MILLIS = 80L;
    private static final long RESUME_PROGRESS_MILLIS = 250L;
    private static final long TOTAL_MILLIS = 360_000L;
    private static final BenchMetricDescriptor SOUND_ACTIVE = new BenchMetricDescriptor(
            "ncpb.real_mp3_sound_engine.sound_active", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor CHANNEL_STARTS = new BenchMetricDescriptor(
            "ncpb.real_mp3_sound_engine.channel_starts", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor TAP_ACTIVE = new BenchMetricDescriptor(
            "ncpb.real_mp3_sound_engine.tap_active", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor OPENAL_ACTIVE = new BenchMetricDescriptor(
            "ncpb.real_mp3_sound_engine.openal_active", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor PAUSED = new BenchMetricDescriptor(
            "ncpb.real_mp3_sound_engine.paused", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor AUDIBLE_MILLIS = new BenchMetricDescriptor(
            "ncpb.real_mp3_sound_engine.audible_millis", "milliseconds", MetricDirection.NEUTRAL);

    private final AudioStreamProperties.RealMp3Bench properties = AudioStreamProperties.realMp3Bench();
    private final PlaybackSessionId firstSession = PlaybackSessionId.of("bench-real-mp3-sound-engine-first");
    private final PlaybackSessionId secondSession = PlaybackSessionId.of("bench-real-mp3-sound-engine-second");
    private final BlockPos turntablePos = new BlockPos(31, 64, 33);
    private final AtomicInteger streamingChannelStarts = new AtomicInteger();
    private final AtomicReference<Throwable> streamFailure = new AtomicReference<>();
    private BenchSound firstSound;
    private BenchSound secondSound;
    private final Consumer<PlayStreamingSourceEvent> streamingListener = event -> {
        if (event.getSound() == firstSound || event.getSound() == secondSound) {
            streamingChannelStarts.incrementAndGet();
        }
    };
    private HttpAudioStreamHandler.RegisteredRequest firstRequest;
    private HttpAudioStreamHandler.RegisteredRequest secondRequest;
    private UUID ownerId;
    private OpenALTappedAudioInputStream.LifecycleSnapshot tapBaseline;
    private StereoOpenALHandler.LifecycleSnapshot stereoBaseline;
    private long audioStagingBaseline;
    private StereoOpenALHandler.PcmQuality firstPcm =
            new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D);
    private StereoOpenALHandler.PcmQuality secondPcm =
            new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0D, 0.0D);
    private boolean listenerRegistered;
    private int phase;
    private boolean converged;
    private long pausePositionMillis = -1L;
    private long pauseDeadlineNanos;
    private long resumePositionMillis = -1L;
    private boolean pauseContinuityVerified;
    private boolean exactMuteStopVerified;
    private boolean exactRangeStopVerified;

    @Override
    public void setup(BenchClientContext context) {
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
        ModernTurntablePlaybackTracker.stopAllSounds();
        ownerId = context.player().getUUID();
        ClientAudioOutputRegistry.setOwnerVolume(ownerId, 1.0F);
        tapBaseline = OpenALTappedAudioInputStream.lifecycleSnapshot();
        stereoBaseline = StereoOpenALHandler.lifecycleSnapshot();
        audioStagingBaseline = MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING)
                .currentBytes();

        NeoForge.EVENT_BUS.addListener(PlayStreamingSourceEvent.class, streamingListener);
        listenerRegistered = true;
        firstRequest = startSound(context, firstSession, FIRST_OFFSET_MILLIS, true);
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        return context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        throwIfStreamFailed();
        ClientAudioOutputRegistry.updatePositions(new float[] {
                (float) context.player().getX(), (float) context.player().getEyeY(),
                (float) context.player().getZ()
        });
        OpenALSpatialAudio.tickNativeDeletes(System.nanoTime());

        StereoOpenALHandler.DiagnosticSnapshot output = ClientAudioOutputRegistry
                .getOwnerStereoSnapshot(ownerId).orElse(null);
        if (output != null && output.firstPcm().samples() > 0L) {
            if (phase == 0) {
                firstPcm = output.firstPcm();
            } else {
                secondPcm = output.firstPcm();
            }
        }
        record(context);

        if (phase == 0 && firstSound.streamReady() && streamingChannelStarts.get() == 1
                && context.minecraft().getSoundManager().isActive(firstSound)
                && output != null && output.started() && output.firstPcm().samples() >= 1_024L) {
            requireHealthy("first SoundEngine channel", firstSound, 1, output);
            firstPcm = output.firstPcm();
            if (!ModernTurntablePlaybackTracker.tryStart(turntablePos, firstSession.value(),
                    (int) (TOTAL_MILLIS / 1_000L))) {
                throw new AssertionError("Could not bind first sound to turntable mute session");
            }
            ModernTurntablePlaybackTracker.registerSound(firstSound, turntablePos, firstSession.value());
            ModernTurntablePlaybackCoordinator.stop(turntablePos, firstSession.value());
            if (ModernTurntablePlaybackTracker.isActiveSession(turntablePos, firstSession.value())) {
                throw new AssertionError("Exact turntable mute stop left its client session active");
            }
            exactMuteStopVerified = true;
            phase = 1;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 1 && firstChannelConverged(context)) {
            secondRequest = startSound(context, secondSession, SECOND_OFFSET_MILLIS, false);
            phase = 2;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 2 && secondSound.streamReady() && streamingChannelStarts.get() == 2
                && context.minecraft().getSoundManager().isActive(secondSound)
                && output != null && output.started() && output.firstPcm().samples() >= 1_024L) {
            requireHealthy("replacement SoundEngine channel", secondSound, 2, output);
            secondPcm = output.firstPcm();
            pausePositionMillis = output.positionMillis();
            if (pausePositionMillis < 0L) {
                throw new AssertionError("Replacement output had no audible position before pause: " + output);
            }
            setPaused(context, true);
            pauseDeadlineNanos = System.nanoTime() + PAUSE_HOLD_NANOS;
            phase = 3;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 3 && System.nanoTime() >= pauseDeadlineNanos) {
            requirePausedContinuity(context, output);
            setPaused(context, false);
            resumePositionMillis = output.positionMillis();
            phase = 4;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 4 && output != null && !output.paused()
                && output.positionMillis() >= resumePositionMillis + RESUME_PROGRESS_MILLIS) {
            if (!isActive(context, secondSound) || streamingChannelStarts.get() != 2
                    || OpenALTappedAudioInputStream.lifecycleSnapshot().instancesCreated()
                            != tapBaseline.instancesCreated() + 2L
                    || StereoOpenALHandler.lifecycleSnapshot().instancesCreated()
                            != stereoBaseline.instancesCreated() + 2L) {
                throw new AssertionError("Pause/resume recreated the streaming channel or OpenAL pipeline");
            }
            pauseContinuityVerified = true;
            if (!ModernTurntablePlaybackTracker.tryStart(turntablePos, secondSession.value(),
                    (int) (TOTAL_MILLIS / 1_000L))) {
                throw new AssertionError("Could not bind replacement sound to turntable eject session");
            }
            ModernTurntablePlaybackTracker.registerSound(secondSound, turntablePos, secondSession.value());
            ModernTurntablePlaybackCoordinator.stop(turntablePos, firstSession.value());
            if (!ModernTurntablePlaybackTracker.isActiveSession(turntablePos, secondSession.value())
                    || !isActive(context, secondSound)) {
                throw new AssertionError("Stale turntable stop terminated the replacement disc session");
            }
            ModernTurntablePlaybackCoordinator.stop(turntablePos, secondSession.value());
            if (ModernTurntablePlaybackTracker.isActiveSession(turntablePos, secondSession.value())) {
                throw new AssertionError("Exact turntable range stop left its client session active");
            }
            exactRangeStopVerified = true;
            phase = 5;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 5 && resourcesConverged(context)) {
            converged = true;
            return BenchClientStepResult.COMPLETE;
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfStreamFailed();
        if (!converged || !pauseContinuityVerified || !exactMuteStopVerified || !exactRangeStopVerified
                || !resourcesConverged(context)) {
            throw new AssertionError("Minecraft SoundEngine streaming channels did not converge: firstActive="
                    + context.minecraft().getSoundManager().isActive(firstSound) + " secondActive="
                    + context.minecraft().getSoundManager().isActive(secondSound) + " tap="
                    + OpenALTappedAudioInputStream.lifecycleSnapshot() + " stereo="
                    + StereoOpenALHandler.lifecycleSnapshot());
        }
        if (streamingChannelStarts.get() != 2) {
            throw new AssertionError("Expected exactly two Minecraft streaming-channel starts, got "
                    + streamingChannelStarts.get());
        }
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        if (tap.instancesCreated() != tapBaseline.instancesCreated() + 2L
                || tap.closesCompleted() != tapBaseline.closesCompleted() + 2L
                || stereo.instancesCreated() != stereoBaseline.instancesCreated() + 2L
                || stereo.cleanupsStarted() != stereoBaseline.cleanupsStarted() + 2L
                || stereo.cleanupsCompleted() != stereoBaseline.cleanupsCompleted() + 2L) {
            throw new AssertionError("SoundEngine replacement must close both tapped streams and OpenAL outputs exactly once: "
                    + "tapBaseline=" + tapBaseline + " tap=" + tap + " stereoBaseline=" + stereoBaseline
                    + " stereo=" + stereo);
        }
        requirePcmQuality("first SoundEngine channel", firstPcm);
        requirePcmQuality("replacement SoundEngine channel", secondPcm);
    }

    @Override
    public void teardown(BenchClientContext context) {
        setPaused(context, false);
        stopSound(context, firstSound);
        stopSound(context, secondSound);
        ModernTurntablePlaybackTracker.stopAllSounds();
        cancelRequest(firstRequest);
        cancelRequest(secondRequest);
        if (listenerRegistered) {
            NeoForge.EVENT_BUS.unregister(streamingListener);
            listenerRegistered = false;
        }
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
    }

    private boolean resourcesConverged(BenchClientContext context) {
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        return !isActive(context, firstSound) && !isActive(context, secondSound)
                && tap.activeInstances() == tapBaseline.activeInstances()
                && tap.closesCompleted() >= tapBaseline.closesCompleted() + 2L
                && stereo.activeInstances() == stereoBaseline.activeInstances()
                && stereo.cleanupsCompleted() >= stereoBaseline.cleanupsCompleted() + 2L
                && ClientAudioOutputRegistry.getOwnerStereoSnapshot(ownerId).isEmpty()
                && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                && MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING).currentBytes()
                        == audioStagingBaseline;
    }

    private boolean firstChannelConverged(BenchClientContext context) {
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        return !isActive(context, firstSound)
                && tap.activeInstances() == tapBaseline.activeInstances()
                && tap.closesCompleted() >= tapBaseline.closesCompleted() + 1L
                && stereo.activeInstances() == stereoBaseline.activeInstances()
                && stereo.cleanupsCompleted() >= stereoBaseline.cleanupsCompleted() + 1L
                && ClientAudioOutputRegistry.getOwnerStereoSnapshot(ownerId).isEmpty();
    }

    private void requireHealthy(String phase, BenchSound currentSound, int expectedStarts,
            StereoOpenALHandler.DiagnosticSnapshot output) {
        if (streamingChannelStarts.get() != expectedStarts || !currentSound.streamReady()
                || output == null || !output.started()) {
            throw new AssertionError("Real MP3 did not fully attach to the Minecraft streaming channel: starts="
                    + streamingChannelStarts.get() + " ready=" + currentSound.streamReady() + " output=" + output);
        }
        requirePcmQuality(phase, output.firstPcm());
    }

    private void throwIfStreamFailed() {
        Throwable failure = streamFailure.get();
        if (failure != null) {
            throw new AssertionError("Real MP3 SoundEngine stream failed", failure);
        }
    }

    private void record(BenchClientContext context) {
        context.metrics().record(SOUND_ACTIVE,
                (isActive(context, firstSound) ? 1L : 0L) + (isActive(context, secondSound) ? 1L : 0L));
        context.metrics().record(CHANNEL_STARTS, streamingChannelStarts.get());
        context.metrics().record(TAP_ACTIVE,
                OpenALTappedAudioInputStream.lifecycleSnapshot().activeInstances());
        context.metrics().record(OPENAL_ACTIVE, StereoOpenALHandler.lifecycleSnapshot().activeInstances());
        StereoOpenALHandler.DiagnosticSnapshot output = ClientAudioOutputRegistry
                .getOwnerStereoSnapshot(ownerId).orElse(null);
        context.metrics().record(PAUSED, output != null && output.paused() ? 1L : 0L);
        context.metrics().record(AUDIBLE_MILLIS, output != null ? output.positionMillis() : -1L);
    }

    private void requirePausedContinuity(BenchClientContext context,
            StereoOpenALHandler.DiagnosticSnapshot output) {
        long pausedPosition = output != null ? output.positionMillis() : -1L;
        if (output == null || !output.paused()
                || Math.abs(pausedPosition - pausePositionMillis) > PAUSE_POSITION_TOLERANCE_MILLIS
                || streamingChannelStarts.get() != 2 || !isActive(context, secondSound)
                || OpenALTappedAudioInputStream.lifecycleSnapshot().instancesCreated()
                        != tapBaseline.instancesCreated() + 2L
                || StereoOpenALHandler.lifecycleSnapshot().instancesCreated()
                        != stereoBaseline.instancesCreated() + 2L) {
            throw new AssertionError("Real SoundEngine pause did not freeze the existing output: before="
                    + pausePositionMillis + " after=" + pausedPosition + " output=" + output + " starts="
                    + streamingChannelStarts.get());
        }
    }

    private static void setPaused(BenchClientContext context, boolean paused) {
        ClientAudioOutputRegistry.setPaused(paused);
        if (paused) {
            context.minecraft().getSoundManager().pauseAllExcept();
        } else {
            context.minecraft().getSoundManager().resume();
        }
    }

    private HttpAudioStreamHandler.RegisteredRequest startSound(BenchClientContext context,
            PlaybackSessionId sessionId, long offsetMillis, boolean first) {
        PlaybackRequest request = PlaybackRequest.now(properties.url(), null, sessionId.value(), offsetMillis,
                TOTAL_MILLIS, ownerId, null);
        HttpAudioStreamHandler.RegisteredRequest registered = HttpAudioStreamHandler.registerRequest(request);
        BenchSound created;
        try {
            created = new BenchSound(URI.create(registered.url()).toURL(), sessionId, offsetMillis, streamFailure);
        } catch (Exception error) {
            cancelRequest(registered);
            throw new AssertionError("Could not create real MP3 SoundEngine bench sound", error);
        }
        if (first) {
            firstSound = created;
        } else {
            secondSound = created;
        }
        SoundEngine.PlayResult result = context.minecraft().getSoundManager().play(created);
        if (result == SoundEngine.PlayResult.NOT_STARTED) {
            cancelRequest(registered);
            throw new AssertionError("Minecraft SoundEngine did not allocate the real MP3 streaming sound");
        }
        return registered;
    }

    private static void stopSound(BenchClientContext context, BenchSound sound) {
        if (sound != null) {
            context.minecraft().getSoundManager().stop(sound);
            sound.requestStop();
        }
    }

    private static boolean isActive(BenchClientContext context, BenchSound sound) {
        return sound != null && context.minecraft().getSoundManager().isActive(sound);
    }

    private static void cancelRequest(HttpAudioStreamHandler.RegisteredRequest request) {
        if (request != null) {
            request.requestToken().ifPresent(HttpAudioStreamHandler::cancelRequest);
        }
    }

    private static final class BenchSound extends SyncedMediaSound {
        private final AtomicReference<Throwable> failure;
        private final AtomicBoolean streamReady = new AtomicBoolean();

        private BenchSound(URL url, PlaybackSessionId sessionId, long offsetMillis,
                AtomicReference<Throwable> failure) {
            super(url, (int) (TOTAL_MILLIS / 1_000L), null, sessionId.value(), offsetMillis);
            this.failure = failure;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            // Production indexed sounds are intentionally submitted silent and fade in only after stream readiness.
            // Keeping the real SoundEngine/HTTP/PCM bench silent at submission prevents this regression from being
            // hidden by a test-only non-zero volume.
            this.volume = 0.0F;
        }

        @Override
        public void tick() {
            tick++;
        }

        @Override
        protected void refreshDecodeDemand() {
            setDecodeDemand(true);
        }

        @Override
        protected void onStreamReady() {
            streamReady.set(true);
        }

        @Override
        protected void onStreamFailure(Exception error) {
            failure.compareAndSet(null, error);
            super.onStreamFailure(error);
        }

        @Override
        protected void finishSession() {
        }

        @Override
        protected String streamDebugName() {
            return "real MP3 SoundEngine bench";
        }

        private boolean streamReady() {
            return streamReady.get();
        }

        private void requestStop() {
            stop();
        }
    }
}
