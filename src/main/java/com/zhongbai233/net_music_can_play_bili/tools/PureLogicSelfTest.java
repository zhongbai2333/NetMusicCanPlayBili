package com.zhongbai233.net_music_can_play_bili.tools;

import com.zhongbai233.net_music_can_play_bili.bili.Mp3FrameSync;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;

/** Lightweight pure-logic self tests that do not require launching Minecraft. */
public final class PureLogicSelfTest {
    private PureLogicSelfTest() {
    }

    public static void main(String[] args) {
        findsMp3SyncAfterNoise();
        rejectsInvalidMp3Frames();
        createsNamedDaemonThreads();
        System.out.println("PureLogicSelfTest passed");
    }

    private static void findsMp3SyncAfterNoise() {
        byte[] stream = new byte[13 + 417 * 3];
        for (int i = 0; i < 13; i++) {
            stream[i] = (byte) (0x40 + i);
        }
        for (int offset = 13; offset < stream.length; offset += 417) {
            writeMpeg1Layer3FrameHeader(stream, offset);
        }
        int sync = Mp3FrameSync.findFrameSync(stream, stream.length);
        if (sync != 13) {
            throw new AssertionError("expected MP3 sync at byte 13, got " + sync);
        }
    }

    private static void rejectsInvalidMp3Frames() {
        byte[] invalid = { 0x00, 0x11, 0x22, 0x33, 0x44 };
        if (Mp3FrameSync.findFrameSync(invalid, invalid.length) != -1) {
            throw new AssertionError("invalid MP3 bytes should not produce a sync offset");
        }
        if (Mp3FrameSync.parseFrame(invalid, 0, invalid.length) != null) {
            throw new AssertionError("invalid MP3 bytes should not parse as a frame");
        }
    }

    private static void createsNamedDaemonThreads() {
        Thread direct = NetMusicThreadFactory.daemonThread("unit-worker", () -> {
        });
        if (!direct.isDaemon() || !"unit-worker".equals(direct.getName())) {
            throw new AssertionError("daemonThread should preserve name and daemon flag");
        }
        Thread pooled = NetMusicThreadFactory.daemon("unit-pool").newThread(() -> {
        });
        if (!pooled.isDaemon() || !pooled.getName().startsWith("unit-pool-")) {
            throw new AssertionError("daemon factory should create named daemon threads");
        }
    }

    /** MPEG 1 Layer III, 128 kbps, 44100 Hz, no padding => frame length 417 bytes. */
    private static void writeMpeg1Layer3FrameHeader(byte[] target, int offset) {
        target[offset] = (byte) 0xFF;
        target[offset + 1] = (byte) 0xFB;
        target[offset + 2] = (byte) 0x90;
        target[offset + 3] = 0x64;
    }
}
