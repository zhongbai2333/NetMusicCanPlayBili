package com.zhongbai233.net_music_can_play_bili;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildMediaRunPropertiesContractTest {
    @Test
    void clientBenchAndPairedRunsShareOneMediaPropertyCatalog() throws Exception {
        String build = Files.readString(Path.of("build.gradle"));
        String gradleProperties = Files.readString(Path.of("gradle.properties"));
        String normalizedBuild = build.replaceAll("\\s+", " ");
        Map<String, String> criticalMappings = Map.of(
                "ncpb.video.real_bench.max_fps", "ncpbRealBenchMaxFps",
                "ncpb.live.real_bench", "ncpbRealLiveBench",
                "ncpb.live.real_bench.room", "ncpbLiveBenchRoom",
                "ncpb.video.native.av1_first_frame_probe_timeout_ms", "ncpbAv1FirstFrameProbeTimeoutMillis",
                "ncpb.video.native.av1_first_frame_probe_max_packets", "ncpbAv1FirstFrameProbeMaxPackets");

        criticalMappings.forEach((systemProperty, gradleProperty) -> assertTrue(normalizedBuild.contains(
                "system: '" + systemProperty + "', gradle: '" + gradleProperty + "'"),
                () -> "missing shared run-property mapping for " + systemProperty));
        assertEquals(2, occurrences(build, "sharedMediaRunProperties.each { spec ->"),
                "client and Bench run configuration must both consume the shared catalog");
        assertTrue(build.contains("sharedMediaRunProperties.collect { it.gradle }"),
                "paired clients must forward every shared Gradle property");
        assertTrue(gradleProperties.contains("modbench_version=0.1.3-beta"),
                "pairedClientCount requires the released ModBench paired API");
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }
}
