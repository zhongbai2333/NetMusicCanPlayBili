package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliApiPropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        assertEquals("", BiliApiProperties.initialSessdata());
        assertEquals("", BiliApiProperties.initialWebCookie());
        assertEquals("", BiliApiProperties.initialUserAgent());
        assertFalse(BiliApiProperties.rotateUserAgent());
        assertEquals("auto", BiliApiProperties.audioPreference());
        assertEquals(60_000L, BiliApiProperties.liveOfflineRetryMillis());
        assertEquals(BiliApiClient.VideoCodecPolicy.AUTO, BiliApiProperties.videoCodecPolicy());
    }

    @Test
    void explicitValuesRemainConfigurableAndStringsAreNormalized() {
        set(BiliApiProperties.SESSDATA, " session-token ");
        set(BiliApiProperties.WEB_COOKIE, " SESSDATA=session-token; bili_jct=csrf-token ");
        set(BiliApiProperties.USER_AGENT, " Custom Browser Agent ");
        set(BiliApiProperties.ROTATE_USER_AGENT, "true");
        set(BiliApiProperties.AUDIO_PREFERENCE, " HiRes ");
        set(BiliApiProperties.LIVE_OFFLINE_RETRY_SECONDS, "75");
        set(BiliApiProperties.VIDEO_CODEC_POLICY, " Prefer-AV1 ");

        assertEquals("session-token", BiliApiProperties.initialSessdata());
        assertEquals("SESSDATA=session-token; bili_jct=csrf-token", BiliApiProperties.initialWebCookie());
        assertEquals("Custom Browser Agent", BiliApiProperties.initialUserAgent());
        assertTrue(BiliApiProperties.rotateUserAgent());
        assertEquals("hires", BiliApiProperties.audioPreference());
        assertEquals(75_000L, BiliApiProperties.liveOfflineRetryMillis());
        assertEquals(BiliApiClient.VideoCodecPolicy.PREFER_AV1, BiliApiProperties.videoCodecPolicy());
    }

    @Test
    void blankAndInvalidValuesUseCompatibleDefaults() {
        set(BiliApiProperties.SESSDATA, "   ");
        set(BiliApiProperties.WEB_COOKIE, "   ");
        set(BiliApiProperties.USER_AGENT, "   ");
        set(BiliApiProperties.ROTATE_USER_AGENT, "enabled");
        set(BiliApiProperties.AUDIO_PREFERENCE, "   ");
        set(BiliApiProperties.LIVE_OFFLINE_RETRY_SECONDS, "invalid");
        set(BiliApiProperties.VIDEO_CODEC_POLICY, "enable-everything");

        assertEquals("", BiliApiProperties.initialSessdata());
        assertEquals("", BiliApiProperties.initialWebCookie());
        assertEquals("", BiliApiProperties.initialUserAgent());
        assertFalse(BiliApiProperties.rotateUserAgent());
        assertEquals("auto", BiliApiProperties.audioPreference());
        assertEquals(60_000L, BiliApiProperties.liveOfflineRetryMillis());
        assertEquals(BiliApiClient.VideoCodecPolicy.AUTO, BiliApiProperties.videoCodecPolicy());
    }

    @Test
    void everyDocumentedVideoCodecPolicyParses() {
        assertPolicy("auto", BiliApiClient.VideoCodecPolicy.AUTO);
        assertPolicy("prefer-av1", BiliApiClient.VideoCodecPolicy.PREFER_AV1);
        assertPolicy("compatibility", BiliApiClient.VideoCodecPolicy.COMPATIBILITY);
        assertPolicy("h264", BiliApiClient.VideoCodecPolicy.H264);
    }

    @Test
    void unsafeRetrySecondsAreClampedAndConversionSaturates() {
        set(BiliApiProperties.LIVE_OFFLINE_RETRY_SECONDS, "0");
        assertEquals(10_000L, BiliApiProperties.liveOfflineRetryMillis());

        set(BiliApiProperties.LIVE_OFFLINE_RETRY_SECONDS, "-1");
        assertEquals(10_000L, BiliApiProperties.liveOfflineRetryMillis());

        set(BiliApiProperties.LIVE_OFFLINE_RETRY_SECONDS, Long.toString(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, BiliApiProperties.liveOfflineRetryMillis());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }

    private void assertPolicy(String raw, BiliApiClient.VideoCodecPolicy expected) {
        set(BiliApiProperties.VIDEO_CODEC_POLICY, raw);
        assertEquals(expected, BiliApiProperties.videoCodecPolicy());
    }
}
