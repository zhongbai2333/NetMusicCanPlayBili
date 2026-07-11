package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure HTTP range/header helpers shared by media stream probes. */
public final class HttpRangeHeaders {
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)",
            Pattern.CASE_INSENSITIVE);

    private HttpRangeHeaders() {
    }

    public static Optional<Long> parseContentRangeTotal(String value) {
        ContentRange range = parseContentRange(value);
        return range.hasKnownTotalLength() ? Optional.of(range.totalLength()) : Optional.empty();
    }

    public static ContentRange parseContentRange(String value) {
        if (value == null) {
            return ContentRange.unknown();
        }
        Matcher matcher = CONTENT_RANGE.matcher(value.trim());
        if (!matcher.matches()) {
            return ContentRange.unknown();
        }
        try {
            long start = Long.parseLong(matcher.group(1));
            long endInclusive = Long.parseLong(matcher.group(2));
            long totalLength = "*".equals(matcher.group(3)) ? -1L : Long.parseLong(matcher.group(3));
            if (endInclusive < start) {
                return ContentRange.unknown();
            }
            return new ContentRange(start, endInclusive, totalLength);
        } catch (NumberFormatException ignored) {
            return ContentRange.unknown();
        }
    }

    public static boolean isRedirectStatus(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    public static HttpRequest.Builder rangeRequest(URL url, long rangeOffset, boolean probe, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(timeout)
                .GET()
                .header("Range", probe ? "bytes=0-0" : "bytes=%d-".formatted(Math.max(0L, rangeOffset)));
    }

    public static HttpRequest.Builder boundedRangeRequest(URL url, long start, long endInclusive, Duration timeout) {
        if (start < 0L || endInclusive < start) {
            throw new IllegalArgumentException("invalid range: " + start + "-" + endInclusive);
        }
        return HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(timeout)
                .GET()
                .header("Range", "bytes=%d-%d".formatted(start, endInclusive));
    }

    public record ContentRange(long start, long endInclusive, long totalLength) {
        public static ContentRange unknown() {
            return new ContentRange(-1L, -1L, -1L);
        }

        public boolean isKnown() {
            return start >= 0L && endInclusive >= start;
        }

        public boolean hasKnownTotalLength() {
            return totalLength >= 0L;
        }
    }
}
