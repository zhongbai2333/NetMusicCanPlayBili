package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveVideoSampleBusTest {
    private static final byte[] AVC_C = { 1, 100, 0, 40, (byte) 0xFF };

    private LiveVideoSampleBus bus;

    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.close();
        }
    }

    @Test
    void mapsFlvTimestampsIntoAudioDomain() throws Exception {
        bus = LiveVideoSampleBus.register("session-a");
        bus.publishConfig(AVC_C);
        // 音频锚：FLV ts=100000ms 时 OpenAL 已喂 2000ms
        bus.setAudioAnchor(100_000L, 2_000L);

        bus.pushSample(new byte[] { 1 }, 100_040L, 0, true);
        bus.pushSample(new byte[] { 2 }, 100_080L, -24, false);

        LiveVideoSampleBus.VideoSample first = bus.poll(100L);
        LiveVideoSampleBus.VideoSample second = bus.poll(100L);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals((2_000L + 40L) * 1_000_000L, first.ptsNanos());
        assertEquals((2_000L + 80L - 24L) * 1_000_000L, second.ptsNanos());
        assertTrue(first.keyframe());
        assertArrayEquals(AVC_C, first.avcConfig());
    }

    @Test
    void dropsSamplesBeforeAudioAnchor() throws Exception {
        bus = LiveVideoSampleBus.register("session-b");
        bus.publishConfig(AVC_C);

        bus.pushSample(new byte[] { 1 }, 50L, 0, true);
        assertEquals(1L, bus.droppedSamples());
        assertNull(bus.poll(10L));

        bus.setAudioAnchor(100L, 0L);
        bus.pushSample(new byte[] { 2 }, 140L, 0, true);
        LiveVideoSampleBus.VideoSample sample = bus.poll(100L);
        assertNotNull(sample);
        assertEquals(40L * 1_000_000L, sample.ptsNanos());
    }

    @Test
    void consumerStartsAtKeyframe() throws Exception {
        bus = LiveVideoSampleBus.register("session-c");
        bus.publishConfig(AVC_C);
        bus.setAudioAnchor(0L, 0L);

        bus.pushSample(new byte[] { 1 }, 10L, 0, false);
        bus.pushSample(new byte[] { 2 }, 20L, 0, false);
        bus.pushSample(new byte[] { 3 }, 30L, 0, true);
        bus.pushSample(new byte[] { 4 }, 40L, 0, false);

        LiveVideoSampleBus.VideoSample first = bus.poll(100L);
        assertNotNull(first);
        assertArrayEquals(new byte[] { 3 }, first.data());
        assertTrue(first.keyframe());
        LiveVideoSampleBus.VideoSample next = bus.poll(100L);
        assertNotNull(next);
        assertArrayEquals(new byte[] { 4 }, next.data());
    }

    @Test
    void beginConnectionResetsAnchorAndKeyframeGate() throws Exception {
        bus = LiveVideoSampleBus.register("session-d");
        bus.publishConfig(AVC_C);
        bus.setAudioAnchor(0L, 0L);
        bus.pushSample(new byte[] { 1 }, 10L, 0, true);
        assertNotNull(bus.poll(100L));

        bus.beginConnection();
        assertFalse(bus.hasAudioAnchor());
        // 新连接时间戳基准跳变：新锚点建立后，非关键帧被丢到关键帧
        bus.setAudioAnchor(1_000_000L, 5_000L);
        bus.pushSample(new byte[] { 2 }, 1_000_010L, 0, false);
        bus.pushSample(new byte[] { 3 }, 1_000_020L, 0, true);
        LiveVideoSampleBus.VideoSample sample = bus.poll(100L);
        assertNotNull(sample);
        assertArrayEquals(new byte[] { 3 }, sample.data());
    }

    @Test
    void closeWakesConsumerWithNull() throws Exception {
        bus = LiveVideoSampleBus.register("session-e");
        bus.close();
        assertTrue(bus.isClosed());
        assertNull(bus.poll(50L));
        assertNull(LiveVideoSampleBus.find("session-e"));
    }

    @Test
    void registerReplacesAndClosesPreviousBus() {
        bus = LiveVideoSampleBus.register("session-f");
        LiveVideoSampleBus replacement = LiveVideoSampleBus.register("session-f");
        assertTrue(bus.isClosed());
        assertEquals(replacement, LiveVideoSampleBus.find("session-f"));
        replacement.close();
    }

    @Test
    void busUrlRoundTrip() {
        assertTrue(LiveVideoSampleBus.isBusUrl(LiveVideoSampleBus.busUrl("abc")));
        assertEquals("abc", LiveVideoSampleBus.keyFromBusUrl(LiveVideoSampleBus.busUrl("abc")));
        assertFalse(LiveVideoSampleBus.isBusUrl("https://example.com/a.flv"));
    }
}
