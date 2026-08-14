package com.zhongbai233.net_music_can_play_bili.bili;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnHealthTracker;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnUrlFallbacks;
import com.zhongbai233.net_music_can_play_bili.media.stream.ChunkPrefetchInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4RangeSeekSupport;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4SeekRangeCache;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeClient;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.LifecycleClose;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Owns fMP4 audio byte-range probing, SIDX selection, and CDN range racing. */
final class Fmp4AudioStreamSeeker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AudioStreamProperties.Http PROPERTIES = AudioStreamProperties.http();
    private static final int INIT_PROBE_BYTES = 4 * 1024 * 1024;
    private static final int MOOF_SCAN_BYTES = 2 * 1024 * 1024;
    private static final int SEEK_MAX_ATTEMPTS = 3;
    private static final double CLOSE_FRAGMENT_SECONDS = 15.0D;
    private static final double TARGET_EPSILON_SECONDS = 0.05D;
    private static final long SEEK_PREROLL_BYTES = 256 * 1024L;
    private static final long SEGMENT_BASE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final int RANGE_RACE_MAX_CANDIDATES = PROPERTIES.rangeRaceMaxCandidates();
    private static final long RANGE_RACE_TIMEOUT_MILLIS = PROPERTIES.rangeRaceTimeoutMillis();
    private static final int MAX_SEGMENT_BASE_ENTRIES = PROPERTIES.segmentBaseCacheMaxEntries();
    private static final ExecutorService RANGE_RACE_EXECUTOR = Executors.newFixedThreadPool(
            RANGE_RACE_MAX_CANDIDATES, NetMusicThreadFactory.daemon("bili-audio-range-race"));
    private static final ConcurrentHashMap<String, SegmentBaseInfo> SEGMENT_BASE_BY_URL = new ConcurrentHashMap<>();

    private Fmp4AudioStreamSeeker() {
    }

    static void registerSegmentBase(String audioUrl, long initStart, long initEnd, long indexStart, long indexEnd) {
        if (audioUrl == null || audioUrl.isBlank() || initStart < 0L || initEnd < initStart
                || indexStart < 0L || indexEnd < indexStart) {
            return;
        }
        long now = System.currentTimeMillis();
        cleanupSegmentBaseInfo(now);
        SEGMENT_BASE_BY_URL.put(audioUrl, new SegmentBaseInfo(initStart, initEnd, indexStart, indexEnd, now));
    }

    static void clearSegmentBases() {
        SEGMENT_BASE_BY_URL.clear();
    }

    static StreamStart open(URL url, PlaybackRequest request, float startOffsetSeconds) throws IOException {
        if (request != null && startOffsetSeconds > 1.0f && request.totalMillis() > 0L) {
            StreamStart ranged = tryOpenRangeSeek(url, request, startOffsetSeconds);
            if (ranged != null) {
                return ranged;
            }
        }
        return new StreamStart(openPrefetchWithCdnFallback(url, 0L), startOffsetSeconds);
    }

    private static ChunkPrefetchInputStream openPrefetchWithCdnFallback(URL primary, long startByteOffset)
            throws IOException {
        List<URL> candidates = CdnUrlFallbacks.candidates(primary);
        IOException lastError = null;
        for (int i = 0; i < candidates.size(); i++) {
            URL candidate = candidates.get(i);
            try {
                return new ChunkPrefetchInputStream(candidate, startByteOffset);
            } catch (ChunkPrefetchInputStream.EmptyCdnResponseException e) {
                lastError = e;
                if (i + 1 < candidates.size()) {
                    LOGGER.warn("Audio CDN returned empty body, retrying alternate host {} -> {} offset={}: {}",
                            candidate.getHost(), candidates.get(i + 1).getHost(), startByteOffset, e.getMessage());
                }
            }
        }
        throw lastError != null ? lastError : new IOException("no CDN URL candidates available");
    }

    private static StreamStart tryOpenRangeSeek(URL url, PlaybackRequest request, float targetSeconds) {
        ChunkPrefetchInputStream lastRange = null;
        try {
            long started = System.currentTimeMillis();
            Fmp4RangeSeekSupport.InitSegment init = readInitSegment(url);
            long contentLength = init.contentLength();
            if (contentLength <= 0L) {
                return null;
            }

            StreamStart sidxStart = tryOpenSidxSeek(url, request, init, targetSeconds);
            if (sidxStart != null) {
                LOGGER.debug("音频fMP4 seek 总耗时: mode=sidx cost={}ms target={}s host={}",
                        System.currentTimeMillis() - started, targetSeconds, url.getHost());
                return sidxStart;
            }

            long elapsedMillis = Math.max(0L, request.elapsedMillis());
            long totalMillis = Math.max(1L, request.totalMillis());
            double ratio = Math.max(0.0D, Math.min(0.98D, elapsedMillis / (double) totalMillis));
            long estimatedOffset = Math.min(contentLength - 1L, Math.max(0L, Math.round(contentLength * ratio)));
            long rangeStart = Math.max(init.bytes().length, estimatedOffset - SEEK_PREROLL_BYTES);

            int timescale = init.timescale() > 0 ? init.timescale() : 48000;
            for (int attempt = 0; attempt < SEEK_MAX_ATTEMPTS; attempt++) {
                ChunkPrefetchInputStream range = openPrefetchWithCdnFallback(url, rangeStart);
                lastRange = range;
                try {
                    Fmp4RangeSeekSupport.MoofProbe probe = Fmp4RangeSeekSupport.readMoofProbe(range, targetSeconds,
                            timescale, MOOF_SCAN_BYTES, TARGET_EPSILON_SECONDS, CLOSE_FRAGMENT_SECONDS);
                    if (probe == null) {
                        closeQuietly(range);
                        lastRange = null;
                        long nextStart = Math.min(contentLength - 1L, rangeStart + MOOF_SCAN_BYTES);
                        if (attempt + 1 >= SEEK_MAX_ATTEMPTS || nextStart <= rangeStart) {
                            return null;
                        }
                        rangeStart = nextStart;
                        continue;
                    }

                    byte[] probeBytes = probe.bytes();
                    Fmp4RangeSeekSupport.MoofCandidate candidate = probe.candidate();
                    long absoluteMoofOffset = rangeStart + candidate.offset();
                    if (attempt + 1 < SEEK_MAX_ATTEMPTS
                            && Fmp4RangeSeekSupport.shouldRetry(candidate, targetSeconds,
                                    TARGET_EPSILON_SECONDS, CLOSE_FRAGMENT_SECONDS)) {
                        long nextStart = Fmp4RangeSeekSupport.nextRangeStart(candidate, targetSeconds,
                                totalMillis / 1000.0D, contentLength, absoluteMoofOffset, init.bytes().length,
                                SEEK_PREROLL_BYTES);
                        if (Math.abs(nextStart - rangeStart) > SEEK_PREROLL_BYTES) {
                            closeQuietly(range);
                            lastRange = null;
                            rangeStart = nextStart;
                            continue;
                        }
                    }

                    if (Fmp4RangeSeekSupport.isAfterTargetCandidate(candidate, targetSeconds,
                            TARGET_EPSILON_SECONDS)) {
                        LOGGER.debug(
                                "fMP4 range seek candidate is after target: target={}s fragment={}s byte={} attemptsExhausted=true; falling back to decoded skip",
                                targetSeconds, candidate.fragmentSeconds(), absoluteMoofOffset);
                        return null;
                    }

                    float residualSeconds = Fmp4RangeSeekSupport.residualSeconds(targetSeconds, candidate,
                            totalMillis / 1000.0D, contentLength, absoluteMoofOffset);
                    InputStream tail = new SequenceInputStream(
                            new ByteArrayInputStream(probeBytes, candidate.offset(), probeBytes.length - candidate.offset()),
                            range);
                    lastRange = null;
                    InputStream combined = new SequenceInputStream(new ByteArrayInputStream(init.bytes()), tail);
                    LOGGER.debug(
                            "音频fMP4 RangeSeek: target={}s fragment={}s residual={}s timelineStart={}s byte={} totalBytes={} cost={}ms host={}",
                            targetSeconds, candidate.fragmentSeconds(), residualSeconds,
                            request.startOffsetSeconds(), absoluteMoofOffset, contentLength,
                            System.currentTimeMillis() - started, url.getHost());
                    return new StreamStart(combined, residualSeconds);
                } finally {
                    if (lastRange == range) {
                        closeQuietly(range);
                        lastRange = null;
                    }
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("fMP4 range seek unavailable, falling back to decoded skip: {}", e.getMessage());
            closeQuietly(lastRange);
            return null;
        }
    }

    private static StreamStart tryOpenSidxSeek(URL url, PlaybackRequest request,
            Fmp4RangeSeekSupport.InitSegment init, float targetSeconds) {
        SegmentBaseInfo info = segmentBaseInfo(url.toString());
        if (info == null) {
            return null;
        }
        try {
            byte[] sidxBytes = readRangeBytes(url, info.indexStart(), info.indexEnd());
            Fmp4RangeSeekSupport.SidxIndex sidx = Fmp4RangeSeekSupport.parseSidx(sidxBytes, info.indexStart());
            if (sidx == null || sidx.entries().isEmpty()) {
                return null;
            }
            Fmp4RangeSeekSupport.SidxEntry selected = null;
            for (Fmp4RangeSeekSupport.SidxEntry entry : sidx.entries()) {
                if (entry.timeSeconds() > targetSeconds + TARGET_EPSILON_SECONDS) {
                    break;
                }
                if (entry.startsWithSap()) {
                    selected = entry;
                }
            }
            if (selected == null) {
                for (Fmp4RangeSeekSupport.SidxEntry entry : sidx.entries()) {
                    if (entry.timeSeconds() > targetSeconds + TARGET_EPSILON_SECONDS) {
                        break;
                    }
                    selected = entry;
                }
            }
            if (selected == null) {
                selected = sidx.entries().get(0);
            }
            ChunkPrefetchInputStream range = openPrefetchWithCdnFallback(url, selected.byteStart());
            try {
                int timescale = init.timescale() > 0 ? init.timescale()
                        : (int) Math.min(Integer.MAX_VALUE, sidx.timescale());
                Fmp4RangeSeekSupport.MoofProbe probe = Fmp4RangeSeekSupport.readMoofProbe(range, targetSeconds,
                        timescale > 0 ? timescale : 48000, MOOF_SCAN_BYTES, TARGET_EPSILON_SECONDS,
                        CLOSE_FRAGMENT_SECONDS);
                if (probe == null) {
                    closeQuietly(range);
                    return null;
                }
                byte[] probeBytes = probe.bytes();
                Fmp4RangeSeekSupport.MoofCandidate candidate = probe.candidate();
                if (Fmp4RangeSeekSupport.isAfterTargetCandidate(candidate, targetSeconds, TARGET_EPSILON_SECONDS)) {
                    LOGGER.debug("音频fMP4 SidxSeek 命中目标之后 fragment，回退 Moof RangeSeek: target={}s fragment={}s byte={}",
                            targetSeconds, candidate.fragmentSeconds(), selected.byteStart());
                    closeQuietly(range);
                    return null;
                }
                double fragmentSeconds = !Double.isNaN(candidate.fragmentSeconds())
                        ? candidate.fragmentSeconds()
                        : selected.timeSeconds();
                float residualSeconds = (float) Math.max(0.0D,
                        Math.min(targetSeconds, targetSeconds - fragmentSeconds));
                InputStream tail = new SequenceInputStream(
                        new ByteArrayInputStream(probeBytes, candidate.offset(), probeBytes.length - candidate.offset()),
                        range);
                InputStream combined = new SequenceInputStream(new ByteArrayInputStream(init.bytes()), tail);
                LOGGER.debug(
                        "音频fMP4 SidxSeek: target={}s fragment={}s residual={}s timelineStart={}s byte={} totalBytes={} host={}",
                        targetSeconds, fragmentSeconds, residualSeconds, request.startOffsetSeconds(),
                        selected.byteStart(), init.contentLength(), url.getHost());
                LOGGER.debug("音频fMP4 SidxSeek 选择: target={}s selectedFragment={}s startsWithSap={} byte={}",
                        targetSeconds, fragmentSeconds, selected.startsWithSap(), selected.byteStart());
                return new StreamStart(combined, residualSeconds);
            } catch (IOException | RuntimeException e) {
                closeQuietly(range);
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("fMP4 audio sidx seek unavailable, falling back to range seek: {}", e.getMessage());
            return null;
        }
    }

    private static byte[] readRangeBytes(URL url, long start, long end) throws IOException {
        return readRange(url, start, end).bytes();
    }

    private static RangeBytes readRange(URL url, long start, long end) throws IOException {
        Fmp4SeekRangeCache.CachedRange cached = Fmp4SeekRangeCache.get(url, start, end);
        if (cached != null) {
            return new RangeBytes(cached.bytes(), cached.totalLength(), cached.sourceHost(), cached.sourceUrl());
        }
        List<URL> candidates = CdnUrlFallbacks.candidates(url);
        if (candidates.size() > 1 && RANGE_RACE_MAX_CANDIDATES > 1) {
            RangeBytes raced = readRangeRace(candidates, start, end);
            if (raced != null) {
                Fmp4SeekRangeCache.put(url, start, end, raced.bytes(), raced.totalLength(), raced.host(), raced.url());
                return raced;
            }
        }
        RangeBytes result = readRangeSingle(url, start, end);
        Fmp4SeekRangeCache.put(url, start, end, result.bytes(), result.totalLength(), result.host(), result.url());
        return result;
    }

    private static RangeBytes readRangeRace(List<URL> candidates, long start, long end) throws IOException {
        int count = Math.min(RANGE_RACE_MAX_CANDIDATES, candidates.size());
        CompletableFuture<RangeBytes> first = new CompletableFuture<>();
        List<Future<?>> tasks = new ArrayList<>(count);
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<IOException> lastError = new AtomicReference<>();
        for (int i = 0; i < count; i++) {
            URL candidate = candidates.get(i);
            tasks.add(RANGE_RACE_EXECUTOR.submit(() -> {
                if (first.isDone()) {
                    return;
                }
                try {
                    first.complete(readRangeSingle(candidate, start, end, false));
                } catch (IOException e) {
                    lastError.set(e);
                    if (failures.incrementAndGet() >= count) {
                        first.completeExceptionally(lastError.get());
                    }
                }
            }));
        }
        try {
            RangeBytes winner = first.get(RANGE_RACE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            BiliCdnSelector.recordSuccess(winner.url());
            LOGGER.debug("音频小范围 CDN 赛马完成: range={}-{} bytes={} total={} host={}",
                    start, end, winner.bytes().length, winner.totalLength(), winner.host());
            return winner;
        } catch (TimeoutException e) {
            LOGGER.debug("音频小范围 CDN 赛马超时，回退串行读取: range={}-{} timeout={}ms candidates={}",
                    start, end, RANGE_RACE_TIMEOUT_MILLIS, count);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while racing audio range", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("audio range race failed", cause);
        } finally {
            tasks.forEach(task -> task.cancel(true));
        }
    }

    private static RangeBytes readRangeSingle(URL url, long start, long end) throws IOException {
        return readRangeSingle(url, start, end, true);
    }

    private static RangeBytes readRangeSingle(URL url, long start, long end, boolean persistCdnSuccess)
            throws IOException {
        HttpRangeClient client = new HttpRangeClient();
        long started = System.currentTimeMillis();
        boolean failureRecorded = false;
        URL actualUrl = url;
        try (HttpRangeClient.CdnResponse response = client.getRangeDirect(url, start, end)) {
            actualUrl = response.sourceUrl();
            int status = response.statusCode();
            if (status != 206 && (status != 200 || start != 0L)) {
                failureRecorded = isRetryableCdnStatus(status);
                throw new IOException("HTTP " + status + " while reading fMP4 range");
            }
            if (status == 206 && response.rangeStart() >= 0L && response.rangeStart() != start) {
                throw new IOException("fMP4 Content-Range starts at " + response.rangeStart()
                        + " instead of requested " + start);
            }
            long maxBytes = Math.max(1L, end - start + 1L);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(maxBytes, 1024 * 1024L));
            byte[] buffer = new byte[64 * 1024];
            long remaining = maxBytes;
            while (remaining > 0L) {
                int n = response.body().read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length == 0) {
                CdnHealthTracker.recordFailure(actualUrl, CdnHealthTracker.FailureKind.EMPTY);
                failureRecorded = true;
                throw new IOException("empty fMP4 range response from " + actualUrl.getHost());
            }
            long declaredLength = response.rangeStart() >= 0L && response.rangeEndInclusive() >= response.rangeStart()
                    ? response.rangeEndInclusive() - response.rangeStart() + 1L
                    : Math.min(maxBytes, response.contentLength());
            if (declaredLength > 0L && bytes.length < declaredLength) {
                CdnHealthTracker.recordFailure(actualUrl, CdnHealthTracker.FailureKind.SHORT_READ);
                failureRecorded = true;
                throw new IOException("short fMP4 range response from " + actualUrl.getHost() + ": expected="
                        + declaredLength + " actual=" + bytes.length);
            }
            CdnHealthTracker.recordSuccess(actualUrl, System.currentTimeMillis() - started, bytes.length);
            if (persistCdnSuccess) {
                BiliCdnSelector.recordSuccess(actualUrl.toString());
            }
            long totalLength = response.totalLength() > 0L ? response.totalLength() : response.contentLength();
            return new RangeBytes(bytes, totalLength, actualUrl.getHost(), actualUrl.toString());
        } catch (IOException e) {
            if (!failureRecorded) {
                CdnHealthTracker.recordFailure(actualUrl, CdnHealthTracker.FailureKind.IO);
            }
            throw e;
        }
    }

    private static boolean isRetryableCdnStatus(int status) {
        return status == 403 || status == 404 || status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private static Fmp4RangeSeekSupport.InitSegment readInitSegment(URL url) throws IOException {
        SegmentBaseInfo info = segmentBaseInfo(url.toString());
        if (info != null) {
            RangeBytes initRange = readRange(url, info.initStart(), info.initEnd());
            Fmp4RangeSeekSupport.InitSegment init = Fmp4RangeSeekSupport.extractInitSegment(initRange.bytes(),
                    initRange.totalLength(), (moovPayload, moov) -> moov.timescale);
            if (init != null && init.contentLength() > 0L) {
                return init;
            }
            LOGGER.debug("fMP4 audio segment_base init unusable, falling back to probe: initRange={}-{} host={}",
                    info.initStart(), info.initEnd(), url.getHost());
        }
        HttpRangeClient client = new HttpRangeClient();
        try (HttpRangeClient.CdnResponse response = client.getRange(url, 0L, INIT_PROBE_BYTES - 1L)) {
            int status = response.statusCode();
            if (status != 206 && status != 200) {
                throw new IOException("HTTP " + status + " while probing fMP4 init segment");
            }
            long contentLength = response.totalLength() > 0L ? response.totalLength() : response.contentLength();
            ByteArrayOutputStream prefix = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            Fmp4RangeSeekSupport.InitSegment init = null;
            while (prefix.size() < INIT_PROBE_BYTES) {
                int request = Math.min(buffer.length, INIT_PROBE_BYTES - prefix.size());
                int n = response.body().read(buffer, 0, request);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }
                prefix.write(buffer, 0, n);
                init = Fmp4RangeSeekSupport.extractInitSegment(prefix.toByteArray(), contentLength,
                        (moovPayload, moov) -> moov.timescale);
                if (init != null) {
                    break;
                }
            }
            if (init == null) {
                throw new IOException("unable to read complete fMP4 init segment");
            }
            return init;
        }
    }

    private static SegmentBaseInfo segmentBaseInfo(String url) {
        SegmentBaseInfo info = SEGMENT_BASE_BY_URL.get(url);
        if (info == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - info.createdAtMillis() > SEGMENT_BASE_TTL_MILLIS) {
            SEGMENT_BASE_BY_URL.remove(url, info);
            return null;
        }
        return info;
    }

    private static void cleanupSegmentBaseInfo(long now) {
        if (SEGMENT_BASE_BY_URL.isEmpty()) {
            return;
        }
        SEGMENT_BASE_BY_URL.entrySet().removeIf(
                entry -> now - entry.getValue().createdAtMillis() > SEGMENT_BASE_TTL_MILLIS);
        int maxEntries = Math.max(1, MAX_SEGMENT_BASE_ENTRIES);
        while (SEGMENT_BASE_BY_URL.size() > maxEntries) {
            String oldestKey = null;
            long oldestCreatedAt = Long.MAX_VALUE;
            for (var entry : SEGMENT_BASE_BY_URL.entrySet()) {
                long createdAt = entry.getValue().createdAtMillis();
                if (createdAt < oldestCreatedAt) {
                    oldestCreatedAt = createdAt;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) {
                return;
            }
            SEGMENT_BASE_BY_URL.remove(oldestKey);
        }
    }

    private static void closeQuietly(InputStream stream) {
        LifecycleClose.closeQuietly(stream);
    }

    record StreamStart(InputStream stream, float startOffsetSeconds) {
    }

    private record RangeBytes(byte[] bytes, long totalLength, String host, String url) {
    }

    private record SegmentBaseInfo(long initStart, long initEnd, long indexStart, long indexEnd,
            long createdAtMillis) {
    }
}
