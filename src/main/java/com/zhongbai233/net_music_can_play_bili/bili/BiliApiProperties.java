package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** JVM property boundary for Bilibili API identity and playback preferences. */
final class BiliApiProperties {
    static final String SESSDATA = "ncpb.bili.sessdata";
    static final String WEB_COOKIE = "ncpb.bili.cookie";
    static final String USER_AGENT = "ncpb.bili.user_agent";
    static final String ROTATE_USER_AGENT = "ncpb.bili.rotate_user_agent";
    static final String AUDIO_PREFERENCE = "ncpb.bili.audio.preference";
    static final String LIVE_OFFLINE_RETRY_SECONDS = "ncpb.bili.live.offline_retry_seconds";
    static final String VIDEO_CODEC_POLICY = "ncpb.bili.video.codec_policy";

    private BiliApiProperties() {
    }

    static String initialSessdata() {
        return NcpbSystemProperties.stringValue(SESSDATA, "");
    }

    static String initialWebCookie() {
        return NcpbSystemProperties.stringValue(WEB_COOKIE, "");
    }

    static String initialUserAgent() {
        return NcpbSystemProperties.stringValue(USER_AGENT, "");
    }

    static boolean rotateUserAgent() {
        return NcpbSystemProperties.booleanValue(ROTATE_USER_AGENT, false);
    }

    static String audioPreference() {
        return NcpbSystemProperties.stringValue(AUDIO_PREFERENCE, "auto").toLowerCase(Locale.ROOT);
    }

    static long liveOfflineRetryMillis() {
        long seconds = NcpbSystemProperties.longValue(LIVE_OFFLINE_RETRY_SECONDS, 60L);
        return Math.max(10_000L, TimeUnit.SECONDS.toMillis(seconds));
    }

    static BiliApiClient.VideoCodecPolicy videoCodecPolicy() {
        return BiliApiClient.VideoCodecPolicy.parse(
                NcpbSystemProperties.stringValue(VIDEO_CODEC_POLICY, "auto"));
    }
}
