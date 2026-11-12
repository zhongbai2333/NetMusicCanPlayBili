package com.zhongbai233.net_music_can_play_bili.bili;

import java.net.URI;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Validates every hop before sending, with one total deadline and a finite redirect budget. */
final class BiliShortLinkExpansion {
    private BiliShortLinkExpansion() { }
    record Response(int status, String location) { }
    @FunctionalInterface interface Exchange { Response get(URI uri, Duration timeout) throws Exception; }
    static URI expand(URI initial, Duration budget, LongSupplier clock, Exchange exchange) throws Exception {
        long started = clock.getAsLong();
        long allowedNanos = budget.toNanos();
        URI current = initial;
        for (int hops = 0; hops <= 5; hops++) {
            if (!trusted(current)) return null;
            long remaining = allowedNanos - Math.max(0L, clock.getAsLong() - started);
            if (remaining <= 0L) return null;
            Response response = exchange.get(current, Duration.ofNanos(remaining));
            int status = response.status();
            if (status >= 200 && status < 300) return current;
            if (!(status == 301 || status == 302 || status == 303 || status == 307 || status == 308)
                    || response.location() == null || response.location().isBlank() || hops == 5) return null;
            current = current.resolve(response.location());
        }
        return null;
    }
    static boolean trusted(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getPort() != -1 && uri.getPort() != 443 || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        return host.equals("b23.tv") || host.equals("bili2233.cn")
                || host.equals("bilibili.com") || host.endsWith(".bilibili.com");
    }
}
