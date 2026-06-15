package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class ChunkPrefetchInputStream extends InputStream {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int COPY_BUFFER_SIZE = Integer.getInteger(
        "bili.media.prefetch.copy_buffer_bytes", 256 * 1024);

    public static final int DEFAULT_CHUNK_SIZE = Integer.getInteger(
        "bili.media.prefetch.chunk_bytes", 4 * 1024 * 1024);
    public static final int DEFAULT_LOW_WATER = Integer.getInteger(
        "bili.media.prefetch.low_water_bytes", 8 * 1024 * 1024);
    public static final int DEFAULT_HIGH_WATER = Integer.getInteger(
        "bili.media.prefetch.high_water_bytes", 32 * 1024 * 1024);
    private static final long STARTUP_PREBUFFER_BYTES = Math.max(0L, Long.getLong(
        "bili.media.prefetch.startup_bytes", 6L * 1024L * 1024L));
    private static final long SEEK_STARTUP_PREBUFFER_BYTES = Math.max(0L, Long.getLong(
        "bili.media.prefetch.seek_startup_bytes", 2L * 1024L * 1024L));
    private static final long STARTUP_PREBUFFER_MAX_WAIT_MILLIS = Math.max(0L, Long.getLong(
        "bili.media.prefetch.startup_max_wait_ms", 8_000L));
    private static final int PER_HOST_ATTEMPTS = Math.max(1, Integer.getInteger(
        "bili.media.prefetch.per_host_attempts", 2));
    private static final long RETRY_BACKOFF_MILLIS = Math.max(0L, Long.getLong(
        "bili.media.prefetch.retry_backoff_ms", 350L));

    private final URL url;
    private final HttpRangeClient client;
    private final TempFileByteSpool spool;
    private final Thread downloader;
    private final int chunkSize;
    private final int lowWater;
    private final int highWater;
    private final Object demandLock = new Object();
    private final AtomicReference<InputStream> activeBody = new AtomicReference<>();

    private volatile boolean closed;
    private volatile long readPosition;
    private volatile boolean startupPrebufferDone;
    private volatile Mode mode = Mode.UNKNOWN;
    private volatile long totalLength = -1L;
    private final long startByteOffset;

    public ChunkPrefetchInputStream(URL url) throws IOException {
        this(url, 0L);
    }

    /** @param startByteOffset 首次 Range 请求的起始字节偏移；0 表示从头下载 */
    public ChunkPrefetchInputStream(URL url, long startByteOffset) throws IOException {
        this(url, new HttpRangeClient(), DEFAULT_CHUNK_SIZE, DEFAULT_LOW_WATER, DEFAULT_HIGH_WATER,
                Math.max(0L, startByteOffset));
    }

    public ChunkPrefetchInputStream(
            URL url,
            HttpRangeClient client,
            int chunkSize,
            int lowWater,
            int highWater) throws IOException {
        this(url, client, chunkSize, lowWater, highWater, 0L);
    }

    public ChunkPrefetchInputStream(
            URL url,
            HttpRangeClient client,
            int chunkSize,
            int lowWater,
            int highWater,
            long startByteOffset) throws IOException {
        this.url = url;
        this.client = client;
        this.chunkSize = Math.max(1024 * 1024, chunkSize);
        this.lowWater = Math.max(this.chunkSize, lowWater);
        this.highWater = Math.max(this.lowWater, highWater);
        this.startByteOffset = Math.max(0L, startByteOffset);
        this.spool = new TempFileByteSpool("http-prefetch-");
        this.downloader = new Thread(this::downloadLoop, "HttpPrefetch");
        this.downloader.setDaemon(true);
        this.downloader.start();
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException("buffer");
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        if (closed) {
            return -1;
        }

        awaitStartupPrebufferIfNeeded();
        if (closed) {
            return -1;
        }

        int n = spool.read(readPosition, b, off, len);
        if (n > 0) {
            readPosition += n;
            notifyDemand();
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        notifyDemand();
        closeActiveBody();
        downloader.interrupt();
        spool.close();
    }

    private void awaitStartupPrebufferIfNeeded() throws IOException {
        if (startupPrebufferDone || readPosition > 0L) {
            return;
        }
        startupPrebufferDone = true;
        long target = startByteOffset > 0L ? SEEK_STARTUP_PREBUFFER_BYTES : STARTUP_PREBUFFER_BYTES;
        if (target <= 0L) {
            return;
        }
        long before = System.currentTimeMillis();
        long cached = spool.waitUntilCached(target, STARTUP_PREBUFFER_MAX_WAIT_MILLIS);
        long waited = System.currentTimeMillis() - before;
        if (cached > 0L) {
            LOGGER.debug("HTTP startup prebuffer ready: cached={} target={} waited={}ms offset={} host={}",
                    cached, target, waited, startByteOffset, safeHost(url));
            return;
        }
        LOGGER.debug("HTTP startup prebuffer empty after wait: target={} waited={}ms offset={} host={}",
                target, waited, startByteOffset, safeHost(url));
    }

    private void downloadLoop() {
        try {
            downloadWithRangeProbe();
            spool.complete();
        } catch (IOException e) {
            if (!closed) {
                LOGGER.warn("HTTP chunk prefetch failed at cached={} read={} mode={}: {}",
                        spool.cachedLength(), readPosition, mode, e.getMessage());
                spool.fail(e);
            }
        } finally {
            spool.complete();
        }
    }

    private void downloadWithRangeProbe() throws IOException {
        // 非 B站 CDN 的服务器优先尝试全量 GET（单 TCP 连接），
        // 避免跨域/跨境 QoS 对多连接限速导致的吞吐量骤降。
        if (!isBiliCdnHost() && startByteOffset == 0L && tryFullDownload()) {
            return;
        }

        long nextStart = startByteOffset;
        while (!closed && !spool.isComplete()) {
            if (mode == Mode.RANGE) {
                waitUntilCacheNeedsData();
            }
            if (closed) {
                return;
            }

            long end = safeRangeEnd(nextStart);
            nextStart = downloadChunkWithCdnFallback(nextStart, end);
        }
    }

    private long downloadChunkWithCdnFallback(long nextStart, long end) throws IOException {
        List<URL> candidates = CdnUrlFallbacks.candidates(url);
        IOException lastError = null;
        for (int i = 0; i < candidates.size(); i++) {
            URL candidate = candidates.get(i);
            for (int attempt = 1; attempt <= PER_HOST_ATTEMPTS; attempt++) {
                try {
                    return downloadChunk(candidate, nextStart, end);
                } catch (EmptyCdnResponseException e) {
                    lastError = e;
                    if (attempt < PER_HOST_ATTEMPTS) {
                        LOGGER.warn("CDN returned empty audio chunk, retrying same host {} attempt={}/{} range={}-{}: {}",
                                safeHost(candidate), attempt + 1, PER_HOST_ATTEMPTS, nextStart, end, e.getMessage());
                        backoffBeforeRetry();
                        continue;
                    }
                    if (i + 1 < candidates.size()) {
                        LOGGER.warn("CDN returned empty audio chunk, retrying alternate host {} -> {} range={}-{}: {}",
                                safeHost(candidate), safeHost(candidates.get(i + 1)), nextStart, end, e.getMessage());
                    }
                } catch (IOException e) {
                    lastError = e;
                    if (attempt < PER_HOST_ATTEMPTS) {
                        LOGGER.warn("CDN audio chunk failed, retrying same host {} attempt={}/{} range={}-{}: {}",
                                safeHost(candidate), attempt + 1, PER_HOST_ATTEMPTS, nextStart, end, e.getMessage());
                        backoffBeforeRetry();
                        continue;
                    }
                    if (i + 1 < candidates.size()) {
                        LOGGER.warn("CDN audio chunk failed, retrying alternate host {} -> {} range={}-{}: {}",
                                safeHost(candidate), safeHost(candidates.get(i + 1)), nextStart, end, e.getMessage());
                    }
                }
                break;
            }
        }
        throw lastError != null ? lastError : new IOException("no CDN URL candidates available");
    }

    private static void backoffBeforeRetry() throws IOException {
        if (RETRY_BACKOFF_MILLIS <= 0L) {
            return;
        }
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("prefetch retry interrupted", e);
        }
    }

    private long downloadChunk(URL requestUrl, long nextStart, long end) throws IOException {
        long started = System.currentTimeMillis();
        boolean outcomeRecorded = false;
        try (HttpRangeClient.CdnResponse response = client.getRange(requestUrl, nextStart, end)) {
            int status = response.statusCode();
            if (status == 416) {
                mode = mode == Mode.UNKNOWN ? Mode.RANGE : mode;
                spool.complete();
                return nextStart;
            }
            if (status == 206) {
                if (mode != Mode.RANGE) {
                    mode = Mode.RANGE;
                }
                if (response.totalLength() > 0) {
                    totalLength = response.totalLength();
                }
                long requestedLength = end - nextStart + 1;
                long received = copyResponse(response.body(), response.contentLength(), requestedLength);
                LOGGER.trace("HTTP chunk received: range={}-{} received={} cached={} host={}",
                        nextStart, end, received, spool.cachedLength(), safeHost(requestUrl));
                if (received == 0) {
                    CdnHealthTracker.recordFailure(requestUrl, CdnHealthTracker.FailureKind.EMPTY);
                    outcomeRecorded = true;
                    throw new EmptyCdnResponseException("0 bytes for range " + nextStart + "-" + end
                            + " host=" + safeHost(requestUrl));
                }
                long newNextStart = nextStart + received;
                CdnHealthTracker.recordSuccess(requestUrl, System.currentTimeMillis() - started, received);
                outcomeRecorded = true;
                if (totalLength > 0 && newNextStart >= totalLength) {
                    spool.complete();
                    return newNextStart;
                }
                long expectedLength = response.contentLength() > 0 ? response.contentLength() : requestedLength;
                if (received < expectedLength) {
                    if (totalLength > 0 && newNextStart < totalLength) {
                        CdnHealthTracker.recordFailure(requestUrl, CdnHealthTracker.FailureKind.SHORT_READ);
                        outcomeRecorded = true;
                        throw new IOException("CDN range chunk ended early at " + newNextStart + " of " + totalLength);
                    }
                    spool.complete();
                }
                return newNextStart;
            }
            if (status == 200 && nextStart == 0L) {
                mode = Mode.SEQUENTIAL;
                totalLength = response.contentLength();
                LOGGER.debug("HTTP prefetch mode: sequential GET, contentLength={} host={}", totalLength,
                        safeHost(requestUrl));
                long received = copyResponse(response.body(), -1L, Long.MAX_VALUE);
                if (received == 0L) {
                    CdnHealthTracker.recordFailure(requestUrl, CdnHealthTracker.FailureKind.EMPTY);
                    outcomeRecorded = true;
                    throw new EmptyCdnResponseException("0 bytes for sequential GET host=" + safeHost(requestUrl));
                }
                CdnHealthTracker.recordSuccess(requestUrl, System.currentTimeMillis() - started, received);
                outcomeRecorded = true;
                spool.complete();
                return received;
            }
            if (status == 403 || status == 404 || status == 408 || status == 425 || status == 429 || status >= 500) {
                CdnHealthTracker.recordFailure(requestUrl, CdnHealthTracker.FailureKind.HTTP_RETRYABLE);
                outcomeRecorded = true;
            }
            throw new IOException("HTTP " + status + " from CDN range request host=" + safeHost(requestUrl)
                    + " range=" + nextStart + "-" + end + " offset=" + startByteOffset);
        } catch (IOException e) {
            if (!outcomeRecorded) {
                CdnHealthTracker.recordFailure(requestUrl, CdnHealthTracker.FailureKind.IO);
            }
            throw e;
        }
    }

    private long copyResponse(InputStream body, long contentLength, long maxBytes) throws IOException {
        activeBody.set(body);
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long copied = 0L;
        try {
            while (!closed && copied < maxBytes) {
                int toRead = (int) Math.min(buffer.length, maxBytes - copied);
                int n = body.read(buffer, 0, toRead);
                if (n < 0) {
                    break;
                }
                spool.write(buffer, 0, n);
                copied += n;
            }
        } finally {
            activeBody.compareAndSet(body, null);
        }
        if (!closed && contentLength >= 0 && copied < contentLength) {
            throw new IOException("CDN response ended early: expected " + contentLength + ", got " + copied);
        }
        return copied;
    }

    public static final class EmptyCdnResponseException extends IOException {
        public EmptyCdnResponseException(String message) {
            super(message);
        }
    }

    private static String safeHost(URL url) {
        String host = url != null ? url.getHost() : null;
        return host == null || host.isBlank() ? "unknown" : host;
    }

    private long safeRangeEnd(long start) {
        long end = start + chunkSize - 1L;
        if (end < start) {
            return Long.MAX_VALUE;
        }
        return end;
    }

    private void waitUntilCacheNeedsData() throws IOException {
        synchronized (demandLock) {
            while (!closed && !spool.isComplete() && spool.cachedLength() - readPosition >= highWater) {
                try {
                    demandLock.wait(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("prefetch downloader interrupted", e);
                }
            }
            if (!closed && spool.cachedLength() - readPosition <= lowWater) {
                LOGGER.trace("HTTP cache low: read={} cached={} ahead={} mode={}",
                        readPosition, spool.cachedLength(), spool.cachedLength() - readPosition, mode);
            }
        }
    }

    private void notifyDemand() {
        synchronized (demandLock) {
            demandLock.notifyAll();
        }
    }

    private boolean isBiliCdnHost() {
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("bilibili") || lower.contains("bilivideo")
                || lower.contains("hdslb") || lower.contains("mcdn");
    }

    private boolean tryFullDownload() throws IOException {
        try (HttpRangeClient.CdnResponse response = client.get(url)) {
            int status = response.statusCode();
            if (status == 200) {
                mode = Mode.SEQUENTIAL;
                totalLength = response.contentLength();
                LOGGER.debug("HTTP prefetch mode: full GET, contentLength={}", totalLength);
                long received = copyResponse(response.body(), response.contentLength(), Long.MAX_VALUE);
                if (received == 0L) {
                    throw new EmptyCdnResponseException("0 bytes for full GET host=" + safeHost(url));
                }
                return true;
            }
            return false;
        } catch (IOException e) {
            LOGGER.debug("HTTP full GET not supported ({}), falling back to Range", e.getMessage());
            return false;
        }
    }

    private void closeActiveBody() {
        InputStream body = activeBody.getAndSet(null);
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
        }
    }

    private enum Mode {
        UNKNOWN,
        RANGE,
        SEQUENTIAL
    }
}
