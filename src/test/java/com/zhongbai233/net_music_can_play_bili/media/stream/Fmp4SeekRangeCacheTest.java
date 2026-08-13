package com.zhongbai233.net_music_can_play_bili.media.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.URI;
import java.net.URL;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Fmp4SeekRangeCacheTest {
    @AfterEach
    void clear() {
        Fmp4SeekRangeCache.clearForTests();
    }

    @Test
    void equivalentCdnHostsReuseTheSameSmallRange() throws Exception {
        URL primary = URI.create("https://bad.example/video/track.m4s?token=same").toURL();
        URL backup = URI.create("https://good.example/video/track.m4s?token=same").toURL();
        byte[] bytes = { 1, 2, 3, 4 };

        Fmp4SeekRangeCache.put(primary, 938L, 941L, bytes, 9_000L, "good.example", backup.toString());

        Fmp4SeekRangeCache.CachedRange cached = Fmp4SeekRangeCache.get(backup, 938L, 941L);
        assertArrayEquals(bytes, cached.bytes());
        assertSame(cached, Fmp4SeekRangeCache.get(primary, 938L, 941L));

        byte[] exposed = cached.bytes();
        exposed[0] = 99;
        assertArrayEquals(bytes, Fmp4SeekRangeCache.get(primary, 938L, 941L).bytes());
    }

    @Test
    void rangeAndSignedResourceArePartOfTheCacheIdentity() throws Exception {
        URL source = URI.create("https://cdn.example/video/track.m4s?token=one").toURL();
        Fmp4SeekRangeCache.put(source, 0L, 0L, new byte[] { 7 }, 9_000L, "cdn.example",
                source.toString());

        assertNull(Fmp4SeekRangeCache.get(source, 938L, 1901L));
        assertNull(Fmp4SeekRangeCache.get(
                URI.create("https://cdn.example/video/track.m4s?token=two").toURL(), 0L, 0L));
    }

    @Test
    void incompleteRangesAreNeverCached() throws Exception {
        URL source = URI.create("https://cdn.example/video/track.m4s").toURL();

        Fmp4SeekRangeCache.put(source, 100L, 103L, new byte[] { 1, 2, 3 }, 9_000L,
                "cdn.example", source.toString());

        assertNull(Fmp4SeekRangeCache.get(source, 100L, 103L));
    }
}
