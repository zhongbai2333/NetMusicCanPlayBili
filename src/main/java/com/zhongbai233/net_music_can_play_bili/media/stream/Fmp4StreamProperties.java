package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

/** JVM property boundary for fMP4 stream parsing limits. */
final class Fmp4StreamProperties {
    static final String MAX_BUFFERED_PAYLOAD_BYTES = "ncpb.media.fmp4.max_buffered_payload_bytes";

    private static final int MIN_BUFFERED_PAYLOAD_BYTES = 1024 * 1024;
    private static final int DEFAULT_MAX_BUFFERED_PAYLOAD_BYTES = 64 * 1024 * 1024;

    private Fmp4StreamProperties() {
    }

    static int maxBufferedPayloadBytes() {
        return Math.max(MIN_BUFFERED_PAYLOAD_BYTES,
                NcpbSystemProperties.intValue(
                        MAX_BUFFERED_PAYLOAD_BYTES, DEFAULT_MAX_BUFFERED_PAYLOAD_BYTES));
    }
}
