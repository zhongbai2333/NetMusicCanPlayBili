package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRangeHeadersTest {
    @Test
    void parsesContentRangeTotalsAndRedirectStatuses() {
        assertEquals(123456L, HttpRangeHeaders.parseContentRangeTotal("bytes 0-0/123456").orElseThrow());
        assertTrue(HttpRangeHeaders.parseContentRangeTotal("bytes 0-0/*").isEmpty());
        assertTrue(HttpRangeHeaders.parseContentRangeTotal("nope").isEmpty());
        assertTrue(HttpRangeHeaders.isRedirectStatus(301));
        assertTrue(HttpRangeHeaders.isRedirectStatus(308));
        assertFalse(HttpRangeHeaders.isRedirectStatus(200));
    }

    @Test
    void parsesFullContentRanges() {
        HttpRangeHeaders.ContentRange range = HttpRangeHeaders.parseContentRange("bytes 10-20/100");
        assertTrue(range.isKnown());
        assertEquals(10L, range.start());
        assertEquals(20L, range.endInclusive());
        assertEquals(100L, range.totalLength());

        HttpRangeHeaders.ContentRange unknownTotal = HttpRangeHeaders.parseContentRange("bytes 10-20/*");
        assertTrue(unknownTotal.isKnown());
        assertFalse(unknownTotal.hasKnownTotalLength());
        assertFalse(HttpRangeHeaders.parseContentRange("bytes 20-10/100").isKnown());
    }

    @Test
    void buildsRangeRequests() throws Exception {
        URL url = URI.create("https://example.invalid/audio.m4a").toURL();
        HttpRequest request = HttpRangeHeaders.rangeRequest(url, 42L, false, Duration.ofSeconds(3)).build();
        assertEquals("bytes=42-", request.headers().firstValue("Range").orElseThrow());

        HttpRequest probe = HttpRangeHeaders.rangeRequest(url, 999L, true, Duration.ofSeconds(3)).build();
        assertEquals("bytes=0-0", probe.headers().firstValue("Range").orElseThrow());

        HttpRequest bounded = HttpRangeHeaders.boundedRangeRequest(url, 10L, 20L, Duration.ofSeconds(3)).build();
        assertEquals("bytes=10-20", bounded.headers().firstValue("Range").orElseThrow());
    }
}