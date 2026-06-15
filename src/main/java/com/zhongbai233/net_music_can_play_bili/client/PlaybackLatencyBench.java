package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 端到端播放延迟 bench
 *
 * <p>
 * 默认关闭。需同时启用 {@code -Dbili.video.advanced_features=true}
 * 与 {@code -Dbili.video.enable_bench_features=true}，再用 {@code -Dbili.playback.bench=true}
 * 打开后，才会做低频采样日志；不参与同步决策。
 * </p>
 */
public final class PlaybackLatencyBench {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = VideoFeatureFlags.benchFeaturesEnabled()
            && VideoFeatureFlags.advancedBoolean("bili.playback.bench", false);
    private static final boolean PASSIVE_SUMMARY_LOGS = VideoFeatureFlags.benchFeaturesEnabled()
            && VideoFeatureFlags.advancedBoolean("bili.playback.bench.passive_log", false);
    private static final long LOG_INTERVAL_NANOS = Math.max(250L,
            VideoFeatureFlags.advancedLong("bili.playback.bench.interval_ms", 3_000L)) * 1_000_000L;

    private static final Map<String, VideoProbe> VIDEO = new ConcurrentHashMap<>();
    private static final Map<Integer, AudioProbe> AUDIO = new ConcurrentHashMap<>();
    private static final AtomicLong LAST_SUMMARY_NANOS = new AtomicLong();
    private static final AtomicLong USER_MARK_NANOS = new AtomicLong();
    private static volatile String currentRunId = "";

