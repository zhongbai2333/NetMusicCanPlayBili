package com.zhongbai233.net_music_can_play_bili.bili.stream;

import com.zhongbai233.net_music_can_play_bili.bili.BiliWbiSigner;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Optional;

public final class HttpRangeClient {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    public CdnResponse get(URL url) throws IOException {
        return send(url, null, null);
    }

    public CdnResponse getRange(URL url, long start, long endInclusive) throws IOException {
        if (start < 0 || endInclusive < start) {
            throw new IllegalArgumentException("invalid range: " + start + "-" + endInclusive);
        }
        return send(url, start, endInclusive);
    }

    private CdnResponse send(URL url, Long start, Long endInclusive) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.toString()))
                .header("User-Agent", USER_AGENT)
                .header("Referer", refererFor(url))
                .header("Origin", originFor(url))
                .header("Accept", "*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET();
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

    private static String refererFor(URL url) {
        return isBiliHost(url) ? "https://www.bilibili.com/" : "https://" + safeHost(url) + "/";
    }

    private static String originFor(URL url) {
        return isBiliHost(url) ? "https://www.bilibili.com" : "https://" + safeHost(url);
    }

    private static boolean isBiliHost(URL url) {
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.contains("bilibili") || lower.contains("bilivideo")
                || lower.contains("hdslb") || lower.contains("mcdn");
    }

    private static String safeHost(URL url) {
        String host = url.getHost();
        return host == null || host.isBlank() ? "localhost" : host;
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
