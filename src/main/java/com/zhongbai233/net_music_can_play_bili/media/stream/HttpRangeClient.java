package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliRequestHeaders;
import com.zhongbai233.net_music_can_play_bili.bili.BiliWbiSigner;
import com.zhongbai233.net_music_can_play_bili.bili.BiliCdnSelector;
import com.zhongbai233.net_music_can_play_bili.bili.PlaybackSync;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public final class HttpRangeClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_REDIRECTS = 5;
    private static final long REQUEST_TIMEOUT_SECONDS = Math.max(5L, Long.getLong(
            "bili.media.http.request_timeout_seconds", 30L));

    public CdnResponse get(URL url) throws IOException {
        return send(url, null, null);
    }

    public CdnResponse getRange(URL url, long start, long endInclusive) throws IOException {
        if (start < 0 || endInclusive < start) {
            throw new IllegalArgumentException("invalid range: " + start + "-" + endInclusive);
        }
        return send(url, start, endInclusive);
    }

    /** 只请求指定 URL，不走等价 CDN fallback；供已自行做 CDN race 的调用方使用。 */
    public CdnResponse getRangeDirect(URL url, long start, long endInclusive) throws IOException {
        return getRangeDirect(url, start, endInclusive, true);
    }

    public CdnResponse getRangeDirect(URL url, long start, long endInclusive, boolean persistCdnSuccess)
            throws IOException {
        if (start < 0 || endInclusive < start) {
            throw new IllegalArgumentException("invalid range: " + start + "-" + endInclusive);
        }
        long started = System.currentTimeMillis();
        try {
            CdnResponse response = send(url, start, endInclusive, 0);
            BiliRequestHeaders.recordBiliCdnResponse(url, response.statusCode());
            if (response.statusCode() == 200 || response.statusCode() == 206) {
                CdnHealthTracker.recordSuccess(url, System.currentTimeMillis() - started,
                        Math.max(0L, response.contentLength()));
                if (persistCdnSuccess) {
                    BiliCdnSelector.recordSuccess(url.toString());
                }
            } else if (isRetryableStatus(response.statusCode())) {
                CdnHealthTracker.recordFailure(url, CdnHealthTracker.FailureKind.HTTP_RETRYABLE);
            }
            return response;
        } catch (IOException e) {
            CdnHealthTracker.recordFailure(url, CdnHealthTracker.FailureKind.IO);
            throw e;
        }
    }

    private CdnResponse send(URL url, Long start, Long endInclusive) throws IOException {
        List<URL> candidates = CdnUrlFallbacks.candidates(url);
        IOException lastError = null;
        for (int i = 0; i < candidates.size(); i++) {
            URL candidate = candidates.get(i);
            try {
                long started = System.currentTimeMillis();
                CdnResponse response = send(candidate, start, endInclusive, 0);
                BiliRequestHeaders.recordBiliCdnResponse(candidate, response.statusCode());
                if (isRetryableStatus(response.statusCode()) && i + 1 < candidates.size()) {
                    CdnHealthTracker.recordFailure(candidate, CdnHealthTracker.FailureKind.HTTP_RETRYABLE);
                    response.close();
                    LOGGER.warn("CDN range request returned HTTP {}, retrying alternate host {} -> {} range={}-{}{}",
                            response.statusCode(), safeHost(candidate), safeHost(candidates.get(i + 1)),
                            start != null ? start : 0L, endInclusive != null ? endInclusive : -1L,
                            bili403Hint(response.statusCode(), candidate));
                    continue;
                }
                if (response.statusCode() == 200 || response.statusCode() == 206) {
                    CdnHealthTracker.recordSuccess(candidate, System.currentTimeMillis() - started,
                            Math.max(0L, response.contentLength()));
                    BiliCdnSelector.recordSuccess(candidate.toString());
                }
                return response;
            } catch (IOException e) {
                lastError = e;
                CdnHealthTracker.recordFailure(candidate, CdnHealthTracker.FailureKind.IO);
                if (i + 1 >= candidates.size()) {
                    break;
                }
                LOGGER.warn("CDN range request failed on host {}, retrying alternate host {} range={}-{}: {}",
                        safeHost(candidate), safeHost(candidates.get(i + 1)),
                        start != null ? start : 0L, endInclusive != null ? endInclusive : -1L, e.getMessage());
            }
        }
        throw lastError != null ? lastError : new IOException("no CDN URL candidates available");
    }

    private CdnResponse send(URL url, Long start, Long endInclusive, int redirects) throws IOException {
        URL requestUrl = PlaybackSync.strip(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(requestUrl.toString()))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .GET();
        BiliRequestHeaders.applyBiliCdnHeaders(builder, requestUrl);
        if (start != null && endInclusive != null) {
            builder.header("Range", "bytes=" + start + "-" + endInclusive);
        }

        HttpResponse<InputStream> response;
        try {
            response = BiliWbiSigner.HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("CDN request interrupted", e);
        }

        if (isRedirect(response.statusCode())) {
            Optional<String> location = response.headers().firstValue("Location");
            response.body().close();
            if (location.isEmpty()) {
                throw new IOException("HTTP " + response.statusCode() + " redirect without Location");
            }
            if (redirects >= MAX_REDIRECTS) {
                throw new IOException("too many HTTP redirects");
            }
            URL redirected = URI.create(requestUrl.toString()).resolve(location.get()).toURL();
            return send(redirected, start, endInclusive, redirects + 1);
        }

        long contentLength = parseLong(response.headers().firstValue("Content-Length"));
        RangeInfo contentRange = parseContentRange(response.headers().firstValue("Content-Range"));
        return new CdnResponse(
                response.statusCode(),
                response.body(),
                contentLength,
                contentRange.start(),
                contentRange.endInclusive(),
                contentRange.totalLength(),
                start != null);
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 403 || statusCode == 404 || statusCode == 408 || statusCode == 425
                || statusCode == 429 || statusCode >= 500;
    }

    private static String safeHost(URL url) {
        return BiliRequestHeaders.safeHost(url);
    }

    private static String bili403Hint(int statusCode, URL url) {
        if (statusCode != 403 || !BiliRequestHeaders.isBiliHost(url)) {
            return "";
        }
        return "; possible Bilibili DASH URL expiry or anti-hotlink check, refresh playurl if all CDN candidates fail";
    }

    private static long parseLong(Optional<String> value) {
        if (value.isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(value.get().trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static RangeInfo parseContentRange(Optional<String> value) {
        if (value.isEmpty()) {
            return RangeInfo.unknown();
        }
        String range = value.get().trim();
        if (!range.startsWith("bytes ")) {
            return RangeInfo.unknown();
        }
        int dash = range.indexOf('-');
        int slash = range.indexOf('/');
        if (dash < 6 || slash <= dash) {
            return RangeInfo.unknown();
        }
        try {
            long start = Long.parseLong(range.substring(6, dash));
            long end = Long.parseLong(range.substring(dash + 1, slash));
            long total = "*".equals(range.substring(slash + 1)) ? -1L : Long.parseLong(range.substring(slash + 1));
            return new RangeInfo(start, end, total);
        } catch (NumberFormatException ignored) {
            return RangeInfo.unknown();
        }
    }

    public record CdnResponse(
            int statusCode,
            InputStream body,
            long contentLength,
            long rangeStart,
            long rangeEndInclusive,
            long totalLength,
            boolean rangeRequested) implements AutoCloseable {

        public boolean isPartialContent() {
            return statusCode == 206;
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    private record RangeInfo(long start, long endInclusive, long totalLength) {
        static RangeInfo unknown() {
            return new RangeInfo(-1L, -1L, -1L);
        }
    }
}
