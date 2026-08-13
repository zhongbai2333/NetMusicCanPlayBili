package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioStreamPropertiesTest {
    private final List<String> changedKeys = new ArrayList<>();

    @AfterEach
    void clearProperties() {
        changedKeys.forEach(System::clearProperty);
    }

    @Test
    void defaultsRemainCompatible() {
        assertEquals(new AudioStreamProperties.Http(30, 3, 1_500L, 512), AudioStreamProperties.http());
        assertEquals(new AudioStreamProperties.Dolby(8, 128, 64), AudioStreamProperties.dolby());
        assertEquals(256, AudioStreamProperties.liveVideoBusCapacity());
        assertEquals(new AudioStreamProperties.Recovery(3, 1_000L), AudioStreamProperties.recovery());
        assertEquals(new AudioStreamProperties.RealMp3Bench(false,
                "https://www.learningcontainer.com/wp-content/uploads/2020/02/Kalimba.mp3"),
                AudioStreamProperties.realMp3Bench());
    }

    @Test
    void explicitValuesRemainConfigurable() {
        set(AudioStreamProperties.FORMAT_WAIT_SECONDS, "45");
        set(AudioStreamProperties.RANGE_RACE_MAX_CANDIDATES, "3");
        set(AudioStreamProperties.RANGE_RACE_TIMEOUT_MILLIS, "1500");
        set(AudioStreamProperties.DOLBY_PREBUFFER_FRAMES, "12");
        set(AudioStreamProperties.DOLBY_RAW_QUEUE_CAPACITY, "192");
        set(AudioStreamProperties.DOLBY_PROCESSED_QUEUE_CAPACITY, "96");
        set(AudioStreamProperties.LIVE_VIDEO_BUS_CAPACITY, "512");
        set(AudioStreamProperties.STREAM_RECOVERY_MAX_ATTEMPTS, "5");
        set(AudioStreamProperties.STREAM_RECOVERY_MIN_INTERVAL_MILLIS, "2000");
        set(AudioStreamProperties.REAL_MP3_BENCH, "true");
        set(AudioStreamProperties.REAL_MP3_BENCH_URL, " https://example.test/audio.mp3 ");

        assertEquals(new AudioStreamProperties.Http(45, 3, 1_500L, 512), AudioStreamProperties.http());
        assertEquals(new AudioStreamProperties.Dolby(12, 192, 96), AudioStreamProperties.dolby());
        assertEquals(512, AudioStreamProperties.liveVideoBusCapacity());
        assertEquals(new AudioStreamProperties.Recovery(5, 2_000L), AudioStreamProperties.recovery());
        assertEquals(new AudioStreamProperties.RealMp3Bench(true, "https://example.test/audio.mp3"),
                AudioStreamProperties.realMp3Bench());
    }

    @Test
    void legacyAudioRangeRaceKeysRemainCompatible() {
        set(AudioStreamProperties.LEGACY_RANGE_RACE_MAX_CANDIDATES, "4");
        set(AudioStreamProperties.LEGACY_RANGE_RACE_TIMEOUT_MILLIS, "1750");

        assertEquals(new AudioStreamProperties.Http(30, 4, 1_750L, 512), AudioStreamProperties.http());
    }

    @Test
    void canonicalSegmentCacheKeyOverridesLegacyAlias() {
        set(AudioStreamProperties.LEGACY_SEGMENT_BASE_CACHE_MAX_ENTRIES, "640");
        assertEquals(640, AudioStreamProperties.http().segmentBaseCacheMaxEntries());

        set(AudioStreamProperties.SEGMENT_BASE_CACHE_MAX_ENTRIES, "256");
        assertEquals(256, AudioStreamProperties.http().segmentBaseCacheMaxEntries());
    }

    @Test
    void invalidValuesUseCompatibilityDefaults() {
        set(AudioStreamProperties.FORMAT_WAIT_SECONDS, "invalid");
        set(AudioStreamProperties.DOLBY_RAW_QUEUE_CAPACITY, "invalid");
        set(AudioStreamProperties.LIVE_VIDEO_BUS_CAPACITY, "invalid");
        set(AudioStreamProperties.STREAM_RECOVERY_MIN_INTERVAL_MILLIS, "invalid");
        set(AudioStreamProperties.SEGMENT_BASE_CACHE_MAX_ENTRIES, "invalid");
        set(AudioStreamProperties.LEGACY_SEGMENT_BASE_CACHE_MAX_ENTRIES, "700");
        set(AudioStreamProperties.REAL_MP3_BENCH, "yes");
        set(AudioStreamProperties.REAL_MP3_BENCH_URL, "   ");

        assertEquals(30, AudioStreamProperties.http().formatWaitSeconds());
        assertEquals(700, AudioStreamProperties.http().segmentBaseCacheMaxEntries());
        assertEquals(128, AudioStreamProperties.dolby().rawQueueCapacity());
        assertEquals(256, AudioStreamProperties.liveVideoBusCapacity());
        assertEquals(1_000L, AudioStreamProperties.recovery().minIntervalMillis());
        assertEquals(new AudioStreamProperties.RealMp3Bench(false,
                "https://www.learningcontainer.com/wp-content/uploads/2020/02/Kalimba.mp3"),
                AudioStreamProperties.realMp3Bench());
    }

    @Test
    void unsafeCapacitiesAndIntervalsAreClamped() {
        set(AudioStreamProperties.FORMAT_WAIT_SECONDS, "0");
        set(AudioStreamProperties.RANGE_RACE_MAX_CANDIDATES, "0");
        set(AudioStreamProperties.RANGE_RACE_TIMEOUT_MILLIS, "0");
        set(AudioStreamProperties.SEGMENT_BASE_CACHE_MAX_ENTRIES, "0");
        set(AudioStreamProperties.DOLBY_PREBUFFER_FRAMES, "-1");
        set(AudioStreamProperties.DOLBY_RAW_QUEUE_CAPACITY, "0");
        set(AudioStreamProperties.DOLBY_PROCESSED_QUEUE_CAPACITY, "0");
        set(AudioStreamProperties.LIVE_VIDEO_BUS_CAPACITY, "0");
        set(AudioStreamProperties.STREAM_RECOVERY_MAX_ATTEMPTS, "-1");
        set(AudioStreamProperties.STREAM_RECOVERY_MIN_INTERVAL_MILLIS, "-1");

        assertEquals(new AudioStreamProperties.Http(15, 1, 250L, 1), AudioStreamProperties.http());
        assertEquals(new AudioStreamProperties.Dolby(0, 32, 1), AudioStreamProperties.dolby());
        assertEquals(32, AudioStreamProperties.liveVideoBusCapacity());
        assertEquals(new AudioStreamProperties.Recovery(0, 0L), AudioStreamProperties.recovery());

        set(AudioStreamProperties.RANGE_RACE_MAX_CANDIDATES, "1000");
        assertEquals(8, AudioStreamProperties.http().rangeRaceMaxCandidates());
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        if (!changedKeys.contains(key)) {
            changedKeys.add(key);
        }
    }
}
