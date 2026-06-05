package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioRegistry;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.bili.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.bili.SpeakerAudioRelay;
import com.zhongbai233.net_music_can_play_bili.client.PlaybackLatencyBench.AudioSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.PlaybackLatencyBench.BenchSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.PlaybackLatencyBench.VideoSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.renderer.VideoBillboardPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.net.URI;
import javax.sound.sampled.AudioInputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动端到端视频 bench：进入世界后，用同一个 B 站视频和多个起点重复启动前方 billboard，汇总内部延迟报告
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class PlaybackAutoBench {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private static final boolean ENABLED = Boolean.getBoolean("bili.playback.auto_bench");
    private static final String VIDEO_ID = System.getProperty("bili.playback.auto_bench.bv", "BV17q9EBEE9w");
    private static final int PAGE = Integer.getInteger("bili.playback.auto_bench.page", 2);
    private static final int QUALITY = Integer.getInteger("bili.playback.auto_bench.quality", 116);
    private static final int ROUNDS = Integer.getInteger("bili.playback.auto_bench.rounds", 3);
    private static final long SAMPLE_MILLIS = Long.getLong("bili.playback.auto_bench.sample_ms", 15_000L);
    private static final long GAP_MILLIS = Long.getLong("bili.playback.auto_bench.gap_ms", 1_500L);
    private static final long[] START_POINTS = parseMillisList(
            System.getProperty("bili.playback.auto_bench.starts_ms", "2313499,2262919,5317200"));
    private static final boolean PREFER_NATIVE = Boolean.parseBoolean(
            System.getProperty("bili.playback.auto_bench.native", "true"));
    private static final boolean ENABLE_AUDIO = Boolean.parseBoolean(
            System.getProperty("bili.playback.auto_bench.audio", "true"));
    private static final boolean ENABLE_SPEAKER_RELAY = Boolean.parseBoolean(
            System.getProperty("bili.playback.auto_bench.speaker_relay", "true"));
    private static final String DECODER_OVERRIDE = System.getProperty("bili.video.ffmpeg.decoder", "").trim();
    private static volatile boolean benchAudioActive;

    private PlaybackAutoBench() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED || !STARTED.compareAndSet(false, true)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            STARTED.set(false);
            return;
        }
        if (benchAudioActive) {
            driveBenchAudio(mc);
        }
        CompletableFuture.runAsync(PlaybackAutoBench::run)
                .orTimeout(Math.max(60L, estimateTimeoutSeconds()), TimeUnit.SECONDS)
                .exceptionally(error -> {
                    LOGGER.error("自动播放Bench失败/超时", error);
                    Minecraft.getInstance().execute(VideoBillboardPreview::stop);
                    return null;
                });
    }

    private static void run() {
        LOGGER.info("══════════════════════════════════════════");
        LOGGER.info("  自动播放Bench开始: bv={}, page={}, quality={}, rounds={}, starts={}, sample={}ms, gap={}ms",
                VIDEO_ID, PAGE, QUALITY, ROUNDS, Arrays.toString(START_POINTS), SAMPLE_MILLIS, GAP_MILLIS);
        LOGGER.info("  模式: 进入存档自动启动，使用玩家正前方 billboard preview，不依赖方块/唱片机/外部截图录音");
        LOGGER.info("══════════════════════════════════════════");

        BiliApiClient.VideoId videoId = BiliApiClient.extractVideoId(VIDEO_ID);
        if (videoId == null) {
            LOGGER.warn("自动播放Bench视频ID无效: {}", VIDEO_ID);
            return;
        }

        BiliApiClient.VideoInfo info;
        BiliApiClient.VideoStream stream;
        String audioUrl = "";
        try {
            long infoStart = System.currentTimeMillis();
            info = BiliApiClient.getVideoInfo(videoId, PAGE);
            stream = BiliApiClient.getBestVideoStream(videoId, info.cid(), QUALITY);
            if (ENABLE_AUDIO) {
                audioUrl = BiliApiClient.getBestAudioUrl(videoId, info.cid(), false);
            }
            LOGGER.info(
                    "自动播放Bench视频信息: title='{}', cid={}, duration={}s, stream=q{} {} {} fps={} codecId={}, resolve={}ms",
                    info.displayTitle(), info.cid(), info.duration(), stream.quality(), stream.displaySize(),
                    stream.codecName(), stream.frameRate(), stream.codecId(), System.currentTimeMillis() - infoStart);
            LOGGER.info("自动播放Bench音频: enabled={}, speakerRelay={}, host={}", ENABLE_AUDIO,
                    ENABLE_SPEAKER_RELAY, hostOf(audioUrl));
        } catch (Exception e) {
            LOGGER.error("自动播放Bench解析B站视频失败", e);
            return;
        }

        int fps = Math.max(1, parseFrameRate(stream.frameRate(), 60));
        int width = Math.max(1, stream.width());
        int height = Math.max(1, stream.height());
        long totalMillis = Math.max(0L, info.duration() * 1000L);
        List<RunResult> results = new ArrayList<>();

        for (int round = 1; round <= Math.max(1, ROUNDS); round++) {
            for (long configuredStart : START_POINTS) {
                long startMillis = normalizeStart(configuredStart, totalMillis);
                String runId = "auto-r" + round + "-t" + startMillis;
                RunResult result = runOne(runId, stream, audioUrl, width, height, fps, startMillis, totalMillis,
                        round);
                results.add(result);
                sleep(GAP_MILLIS);
            }
        }

        Minecraft.getInstance().execute(VideoBillboardPreview::stop);
        logReport(info, stream, results);
    }

    private static RunResult runOne(String runId, BiliApiClient.VideoStream stream, String audioUrl, int width,
            int height, int fps, long startMillis, long totalMillis, int round) {
        long wallStart = System.nanoTime();
        PlaybackLatencyBench.beginRun(runId);
        LOGGER.info("自动播放Bench单轮开始: runId={}, round={}, start={}ms, duration={}ms", runId, round,
                startMillis, SAMPLE_MILLIS);
        BenchPlacement placement = captureAudiblePlacement(round);
        BlockPos benchPos = placement.turntablePos();
        BlockPos speakerLeft = placement.leftSpeakerPos();
        BlockPos speakerRight = placement.rightSpeakerPos();
        AudioBenchSession audio = AudioBenchSession.disabled();
        Minecraft.getInstance().execute(() -> {
            VideoBillboardPreview.stop();
            setupSpeakerRelays(benchPos, speakerLeft, speakerRight);
            VideoBillboardPreview.startPreviewAt(stream.baseUrl(), width, height, fps, stream.codecId(), runId,
                    startMillis, totalMillis, PREFER_NATIVE, DECODER_OVERRIDE.isBlank() ? null : DECODER_OVERRIDE);
        });
        LOGGER.info("自动播放Bench音频位置: runId={}, turntable={}, speakerLeft={}, speakerRight={}；这是可听测试位置，不再使用远程哨兵坐标",
                runId, benchPos, speakerLeft, speakerRight);
        if (ENABLE_AUDIO && audioUrl != null && !audioUrl.isBlank()) {
            audio = AudioBenchSession.start(runId, audioUrl, benchPos, startMillis, totalMillis);
        }
        benchAudioActive = true;
        sleep(SAMPLE_MILLIS);
        BenchSnapshot snapshot = PlaybackLatencyBench.snapshot();
        Minecraft.getInstance().execute(VideoBillboardPreview::stop);
        audio.close();
        cleanupSpeakerRelays(speakerLeft, speakerRight);
        benchAudioActive = false;
        long wallMillis = (System.nanoTime() - wallStart) / 1_000_000L;
        RunResult result = RunResult.from(runId, round, startMillis, wallMillis, snapshot, audio.startedOk(),
                audio.errorSummary());
        LOGGER.info("自动播放Bench单轮结果: {}", result.summaryLine());
        return result;
    }

    private static void logReport(BiliApiClient.VideoInfo info, BiliApiClient.VideoStream stream,
            List<RunResult> results) {
        List<RunResult> valid = results.stream().filter(result -> result.hasVideo()).toList();
        LOGGER.info("══════════════════════════════════════════");
        LOGGER.info("  自动播放Bench完整报告");
        LOGGER.info("  视频: '{}' q{} {} {} fps={} runs={} valid={}", info.displayTitle(), stream.quality(),
                stream.displaySize(), stream.codecName(), stream.frameRate(), results.size(), valid.size());
        for (RunResult result : results) {
            LOGGER.info("  RUN {}", result.summaryLine());
        }
        LOGGER.info("  汇总 firstQueue→upload: {}",
                stats(valid.stream().mapToLong(result -> result.firstQueueToUploadMillis()).toArray()));
        LOGGER.info("  汇总 firstQueue→submit: {}",
                stats(valid.stream().mapToLong(result -> result.firstQueueToSubmitMillis()).toArray()));
        LOGGER.info("  汇总 lastQueue→upload: {}",
                stats(valid.stream().mapToLong(result -> result.lastQueueToUploadMillis()).toArray()));
        LOGGER.info("  汇总 lastUpload→submit: {}",
                stats(valid.stream().mapToLong(result -> result.lastUploadToSubmitMillis()).toArray()));
        LOGGER.info("  汇总 uploadCost: {}",
                stats(valid.stream().mapToLong(result -> result.uploadCostMillis()).toArray()));
        LOGGER.info("  汇总 uploadedFrame: {}",
                stats(valid.stream().mapToLong(result -> result.uploadedFrames()).toArray()));
        LOGGER.info("  汇总 mainAudio queue→start: {}",
                stats(results.stream().mapToLong(result -> result.audioQueueToStartMillis()).toArray()));
        LOGGER.info("  汇总 mainAudio queue→firstConsumed: {}",
                stats(results.stream().mapToLong(result -> result.audioQueueToConsumedMillis()).toArray()));
        LOGGER.info("  汇总 mainAudio openalBuffered: {}",
                stats(results.stream().mapToLong(result -> result.audioOpenAlBufferedMillis()).toArray()));
        LOGGER.info("  汇总 speakerRelay openalBuffered: {}",
                stats(results.stream().mapToLong(result -> result.speakerOpenAlBufferedMillis()).toArray()));
        LOGGER.info("══════════════════════════════════════════");
    }

    private static void setupSpeakerRelays(BlockPos benchPos, BlockPos speakerLeft, BlockPos speakerRight) {
        if (!ENABLE_SPEAKER_RELAY) {
            return;
        }
        SpeakerAudioRelay left = new SpeakerAudioRelay();
        left.setChannelIndex(0);
        SpeakerAudioRelay right = new SpeakerAudioRelay();
        right.setChannelIndex(1);
        DolbyAudioRegistry.registerRelay(speakerLeft, benchPos, left);
        DolbyAudioRegistry.registerRelay(speakerRight, benchPos, right);
    }

    private static BenchPlacement captureAudiblePlacement(int round) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            BlockPos fallback = new BlockPos(0, 80, round);
            return new BenchPlacement(fallback, fallback.offset(-1, 0, 1), fallback.offset(1, 0, 1));
        }
        CompletableFuture<BenchPlacement> future = new CompletableFuture<>();
        mc.execute(() -> {
            if (mc.player == null) {
                BlockPos fallback = new BlockPos(0, 80, round);
                future.complete(new BenchPlacement(fallback, fallback.offset(-1, 0, 1), fallback.offset(1, 0, 1)));
                return;
            }
            BlockPos player = mc.player.blockPosition();
            BlockPos turntable = player.offset(0, 0, 2 + (round % 2));
            future.complete(new BenchPlacement(turntable, player.offset(-1, 0, 1), player.offset(1, 0, 1)));
        });
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            BlockPos fallback = mc.player != null ? mc.player.blockPosition() : new BlockPos(0, 80, round);
            return new BenchPlacement(fallback, fallback.offset(-1, 0, 1), fallback.offset(1, 0, 1));
        }
    }

    private static void cleanupSpeakerRelays(BlockPos speakerLeft, BlockPos speakerRight) {
        if (!ENABLE_SPEAKER_RELAY) {
            return;
        }
        DolbyAudioRegistry.clearMachineOverrideForSpeaker(speakerLeft);
        DolbyAudioRegistry.clearMachineOverrideForSpeaker(speakerRight);
    }

    private static void driveBenchAudio(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        DolbyAudioRegistry.updatePositions(new float[] { (float) mc.player.getX(), (float) mc.player.getY(),
                (float) mc.player.getZ() });
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return "-";
        }
        try {
            String host = URI.create(url).getHost();
            return host == null ? "unknown" : host;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String stats(long[] raw) {
        long[] values = Arrays.stream(raw).filter(value -> value >= 0L).sorted().toArray();
        if (values.length == 0) {
            return "n/a";
        }
        double avg = Arrays.stream(values).average().orElse(0.0D);
        long min = values[0];
        long max = values[values.length - 1];
        long p50 = percentile(values, 0.50D);
        long p95 = percentile(values, 0.95D);
        return String.format(Locale.ROOT, "count=%d avg=%.1fms min=%dms p50=%dms p95=%dms max=%dms",
                values.length, avg, min, p50, p95, max);
    }

    private static long percentile(long[] values, double percentile) {
        if (values.length == 0) {
            return -1L;
        }
        int index = (int) Math.ceil(percentile * values.length) - 1;
        return values[Math.max(0, Math.min(values.length - 1, index))];
    }

    private static long normalizeStart(long startMillis, long totalMillis) {
        long start = Math.max(0L, startMillis);
        if (totalMillis <= 0L) {
            return start;
        }
        return Math.min(start, Math.max(0L, totalMillis - Math.max(1_000L, SAMPLE_MILLIS)));
    }

    private static long[] parseMillisList(String raw) {
        if (raw == null || raw.isBlank()) {
            return new long[] { 0L };
        }
        String[] parts = raw.split(",");
        long[] values = new long[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                long value = Long.parseLong(part.trim());
                if (value >= 0L) {
                    values[count++] = value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return count == 0 ? new long[] { 0L } : Arrays.copyOf(values, count);
    }

    private static int parseFrameRate(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            if (raw.contains("/")) {
                String[] parts = raw.split("/", 2);
                double numerator = Double.parseDouble(parts[0].trim());
                double denominator = Double.parseDouble(parts[1].trim());
                return denominator > 0.0D ? Math.max(1, (int) Math.round(numerator / denominator)) : fallback;
            }
            return Math.max(1, (int) Math.round(Double.parseDouble(raw.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long estimateTimeoutSeconds() {
        long runs = Math.max(1, ROUNDS) * Math.max(1, START_POINTS.length);
        return 45L + runs * Math.max(2L, (SAMPLE_MILLIS + GAP_MILLIS) / 1000L + 5L);
    }

    private static void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record RunResult(String runId, int round, long startMillis, long wallMillis, boolean hasVideo,
            long firstQueueToUploadMillis, long firstQueueToSubmitMillis, long lastQueueToUploadMillis,
            long lastUploadToSubmitMillis, long uploadCostMillis, long uploadedFrames, long submittedPtsMillis,
            boolean audioStarted, String audioError, long audioQueueToStartMillis, long audioQueueToConsumedMillis,
            long audioOpenAlBufferedMillis, long speakerOpenAlBufferedMillis) {
        static RunResult from(String runId, int round, long startMillis, long wallMillis, BenchSnapshot snapshot,
                boolean audioStarted, String audioError) {
            VideoSnapshot video = snapshot != null ? snapshot.video() : null;
            AudioSnapshot mainAudio = snapshot != null ? snapshot.mainAudio() : null;
            long speakerBuffered = -1L;
            if (snapshot != null && !snapshot.speakerRelays().isEmpty()) {
                speakerBuffered = Math.round(snapshot.speakerRelays().stream()
                        .mapToLong(audio -> audio.openAlBufferedMillis()).average().orElse(-1.0D));
            }
            if (video == null) {
                return new RunResult(runId, round, startMillis, wallMillis, false, -1L, -1L, -1L, -1L, -1L, -1L,
                        -1L, audioStarted, audioError, audioValue(mainAudio, 0), audioValue(mainAudio, 1),
                        audioValue(mainAudio, 2), speakerBuffered);
            }
            return new RunResult(runId, round, startMillis, wallMillis, true,
                    video.firstQueueToUploadMillis(), video.firstQueueToSubmitMillis(),
                    video.lastQueueToUploadMillis(), video.lastUploadToSubmitMillis(), video.lastUploadCostMillis(),
                    video.lastUploadedFrame(), video.lastSubmittedPtsMillis(), audioStarted, audioError,
                    audioValue(mainAudio, 0), audioValue(mainAudio, 1), audioValue(mainAudio, 2), speakerBuffered);
        }

        private static long audioValue(AudioSnapshot audio, int field) {
            if (audio == null) {
                return -1L;
            }
            return switch (field) {
                case 0 -> audio.queueToStartMillis();
                case 1 -> audio.queueToFirstConsumedMillis();
                case 2 -> audio.openAlBufferedMillis();
                default -> -1L;
            };
        }

        String summaryLine() {
            return String.format(Locale.ROOT,
                    "runId=%s round=%d start=%dms wall=%dms video=%s firstQ→up=%dms firstQ→submit=%dms lastQ→up=%dms lastUp→submit=%dms uploadCost=%dms uploadedFrame=%d submittedPts=%dms audio=%s queue→start=%dms queue→consumed=%dms openalBuffered=%dms speakerBuffered=%dms error=%s",
                    runId, round, startMillis, wallMillis, hasVideo ? "ok" : "missing",
                    firstQueueToUploadMillis, firstQueueToSubmitMillis, lastQueueToUploadMillis,
                    lastUploadToSubmitMillis, uploadCostMillis, uploadedFrames, submittedPtsMillis,
                    audioStarted ? "ok" : "missing", audioQueueToStartMillis, audioQueueToConsumedMillis,
                    audioOpenAlBufferedMillis, speakerOpenAlBufferedMillis,
                    audioError == null || audioError.isBlank() ? "-" : audioError);
        }
    }

    private static final class AudioBenchSession implements AutoCloseable {
        private final AudioInputStream stream;
        private final CompletableFuture<Void> drainTask;
        private final AtomicBoolean closed;
        private final boolean startedOk;
        private final String errorSummary;

        private AudioBenchSession(AudioInputStream stream, CompletableFuture<Void> drainTask, AtomicBoolean closed,
                boolean startedOk, String errorSummary) {
            this.stream = stream;
            this.drainTask = drainTask;
            this.closed = closed;
            this.startedOk = startedOk;
            this.errorSummary = errorSummary;
        }

        static AudioBenchSession disabled() {
            return new AudioBenchSession(null, null, new AtomicBoolean(true), false, "disabled");
        }

        static AudioBenchSession start(String runId, String audioUrl, BlockPos benchPos, long startMillis,
                long totalMillis) {
            try {
                String synced = PlaybackSync.withSync(audioUrl, runId, startMillis, totalMillis);
                HttpAudioStreamHandler.allowUrl(synced, benchPos);
                AudioInputStream stream = new HttpAudioStreamHandler().handle(URI.create(synced).toURL());
                AtomicBoolean closed = new AtomicBoolean(false);
                CompletableFuture<Void> drain = CompletableFuture.runAsync(() -> drainSilentStream(stream, closed));
                return new AudioBenchSession(stream, drain, closed, true, "");
            } catch (Exception e) {
                LOGGER.warn("自动播放Bench音频启动失败: runId={} reason={}", runId, e.toString());
                return new AudioBenchSession(null, null, new AtomicBoolean(true), false,
                        e.getClass().getSimpleName() + ":" + e.getMessage());
            }
        }

        boolean startedOk() {
            return startedOk;
        }

        String errorSummary() {
            return errorSummary;
        }

        @Override
        public void close() {
            closed.set(true);
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                }
            }
            if (drainTask != null) {
                try {
                    drainTask.get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
            HttpAudioStreamHandler.closeModernStreams();
        }
    }

    private record BenchPlacement(BlockPos turntablePos, BlockPos leftSpeakerPos, BlockPos rightSpeakerPos) {
    }

    private static void drainSilentStream(AudioInputStream stream, AtomicBoolean closed) {
        byte[] buffer = new byte[8 * 1024];
        try {
            while (!closed.get()) {
                int n = stream.read(buffer);
                if (n < 0) {
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }
}
