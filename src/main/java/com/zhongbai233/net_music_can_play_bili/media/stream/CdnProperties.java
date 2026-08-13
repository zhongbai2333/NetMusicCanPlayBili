package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

import java.util.Locale;

/** Central JVM property boundary for CDN selection, probing, and fallback grouping. */
public final class CdnProperties {
    static final String SELECTOR_ENABLED = "ncpb.bili.cdn_selector.enabled";
    static final String SELECTOR_RACE = "ncpb.bili.cdn_selector.race";
    static final String RACE_BYTES = "ncpb.bili.cdn_selector.race_bytes";
    static final String LEGACY_RACE_BYTES = "ncpb.ncpb.bili.cdn_selector.race_bytes";
    static final String RACE_TIMEOUT_MILLIS = "ncpb.bili.cdn_selector.race_timeout_ms";
    static final String LEGACY_RACE_TIMEOUT_MILLIS = "ncpb.ncpb.bili.cdn_selector.race_timeout_ms";
    static final String MAX_RACE_CANDIDATES = "ncpb.bili.cdn_selector.max_race_candidates";
    static final String MIN_PERSIST_INTERVAL_MILLIS = "ncpb.bili.cdn_selector.min_persist_interval_ms";
    static final String BACKGROUND_RACE_INTERVAL_MILLIS =
            "ncpb.bili.cdn_selector.background_race_interval_ms";
    static final String PREFERRED_HOST = "ncpb.bili.cdn.preferred_host";
    static final String FALLBACK_MAX_GROUPS = "ncpb.bili.cdn_fallback.max_groups";

    private CdnProperties() {
    }

    public static Selector selector() {
        return new Selector(
                NcpbSystemProperties.booleanValue(SELECTOR_ENABLED, true),
                NcpbSystemProperties.booleanValue(SELECTOR_RACE, false),
                Math.max(1, NcpbSystemProperties.intValue(RACE_BYTES, LEGACY_RACE_BYTES, 2048)),
                Math.max(250L, NcpbSystemProperties.longValue(
                        RACE_TIMEOUT_MILLIS, LEGACY_RACE_TIMEOUT_MILLIS, 2_500L)),
                Math.max(1, NcpbSystemProperties.intValue(MAX_RACE_CANDIDATES, 4)),
                Math.max(0L, NcpbSystemProperties.longValue(MIN_PERSIST_INTERVAL_MILLIS, 5_000L)),
                Math.max(1_000L, NcpbSystemProperties.longValue(BACKGROUND_RACE_INTERVAL_MILLIS, 60_000L)),
                normalizeHost(NcpbSystemProperties.stringValue(PREFERRED_HOST, "")));
    }

    public static Fallback fallback() {
        return new Fallback(Math.max(1, NcpbSystemProperties.intValue(FALLBACK_MAX_GROUPS, 512)));
    }

    private static String normalizeHost(String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    }

    public record Selector(boolean enabled, boolean raceEnabled, int raceBytes, long raceTimeoutMillis,
            int maxRaceCandidates, long minPersistIntervalMillis, long backgroundRaceIntervalMillis,
            String preferredHost) {
    }

    public record Fallback(int maxGroups) {
    }
}