    private PlaybackLatencyBench() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void markVideoQueued(String sessionId, long frameIndex, long ptsNanos, int queueSize,
            int queueCapacity) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        VideoProbe probe = video(sessionId);
        probe.firstQueuedNanos.compareAndSet(0L, now);
        probe.lastQueuedNanos.set(now);
        probe.lastQueuedFrame.set(frameIndex);
        probe.lastQueuedPtsMillis.set(ptsNanos >= 0L ? ptsNanos / 1_000_000L : -1L);
        probe.lastQueueSize.set(queueSize);
        probe.lastQueueCapacity.set(queueCapacity);
        maybeLog(now);
    }

    public static void markVideoUploaded(String sessionId, long frameIndex, long ptsNanos, long uploadNanos) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        VideoProbe probe = video(sessionId);
        probe.firstUploadedNanos.compareAndSet(0L, now);
        probe.lastUploadedNanos.set(now);
        probe.lastUploadedFrame.set(frameIndex);
        probe.lastUploadedPtsMillis.set(ptsNanos >= 0L ? ptsNanos / 1_000_000L : -1L);
        probe.lastUploadCostMillis.set(Math.max(0L, uploadNanos / 1_000_000L));
        maybeLog(now);
    }

    public static void markVideoSubmitted(String sessionId, long displayedPtsNanos) {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        VideoProbe probe = video(sessionId);
        probe.firstSubmittedNanos.compareAndSet(0L, now);
        probe.lastSubmittedNanos.set(now);
        if (displayedPtsNanos >= 0L) {
            probe.lastSubmittedPtsMillis.set(displayedPtsNanos / 1_000_000L);
        } else {
            long uploadedPts = probe.lastUploadedPtsMillis.get();
            if (uploadedPts >= 0L) {
                probe.lastSubmittedPtsMillis.set(uploadedPts);
            }
        }
        maybeLog(now);
    }

    public static void markAudioQueued(Object owner, String kind, int blocksOrFramesQueued, long mediaSamplesQueued,
            int sampleRate) {
        if (!ENABLED || owner == null) {
            return;
        }
        long now = System.nanoTime();
        AudioProbe probe = audio(owner, kind);
        probe.firstQueuedNanos.compareAndSet(0L, now);
        probe.lastQueuedNanos.set(now);
        probe.lastQueueSize.set(blocksOrFramesQueued);
        probe.lastMediaFedSamples.set(Math.max(0L, mediaSamplesQueued));
        probe.sampleRate.set(Math.max(1, sampleRate));
        maybeLog(now);
    }

    public static void markAudioOpenAlInitialized(Object owner, String kind, int sampleRate) {
        if (!ENABLED || owner == null) {
            return;
        }
        long now = System.nanoTime();
        AudioProbe probe = audio(owner, kind);
        probe.openAlInitializedNanos.compareAndSet(0L, now);
        probe.sampleRate.set(Math.max(1, sampleRate));
        maybeLog(now);
    }

    public static void markAudioStarted(Object owner, String kind, int queueSize, long mediaSamplesFed,
            int sampleRate) {
        if (!ENABLED || owner == null) {
            return;
        }
        long now = System.nanoTime();
        AudioProbe probe = audio(owner, kind);
        probe.startedNanos.compareAndSet(0L, now);
        probe.lastQueueSize.set(queueSize);
        probe.lastMediaFedSamples.set(Math.max(0L, mediaSamplesFed));
        probe.sampleRate.set(Math.max(1, sampleRate));
        maybeLog(now);
    }

    public static void markAudioFed(Object owner, String kind, int fedBlocksOrFrames, long mediaSamplesFed,
            int queueSize, int sampleRate) {
        if (!ENABLED || owner == null || fedBlocksOrFrames <= 0) {
            return;
        }
        long now = System.nanoTime();
        AudioProbe probe = audio(owner, kind);
        probe.firstFedNanos.compareAndSet(0L, now);
        probe.lastFedNanos.set(now);
        probe.lastFedBatch.set(fedBlocksOrFrames);
        probe.lastMediaFedSamples.set(Math.max(0L, mediaSamplesFed));
        probe.lastQueueSize.set(queueSize);
        probe.sampleRate.set(Math.max(1, sampleRate));
        maybeLog(now);
    }

    public static void markAudioConsumed(Object owner, String kind, long consumedSamples, int sampleRate) {
        if (!ENABLED || owner == null || consumedSamples <= 0L) {
            return;
        }
        long now = System.nanoTime();
        AudioProbe probe = audio(owner, kind);
        probe.firstConsumedNanos.compareAndSet(0L, now);
        probe.lastConsumedNanos.set(now);
        probe.lastConsumedSamples.set(consumedSamples);
        probe.sampleRate.set(Math.max(1, sampleRate));
        maybeLog(now);
    }

    public static void reset() {
        VIDEO.clear();
        AUDIO.clear();
        USER_MARK_NANOS.set(0L);
        LAST_SUMMARY_NANOS.set(0L);
        LOGGER.info("播放延迟Bench已重置");
    }

    public static void beginRun(String runId) {
        reset();
        currentRunId = runId == null ? "" : runId;
        LOGGER.info("播放延迟Bench开始: runId={}", currentRunId.isBlank() ? "-" : currentRunId);
    }

    public static BenchSnapshot snapshot() {
        VideoSnapshot bestVideo = null;
        for (VideoProbe probe : VIDEO.values()) {
            VideoSnapshot snapshot = new VideoSnapshot(probe.sessionId,
                    since(probe.firstQueuedNanos, probe.firstUploadedNanos),
                    since(probe.firstQueuedNanos, probe.firstSubmittedNanos),
                    since(probe.lastQueuedNanos, probe.lastUploadedNanos),
                    since(probe.lastUploadedNanos, probe.lastSubmittedNanos),
                    probe.lastQueuedFrame.get(), probe.lastUploadedFrame.get(), probe.lastQueuedPtsMillis.get(),
                    probe.lastUploadedPtsMillis.get(), probe.lastSubmittedPtsMillis.get(),
                    probe.lastUploadCostMillis.get(), probe.lastQueueSize.get(), probe.lastQueueCapacity.get());
            if (bestVideo == null || snapshot.lastUploadedFrame() > bestVideo.lastUploadedFrame()) {
                bestVideo = snapshot;
            }
        }

        java.util.ArrayList<AudioSnapshot> audios = new java.util.ArrayList<>();
        for (AudioProbe probe : AUDIO.values()) {
            int sr = (int) Math.max(1L, probe.sampleRate.get());
            long fedMs = probe.lastMediaFedSamples.get() * 1_000L / sr;
            long consumedMs = probe.lastConsumedSamples.get() * 1_000L / sr;
            AudioSnapshot snapshot = new AudioSnapshot(probe.kind, Integer.toHexString(probe.identity),
                    since(probe.firstQueuedNanos, probe.openAlInitializedNanos),
                    since(probe.firstQueuedNanos, probe.startedNanos),
                    since(probe.firstQueuedNanos, probe.firstFedNanos),
                    since(probe.firstQueuedNanos, probe.firstConsumedNanos),
                    since(probe.firstFedNanos, probe.firstConsumedNanos),
                    probe.lastFedBatch.get(), probe.lastQueueSize.get(), fedMs, consumedMs,
                    Math.max(0L, fedMs - consumedMs), sr);
            audios.add(snapshot);
        }
        audios.sort((a, b) -> {
            int kind = a.kind().compareTo(b.kind());
            return kind != 0 ? kind : a.id().compareTo(b.id());
        });
        return new BenchSnapshot(currentRunId, bestVideo, List.copyOf(audios));
    }

    public static void markUser(String label) {
        long now = System.nanoTime();
        long previous = USER_MARK_NANOS.getAndSet(now);
        if (previous > 0L) {
            LOGGER.info("播放延迟Bench用户标记: label={}, sincePrevious={}ms", label, ms(now - previous));
        } else {
            LOGGER.info("播放延迟Bench用户标记: label={}, first=true", label);
        }
    }

    public static void recordPerceivedDelay(long delayMillis, String note) {
        LOGGER.info("播放延迟Bench听感校准: perceivedDelay={}ms note={}", delayMillis,
                note == null || note.isBlank() ? "-" : note);
    }

    public static void logNow() {
        logSummary(System.nanoTime(), true);
    }

    private static VideoProbe video(String sessionId) {
        String key = sessionId == null || sessionId.isBlank() ? "<preview>" : sessionId;
        return VIDEO.computeIfAbsent(key, VideoProbe::new);
    }

    private static AudioProbe audio(Object owner, String kind) {
        int key = System.identityHashCode(owner);
        return AUDIO.computeIfAbsent(key, ignored -> new AudioProbe(kind, key));
    }

    private static void maybeLog(long now) {
        if (currentRunId.isBlank() && !PASSIVE_SUMMARY_LOGS) {
            return;
        }
        long last = LAST_SUMMARY_NANOS.get();
        if (last > 0L && now - last < LOG_INTERVAL_NANOS) {
            return;
        }
        if (LAST_SUMMARY_NANOS.compareAndSet(last, now)) {
            logSummary(now, false);
        }
    }

    private static void logSummary(long now, boolean forced) {
        if (!ENABLED && !forced) {
            return;
        }
        if (VIDEO.isEmpty() && AUDIO.isEmpty()) {
            LOGGER.info("播放延迟Bench: 暂无采样；确认已加 -Dbili.playback.bench=true 并开始播放");
            return;
        }
        for (VideoProbe v : VIDEO.values()) {
            LOGGER.info("播放延迟Bench-视频: runId={}, session={}, firstQueue→upload={}ms, firstQueue→submit={}ms, "
                    + "lastQueue→upload={}ms, lastUpload→submit={}ms, queuedFrame={}, uploadedFrame={}, "
                    + "queuedPts={}ms, uploadedPts={}ms, submittedPts={}ms, uploadCost={}ms, queue={}/{}",
                    currentRunId.isBlank() ? "-" : currentRunId,
                    v.sessionId,
                    since(v.firstQueuedNanos, v.firstUploadedNanos),
                    since(v.firstQueuedNanos, v.firstSubmittedNanos),
                    since(v.lastQueuedNanos, v.lastUploadedNanos),
                    since(v.lastUploadedNanos, v.lastSubmittedNanos),
                    v.lastQueuedFrame.get(), v.lastUploadedFrame.get(), v.lastQueuedPtsMillis.get(),
                    v.lastUploadedPtsMillis.get(), v.lastSubmittedPtsMillis.get(), v.lastUploadCostMillis.get(),
                    v.lastQueueSize.get(), v.lastQueueCapacity.get());
        }
        for (AudioProbe a : AUDIO.values()) {
            int sr = (int) Math.max(1L, a.sampleRate.get());
            long fedMs = a.lastMediaFedSamples.get() * 1_000L / sr;
            long consumedMs = a.lastConsumedSamples.get() * 1_000L / sr;
            LOGGER.info("播放延迟Bench-音频: runId={}, kind={}, id={}, queue→openalInit={}ms, queue→start={}ms, "
                    + "queue→firstFeed={}ms, queue→firstConsumed={}ms, feed→firstConsumed={}ms, "
                    + "fedBatch={}, queue={}, fedMedia={}ms, consumedMedia={}ms, openalBuffered={}ms, sampleRate={}Hz",
                    currentRunId.isBlank() ? "-" : currentRunId,
                    a.kind, Integer.toHexString(a.identity),
                    since(a.firstQueuedNanos, a.openAlInitializedNanos),
                    since(a.firstQueuedNanos, a.startedNanos),
                    since(a.firstQueuedNanos, a.firstFedNanos),
                    since(a.firstQueuedNanos, a.firstConsumedNanos),
                    since(a.firstFedNanos, a.firstConsumedNanos),
                    a.lastFedBatch.get(), a.lastQueueSize.get(), fedMs, consumedMs,
                    Math.max(0L, fedMs - consumedMs), sr);
        }
    }

    private static long since(AtomicLong start, AtomicLong end) {
        long s = start.get();
        long e = end.get();
        return s > 0L && e > 0L && e >= s ? ms(e - s) : -1L;
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000L;
    }

    private static final class VideoProbe {
        final String sessionId;
        final AtomicLong firstQueuedNanos = new AtomicLong();
        final AtomicLong lastQueuedNanos = new AtomicLong();
        final AtomicLong firstUploadedNanos = new AtomicLong();
        final AtomicLong lastUploadedNanos = new AtomicLong();
        final AtomicLong firstSubmittedNanos = new AtomicLong();
        final AtomicLong lastSubmittedNanos = new AtomicLong();
        final AtomicLong lastQueuedFrame = new AtomicLong(-1L);
        final AtomicLong lastUploadedFrame = new AtomicLong(-1L);
        final AtomicLong lastQueuedPtsMillis = new AtomicLong(-1L);
        final AtomicLong lastUploadedPtsMillis = new AtomicLong(-1L);
        final AtomicLong lastSubmittedPtsMillis = new AtomicLong(-1L);
        final AtomicLong lastUploadCostMillis = new AtomicLong(-1L);
        final AtomicLong lastQueueSize = new AtomicLong(-1L);
        final AtomicLong lastQueueCapacity = new AtomicLong(-1L);

        VideoProbe(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    private static final class AudioProbe {
        final String kind;
        final int identity;
        final AtomicLong firstQueuedNanos = new AtomicLong();
        final AtomicLong lastQueuedNanos = new AtomicLong();
        final AtomicLong openAlInitializedNanos = new AtomicLong();
        final AtomicLong startedNanos = new AtomicLong();
        final AtomicLong firstFedNanos = new AtomicLong();
        final AtomicLong lastFedNanos = new AtomicLong();
        final AtomicLong firstConsumedNanos = new AtomicLong();
        final AtomicLong lastConsumedNanos = new AtomicLong();
        final AtomicLong lastFedBatch = new AtomicLong();
        final AtomicLong lastQueueSize = new AtomicLong(-1L);
        final AtomicLong lastMediaFedSamples = new AtomicLong();
        final AtomicLong lastConsumedSamples = new AtomicLong();
        final AtomicLong sampleRate = new AtomicLong(48_000L);

        AudioProbe(String kind, int identity) {
            this.kind = kind == null || kind.isBlank() ? "unknown" : kind;
            this.identity = identity;
        }
    }

    public record BenchSnapshot(String runId, VideoSnapshot video, List<AudioSnapshot> audios) {
        public AudioSnapshot mainAudio() {
            AudioSnapshot best = null;
            for (AudioSnapshot audio : audios) {
                if (!audio.kind().startsWith("speaker-relay")) {
                    if (best == null || audio.fedMediaMillis() > best.fedMediaMillis()) {
                        best = audio;
                    }
                }
            }
            return best;
        }

        public List<AudioSnapshot> speakerRelays() {
            return audios.stream().filter(audio -> audio.kind().startsWith("speaker-relay")).toList();
        }
    }

    public record VideoSnapshot(String sessionId, long firstQueueToUploadMillis, long firstQueueToSubmitMillis,
            long lastQueueToUploadMillis, long lastUploadToSubmitMillis, long lastQueuedFrame,
            long lastUploadedFrame, long lastQueuedPtsMillis, long lastUploadedPtsMillis, long lastSubmittedPtsMillis,
            long lastUploadCostMillis, long lastQueueSize, long lastQueueCapacity) {
    }

    public record AudioSnapshot(String kind, String id, long queueToOpenAlInitMillis, long queueToStartMillis,
            long queueToFirstFeedMillis, long queueToFirstConsumedMillis, long feedToFirstConsumedMillis,
            long lastFedBatch, long lastQueueSize, long fedMediaMillis, long consumedMediaMillis,
            long openAlBufferedMillis, int sampleRate) {
    }
}
