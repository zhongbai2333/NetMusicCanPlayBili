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
    void legacyAudioRangeRaceKeysRemainCompatible() {
        set(AudioStreamProperties.LEGACY_RANGE_RACE_MAX_CANDIDATES, "4");
        set(AudioStreamProperties.LEGACY_RANGE_RACE_TIMEOUT_MILLIS, "1750");

        assertEquals(new AudioStreamProperties.Http(30, 4, 1_750L, 512), AudioStreamProperties.http());
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
