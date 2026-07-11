package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeHeaders;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

/** 客户端纯音频预览的轻量时长兜底探测器。 */
public final class AudioDurationProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PROBE_BYTES = 128 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final int[] MP3_MPEG1_LAYER1_BITRATES = { 0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352,
            384, 416, 448, 0 };
    private static final int[] MP3_MPEG1_LAYER2_BITRATES = { 0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256,
            320, 384, 0 };
    private static final int[] MP3_MPEG1_LAYER3_BITRATES = { 0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224,
            256, 320, 0 };
    private static final int[] MP3_MPEG2_LAYER1_BITRATES = { 0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176,
            192, 224, 256, 0 };
    private static final int[] MP3_MPEG2_LAYER23_BITRATES = { 0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128,
            144, 160, 0 };

    private AudioDurationProbe() {
    }

    public static CompletableFuture<OptionalLong> probeMillisAsync(String rawUrl) {
        return CompletableFuture.supplyAsync(() -> probeMillis(rawUrl));
    }

    private static OptionalLong probeMillis(String rawUrl) {
        String url = PlaybackSync.strip(rawUrl);
        if (url == null || url.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            ProbeResponse response = probe(url, 0);
            long totalBytes = response.totalBytes();
            Mp3Frame frame = findMp3Frame(response.body());
            if (totalBytes <= 0L || frame == null || frame.bitrateKbps() <= 0) {
                return OptionalLong.empty();
            }
            long audioBytes = Math.max(1L, totalBytes - Math.max(0, frame.offset()));
            long millis = Math.round((audioBytes * 8_000.0D) / (frame.bitrateKbps() * 1000.0D));
            if (millis <= 0L) {
                return OptionalLong.empty();
            }
            LOGGER.debug("纯音频预览本地估算时长: {}ms bitrate={}kbps bytes={} host={}", millis,
                    frame.bitrateKbps(), totalBytes, response.uri().getHost());
            return OptionalLong.of(millis);
        } catch (Exception e) {
            LOGGER.debug("纯音频预览本地时长探测失败: {} reason={}", url, e.toString());
            return OptionalLong.empty();
        }
    }

    private static ProbeResponse probe(String url, int redirects) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = HTTP_CLIENT.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (HttpRangeHeaders.isRedirectStatus(status)) {
                if (redirects >= MAX_REDIRECTS) {
                    throw new IOException("too many redirects while probing audio duration");
                }
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("HTTP " + status + " redirect without Location"));
                return probe(response.uri().resolve(location).toString(), redirects + 1);
            }
            if (status != 200 && status != 206) {
                throw new IOException("HTTP " + status + " while probing audio duration");
            }
            long totalBytes = response.headers().firstValue("Content-Range")
                    .flatMap(HttpRangeHeaders::parseContentRangeTotal)
                    .orElseGet(() -> response.headers().firstValueAsLong("Content-Length").orElse(-1L));
            byte[] bytes = body == null ? new byte[0] : body.readNBytes(PROBE_BYTES);
            return new ProbeResponse(response.uri(), bytes, totalBytes);
        }
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .GET()
                .header("Range", "bytes=0-" + (PROBE_BYTES - 1))
                .header("User-Agent", "Mozilla/5.0 NetMusicCanPlayBili")
                .build();
    }

    private static Mp3Frame findMp3Frame(byte[] bytes) {
        int start = id3v2Size(bytes);
        for (int i = Math.max(0, start); i + 3 < bytes.length; i++) {
            Mp3Frame frame = parseMp3Frame(bytes, i);
            if (frame != null) {
                return frame;
            }
        }
        return null;
    }

    private static int id3v2Size(byte[] bytes) {
        if (bytes.length < 10 || bytes[0] != 'I' || bytes[1] != 'D' || bytes[2] != '3') {
            return 0;
        }
        return 10 + ((bytes[6] & 0x7F) << 21) + ((bytes[7] & 0x7F) << 14)
                + ((bytes[8] & 0x7F) << 7) + (bytes[9] & 0x7F);
    }

    private static Mp3Frame parseMp3Frame(byte[] bytes, int offset) {
        int b0 = bytes[offset] & 0xFF;
        int b1 = bytes[offset + 1] & 0xFF;
        int b2 = bytes[offset + 2] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) {
            return null;
        }
        int version = (b1 >> 3) & 0x03;
        int layer = (b1 >> 1) & 0x03;
        int bitrateIndex = (b2 >> 4) & 0x0F;
        if (version == 0x01 || layer == 0x00 || bitrateIndex == 0x00 || bitrateIndex == 0x0F) {
            return null;
        }
        int bitrateKbps = mp3BitrateKbps(version, layer, bitrateIndex);
        return bitrateKbps > 0 ? new Mp3Frame(offset, bitrateKbps) : null;
    }

    private static int mp3BitrateKbps(int version, int layer, int index) {
        if (version == 0x03) {
            if (layer == 0x03) {
                return MP3_MPEG1_LAYER1_BITRATES[index];
            }
            if (layer == 0x02) {
                return MP3_MPEG1_LAYER2_BITRATES[index];
            }
            return MP3_MPEG1_LAYER3_BITRATES[index];
        }
        if (layer == 0x03) {
            return MP3_MPEG2_LAYER1_BITRATES[index];
        }
        return MP3_MPEG2_LAYER23_BITRATES[index];
    }

    private record ProbeResponse(URI uri, byte[] body, long totalBytes) {
    }

    private record Mp3Frame(int offset, int bitrateKbps) {
    }
}