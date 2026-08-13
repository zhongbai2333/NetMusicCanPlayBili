package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdnHealthTrackerTest {
    @AfterEach
    void clearHealth() {
        CdnHealthTracker.clear();
    }

    @Test
    void recentFastWinnerRanksAheadOfUnknownHost() throws Exception {
        URL winner = URI.create("https://winner.example/video.m4s").toURL();
        URL unknown = URI.create("https://unknown.example/video.m4s").toURL();

        CdnHealthTracker.recordSuccess(winner, 100L, 1_071L);

        assertTrue(CdnHealthTracker.score(winner) < CdnHealthTracker.score(unknown));
    }

    @Test
    void failedHostRanksBehindUnknownHost() throws Exception {
        URL failed = URI.create("https://failed.example/video.m4s").toURL();
        URL unknown = URI.create("https://unknown.example/video.m4s").toURL();

        CdnHealthTracker.recordFailure(failed, CdnHealthTracker.FailureKind.IO);

        assertTrue(CdnHealthTracker.score(failed) > CdnHealthTracker.score(unknown));
    }

    @Test
    void staleObservationsConvergeToUnknownInsteadOfBecomingArtificialWinners() {
        double unknown = CdnHealthTracker.ageAdjustedScore(0.0D, 0.0D, 10_000L, 10_000L);
        double staleFailure = CdnHealthTracker.ageAdjustedScore(8.0D, 2_000.0D, 10_000L, 10_000L);

        assertEquals(unknown, staleFailure);
        assertTrue(CdnHealthTracker.ageAdjustedScore(0.0D, 100.0D, 0L, 10_000L) < unknown);
        assertTrue(CdnHealthTracker.ageAdjustedScore(4.0D, 0.0D, 0L, 10_000L) > unknown);
    }
}
