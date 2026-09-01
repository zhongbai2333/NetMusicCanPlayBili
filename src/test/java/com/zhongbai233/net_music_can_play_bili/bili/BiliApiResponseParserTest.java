package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliApiResponseParserTest {
    @Test
    void acceptsAJsonObjectEnvelopeEvenWhenContentTypeIsImprecise() throws Exception {
        assertEquals(0, BiliApiResponseParser.parse(
                "view", 200, "text/plain", "{\"code\":0,\"data\":{}}")
                .get("code").getAsInt());
    }

    @Test
    void rejectsMalformedNonJsonWithBoundedDiagnostics() {
        String malformed = "upstream reset " + "x".repeat(500);
        BiliApiResponseException failure = assertThrows(BiliApiResponseException.class,
                () -> BiliApiResponseParser.parse("view", 200, "text/html", malformed));

        assertTrue(failure.getMessage().contains("非法 JSON"));
        assertTrue(failure.getMessage().contains("Content-Type=text/html"));
        assertTrue(failure.getMessage().length() < 320);
    }

    @Test
    void rejectsEmptyNonObjectMissingCodeAndHttpFailures() {
        assertThrows(BiliApiResponseException.class,
                () -> BiliApiResponseParser.parse("view", 200, "application/json", ""));
        assertThrows(BiliApiResponseException.class,
                () -> BiliApiResponseParser.parse("view", 200, "application/json", "[]"));
        assertThrows(BiliApiResponseException.class,
                () -> BiliApiResponseParser.parse("view", 200, "application/json", "{\"data\":{}}"));
        assertThrows(BiliApiResponseException.class,
                () -> BiliApiResponseParser.parse("view", 502, "text/html", "bad gateway"));
    }
}
