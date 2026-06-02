package com.zhongbai233.net_music_can_play_bili.bili.stream;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;

public final class ChunkPrefetchInputStream extends InputStream {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int COPY_BUFFER_SIZE = 256 * 1024;

    public static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024;
    public static final int DEFAULT_LOW_WATER = 8 * 1024 * 1024;
    public static final int DEFAULT_HIGH_WATER = 32 * 1024 * 1024;

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
        LOGGER.debug("HTTP chunk prefetch init: chunk={} lowWater={} highWater={} startOffset={} urlHost={}",
                this.chunkSize, this.lowWater, this.highWater, this.startByteOffset, url.getHost());
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

    private void downloadLoop() {
        try {
            downloadWithRangeProbe();
            spool.complete();
            LOGGER.debug("HTTP chunk prefetch complete: mode={} cached={} total={}",
                    mode, spool.cachedLength(), totalLength);
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
            try (HttpRangeClient.CdnResponse response = client.getRange(url, nextStart, end)) {
                int status = response.statusCode();
                if (status == 416) {
                    mode = mode == Mode.UNKNOWN ? Mode.RANGE : mode;
                    return;
                }
                if (status == 206) {
                    if (mode != Mode.RANGE) {
                        mode = Mode.RANGE;
                        LOGGER.debug("HTTP range prefetch: contentLength={} total={}",
                                response.contentLength(), response.totalLength());
                    }
                    if (response.totalLength() > 0) {
                        totalLength = response.totalLength();
                    }
                    long requestedStart = nextStart;
                    long requestedLength = end - requestedStart + 1;
                    long received = copyResponse(response.body(), response.contentLength(), requestedLength);
                    LOGGER.trace("HTTP chunk received: range={}-{} received={} cached={}",
                            nextStart, end, received, spool.cachedLength());
                    nextStart += received;
                    if (received == 0) {
                        return;
                    }
                    if (totalLength > 0 && nextStart >= totalLength) {
                        return;
                    }
                    long expectedLength = response.contentLength() > 0 ? response.contentLength() : requestedLength;
                    if (received < expectedLength) {
                        if (totalLength > 0 && nextStart < totalLength) {
                            throw new IOException("CDN range chunk ended early at " + nextStart + " of " + totalLength);
                        }
                        return;
                    }
                    continue;
                }
                if (status == 200 && nextStart == 0L) {
                    mode = Mode.SEQUENTIAL;
                    totalLength = response.contentLength();
                    LOGGER.debug("HTTP prefetch mode: sequential GET, contentLength={}", totalLength);
                    copyResponse(response.body(), -1L, Long.MAX_VALUE);
                    return;
                }
                throw new IOException("HTTP " + status + " from CDN range request");
            }
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
                copyResponse(response.body(), response.contentLength(), Long.MAX_VALUE);
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
