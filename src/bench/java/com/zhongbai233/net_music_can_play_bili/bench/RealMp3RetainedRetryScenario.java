package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requirePcmQuality;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.client.ClientMediaLifecycleHandler;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedMediaSound;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackSessions;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPrepareLauncher;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPreparePolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaRetryHandler;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaRetryPolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundHandle;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncHandler;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPolicy;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncPacket;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

final class RealMp3RetainedRetryScenario implements BenchClientScenario {
    private static final long FIRST_OFFSET_MILLIS = 5_000L;
    private static final long REFRESH_OFFSET_MILLIS = 12_000L;
    private static final long TOTAL_MILLIS = 360_000L;
    private static final long RETRY_DELAY_MILLIS = 750L;
    private static final long RETRY_SETTLE_NANOS = 1_000_000_000L;
    private static final BenchMetricDescriptor SOUND_ACTIVE = new BenchMetricDescriptor(
            "ncpb.real_mp3_retained_retry.sound_active", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor CHANNEL_STARTS = new BenchMetricDescriptor(
            "ncpb.real_mp3_retained_retry.channel_starts", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor PREPARES = new BenchMetricDescriptor(
            "ncpb.real_mp3_retained_retry.prepares", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor RETRY_DISPATCHES = new BenchMetricDescriptor(
            "ncpb.real_mp3_retained_retry.retry_dispatches", "count", MetricDirection.LOWER_IS_BETTER);
    private static final BenchMetricDescriptor AUDIBLE_MILLIS = new BenchMetricDescriptor(
            "ncpb.real_mp3_retained_retry.audible_millis", "milliseconds", MetricDirection.NEUTRAL);

    private final AudioStreamProperties.RealMp3Bench properties = AudioStreamProperties.realMp3Bench();
    private final PlaybackSessionId retainedSession = PlaybackSessionId.of("bench-real-mp3-retained-retry");
    private final UUID sourceId = UUID.fromString("00000000-0000-0000-0000-00000000a031");
    private final RetainedRetrySyncPolicy policy = new RetainedRetrySyncPolicy();
    private final AtomicInteger streamingChannelStarts = new AtomicInteger();
    private final AtomicReference<Throwable> streamFailure = new AtomicReference<>();
    private final Consumer<PlayStreamingSourceEvent> streamingListener = event -> {
        if (event.getSound() instanceof RetainedRetrySound) {
            streamingChannelStarts.incrementAndGet();
        }
    };
    private OpenALTappedAudioInputStream.LifecycleSnapshot tapBaseline;
    private StereoOpenALHandler.LifecycleSnapshot stereoBaseline;
    private long audioStagingBaseline;
    private long retrySettleDeadlineNanos;
    private boolean listenerRegistered;
    private boolean converged;
    private int phase;

    @Override
    public void setup(BenchClientContext context) {
        ClientMediaPlaybackSessions.clearAll(null);
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
        ClientAudioOutputRegistry.setOwnerVolume(sourceId, 1.0F);
        tapBaseline = OpenALTappedAudioInputStream.lifecycleSnapshot();
        stereoBaseline = StereoOpenALHandler.lifecycleSnapshot();
        audioStagingBaseline = MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING)
                .currentBytes();
        NeoForge.EVENT_BUS.addListener(PlayStreamingSourceEvent.class, streamingListener);
        listenerRegistered = true;
        accept(context, "initial", FIRST_OFFSET_MILLIS);
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
                .getOwnerStereoSnapshot(sourceId).orElse(null);
        record(context, output);

        if (phase == 0 && ready(context, 0, 1, output)) {
            requirePcmQuality("retained retry initial transport", output.firstPcm());
            RetainedRetrySound failed = policy.sound(0);
            failed.failTransport();
            if (!ClientMediaRetryHandler.retryAfterStreamFailure(sourceId, retainedSession,
                    new IOException("bench retained-session transport failure"), policy.retryPolicy())) {
                throw new AssertionError("Real retained-session retry was not admitted");
            }
            if (!ClientMediaRetryHandler.isPending(sourceId, retainedSession)) {
                throw new AssertionError("Real retained-session retry owner was not recorded");
            }
            accept(context, "refreshed", REFRESH_OFFSET_MILLIS);
            if (ClientMediaRetryHandler.isPending(sourceId, retainedSession)) {
                throw new AssertionError("Authoritative transport refresh did not clear exact retry owner");
            }
            requireRetainedSession(REFRESH_OFFSET_MILLIS);
            retrySettleDeadlineNanos = System.nanoTime() + RETRY_SETTLE_NANOS;
            phase = 1;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 1 && ready(context, 1, 2, output)
                && System.nanoTime() >= retrySettleDeadlineNanos) {
            requirePcmQuality("retained retry refreshed transport", output.firstPcm());
            if (policy.retryDispatches() != 0 || policy.prepareCount() != 2 || policy.rebuildCount() != 1
                    || policy.launchUrls().size() != 2
                    || policy.launchUrls().get(0).equals(policy.launchUrls().get(1))) {
                throw new AssertionError("Retained-session refresh did not win exact transport replacement: "
                        + policy.summary());
            }
            RetainedRetrySound first = policy.sound(0);
            if (first.discards() != 1 || !first.stopped()
                    || OpenALTappedAudioInputStream.lifecycleSnapshot().instancesCreated()
                            != tapBaseline.instancesCreated() + 2L
                    || StereoOpenALHandler.lifecycleSnapshot().instancesCreated()
                            != stereoBaseline.instancesCreated() + 2L) {
                throw new AssertionError("Refreshed transport recreated or retired the wrong resources: "
                        + policy.summary());
            }
            if (!ClientMediaRetryHandler.retryAfterStreamFailure(sourceId, retainedSession,
                    new IOException("bench world-unload pending retry"), policy.retryPolicy())
                    || !ClientMediaRetryHandler.isPending(sourceId, retainedSession)) {
                throw new AssertionError("World-unload retry owner was not recorded before cleanup");
            }
            if (context.minecraft().level == null) {
                throw new AssertionError("Integrated-client level disappeared before unload cleanup");
            }
            ClientMediaLifecycleHandler.onLevelUnload(new LevelEvent.Unload(context.minecraft().level));
            if (ClientMediaRetryHandler.isPending(sourceId, retainedSession)) {
                throw new AssertionError("World unload did not clear exact retry owner");
            }
            retrySettleDeadlineNanos = System.nanoTime() + RETRY_SETTLE_NANOS;
            phase = 2;
            return BenchClientStepResult.CONTINUE;
        }
        if (phase == 2 && System.nanoTime() >= retrySettleDeadlineNanos && resourcesConverged(context)) {
            if (policy.retryDispatches() != 0) {
                throw new AssertionError("World-unload retry timer dispatched after cleanup: "
                        + policy.summary());
            }
            converged = true;
            return BenchClientStepResult.COMPLETE;
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfStreamFailed();
        if (!converged || !resourcesConverged(context) || policy.retryDispatches() != 0
                || streamingChannelStarts.get() != 2 || policy.sounds().size() != 2
                || policy.sounds().stream().anyMatch(sound -> sound.discards() != 1)) {
            throw new AssertionError("Real retained-session retry did not converge: channels="
                    + streamingChannelStarts.get() + " policy=" + policy.summary() + " tap="
                    + OpenALTappedAudioInputStream.lifecycleSnapshot() + " stereo="
                    + StereoOpenALHandler.lifecycleSnapshot());
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        ClientMediaPlaybackSessions.clearAll(null);
        if (listenerRegistered) {
            NeoForge.EVENT_BUS.unregister(streamingListener);
            listenerRegistered = false;
        }
        ClientAudioOutputRegistry.cleanup();
        HttpAudioStreamHandler.closeModernStreams();
    }

    private void accept(BenchClientContext context, String transport, long elapsedMillis) {
        String transportUrl = properties.url() + "#ncpb-transport-" + transport;
        ClientMediaSyncPayload payload = new MP4PlaybackSyncPacket(context.player().getUUID(), sourceId,
                ClientMediaSyncPayload.SOURCE_PLAYER, context.player().getId(), context.player().getX(),
                context.player().getY(), context.player().getZ(), true, 0, transportUrl, transportUrl,
                "real MP3 retained retry", (int) (TOTAL_MILLIS / 1_000L), 1_000,
                retainedSession.value(), elapsedMillis, false);
        ClientMediaSyncHandler.handleSync(payload, policy);
    }

    private boolean ready(BenchClientContext context, int soundIndex, int expectedStarts,
            StereoOpenALHandler.DiagnosticSnapshot output) {
        return policy.sounds().size() > soundIndex && policy.sound(soundIndex).streamReady()
                && context.minecraft().getSoundManager().isActive(policy.sound(soundIndex))
                && streamingChannelStarts.get() == expectedStarts && output != null && output.started()
                && output.firstPcm().samples() >= 1_024L;
    }

    private void requireRetainedSession(long expectedElapsedMillis) {
        ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(sourceId);
        if (active == null || !active.playbackSessionId().filter(retainedSession::equals).isPresent()
                || active.timelineSnapshot().serverMillis() != expectedElapsedMillis) {
            throw new AssertionError("Transport refresh changed the logical playback session: " + active);
        }
    }

    private boolean resourcesConverged(BenchClientContext context) {
        OpenALTappedAudioInputStream.LifecycleSnapshot tap = OpenALTappedAudioInputStream.lifecycleSnapshot();
        StereoOpenALHandler.LifecycleSnapshot stereo = StereoOpenALHandler.lifecycleSnapshot();
        return !ClientMediaPlaybackRegistry.contains(sourceId) && ClientMediaSoundRegistry.get(sourceId) == null
                && !ClientMediaRetryHandler.isPending(sourceId, retainedSession)
                && policy.sounds().stream().noneMatch(context.minecraft().getSoundManager()::isActive)
                && tap.activeInstances() == tapBaseline.activeInstances()
                && tap.closesCompleted() >= tapBaseline.closesCompleted() + 2L
                && stereo.activeInstances() == stereoBaseline.activeInstances()
                && stereo.cleanupsCompleted() >= stereoBaseline.cleanupsCompleted() + 2L
                && ClientAudioOutputRegistry.getOwnerStereoSnapshot(sourceId).isEmpty()
                && AudioNativeCloseDiagnostics.global().snapshot(System.nanoTime()).activeOperations() == 0
                && OpenALSpatialAudio.pendingNativeDeleteBatches() == 0
                && MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING).currentBytes()
                        == audioStagingBaseline;
    }

    private void record(BenchClientContext context, StereoOpenALHandler.DiagnosticSnapshot output) {
        context.metrics().record(SOUND_ACTIVE, policy.sounds().stream()
                .filter(context.minecraft().getSoundManager()::isActive).count());
        context.metrics().record(CHANNEL_STARTS, streamingChannelStarts.get());
        context.metrics().record(PREPARES, policy.prepareCount());
        context.metrics().record(RETRY_DISPATCHES, policy.retryDispatches());
        context.metrics().record(AUDIBLE_MILLIS, output != null ? output.positionMillis() : -1L);
    }

    private void throwIfStreamFailed() {
        Throwable failure = streamFailure.get();
        if (failure != null) {
            throw new AssertionError("Real retained-session MP3 stream failed", failure);
        }
    }

    private final class RetainedRetrySyncPolicy implements ClientMediaSyncPolicy {
        private final CopyOnWriteArrayList<RetainedRetrySound> sounds = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> launchUrls = new CopyOnWriteArrayList<>();
        private final AtomicInteger prepares = new AtomicInteger();
        private final AtomicInteger rebuilds = new AtomicInteger();
        private final AtomicInteger retryDispatches = new AtomicInteger();
        private final ClientMediaRetryPolicy retryPolicy = new ClientMediaRetryPolicy() {
            @Override
            public long retryDelayMillis() {
                return RETRY_DELAY_MILLIS;
            }

            @Override
            public void scheduleRetry(UUID deviceId, String sessionId,
                    ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
                tryScheduleRetry(deviceId, sessionId, active, error);
            }

            @Override
            public boolean tryScheduleRetry(UUID deviceId, String sessionId,
                    ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
                retryDispatches.incrementAndGet();
                return true;
            }
        };
        private final ClientMediaPreparePolicy preparePolicy = new ClientMediaPreparePolicy() {
            @Override
            public long prepareTimeoutSeconds() {
                return 30L;
            }

            @Override
            public boolean canHear(UUID ignored, boolean headphoneRouted) {
                return true;
            }

            @Override
            public void stop(UUID ignored) {
                ClientMediaPlaybackSessions.stop(sourceId, null);
            }

            @Override
            public boolean allowDolby(ClientMediaSyncPayload payload, UUID ignored) {
                return false;
            }

            @Override
            public boolean shouldLoadLyrics(ClientMediaSyncPayload payload, UUID ignored) {
                return false;
            }

            @Override
            public String lyricLogLabel() {
                return "Bench retained retry";
            }

            @Override
            public SoundInstance createSound(UUID ignored, ClientMediaSyncPayload payload, URL url,
                    LyricRecord lyricRecord, long startOffsetMillis) {
                RetainedRetrySound sound = new RetainedRetrySound(url, payload.durationSeconds(),
                        payload.sessionId(), startOffsetMillis);
                sounds.add(sound);
                if (!ClientMediaSoundRegistry.tryRegister(sourceId, retainedSession, sound)) {
                    throw new AssertionError("Refreshed retained-session sound registration was rejected");
                }
                return sound;
            }

            @Override
            public void onLaunch(ClientMediaSyncPayload payload, UUID ignored, long startOffsetMillis,
                    String playUrl) {
                launchUrls.add(playUrl);
            }
        };

        @Override
        public boolean canHear(UUID ignored, boolean headphoneRouted) {
            return true;
        }

        @Override
        public void stop(UUID ignored) {
            ClientMediaPlaybackSessions.stop(sourceId, null);
        }

        @Override
        public void updateVolume(UUID ignored, float volume) {
            ClientMediaSoundHandle sound = ClientMediaSoundRegistry.get(sourceId);
            if (sound != null) {
                sound.setMediaVolume(volume);
            }
        }

        @Override
        public boolean shouldRebuildSound(UUID ignored, ClientMediaSyncPayload payload) {
            ClientMediaSoundHandle sound = ClientMediaSoundRegistry.get(sourceId);
            return sound == null || sound.stopped()
                    || !payload.playbackSessionId().equals(sound.playbackSession());
        }

        @Override
        public void preparePlayback(ClientMediaSyncPayload payload, UUID ignored) {
            prepares.incrementAndGet();
            ClientMediaPrepareLauncher.preparePlaybackAsync(payload, sourceId, preparePolicy);
        }

        @Override
        public void onRebuildSound(ClientMediaSyncPayload payload, UUID ignored) {
            rebuilds.incrementAndGet();
        }

        ClientMediaRetryPolicy retryPolicy() {
            return retryPolicy;
        }

        int prepareCount() {
            return prepares.get();
        }

        int rebuildCount() {
            return rebuilds.get();
        }

        int retryDispatches() {
            return retryDispatches.get();
        }

        List<RetainedRetrySound> sounds() {
            return List.copyOf(sounds);
        }

        RetainedRetrySound sound(int index) {
            return sounds.get(index);
        }

        List<String> launchUrls() {
            return List.copyOf(launchUrls);
        }

        String summary() {
            return "prepares=" + prepareCount() + ", rebuilds=" + rebuildCount() + ", retryDispatches="
                    + retryDispatches() + ", launches=" + launchUrls() + ", discards="
                    + sounds.stream().map(RetainedRetrySound::discards).toList();
        }
    }

    private final class RetainedRetrySound extends SyncedMediaSound implements ClientMediaSoundHandle {
        private final AtomicBoolean streamReady = new AtomicBoolean();
        private final AtomicBoolean transportFailed = new AtomicBoolean();
        private final AtomicBoolean discarded = new AtomicBoolean();
        private final AtomicInteger discards = new AtomicInteger();

        private RetainedRetrySound(URL url, int durationSeconds, String sessionId, long startOffsetMillis) {
            super(url, durationSeconds, null, sessionId, startOffsetMillis);
            relative = true;
            attenuation = SoundInstance.Attenuation.NONE;
            volume = 1.0F;
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
        public boolean headphoneRouted() {
            return false;
        }

        @Override
        public boolean stopped() {
            return transportFailed.get() || isStopped();
        }

        @Override
        public void discardWithoutFinishing() {
            if (discarded.compareAndSet(false, true)) {
                discards.incrementAndGet();
                stop();
            }
        }

        @Override
        public void setMediaVolume(float volume) {
            this.volume = Math.max(0.0F, Math.min(1.0F, volume));
        }

        @Override
        protected void onStreamReady() {
            streamReady.set(true);
        }

        @Override
        protected void onStreamFailure(Exception error) {
            streamFailure.compareAndSet(null, error);
        }

        @Override
        protected void finishSession() {
        }

        @Override
        protected String streamDebugName() {
            return "real retained-session retry MP3";
        }

        void failTransport() {
            transportFailed.set(true);
            stop();
        }

        boolean streamReady() {
            return streamReady.get();
        }

        int discards() {
            return discards.get();
        }
    }
}
