package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * B站 CDN 音频流处理器
 */
public class BiliHttpAudioStreamHandler implements IAudioStreamHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    @Override
    public boolean canHandle(URL url) {
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            return false;
        }

        String host = url.getHost();
        if (host == null)
            return false;
        host = host.toLowerCase();

        if (url.getPath() != null && url.getPath().endsWith(".m3u8")) {
            return false;
        }

        return host.contains("bilivideo")
                || host.contains("hdslb")
                || host.contains("mcdn")
                || host.contains("bilibili");
    }

    @Override
    public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
        LOGGER.info("使用 B站音频流处理器: {}://{}/...", url.getProtocol(), url.getHost());

        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com/")
                .header("Origin", "https://www.bilibili.com")
                .header("Accept", "*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();

        // 下载 M4S 到内存
        byte[] fmp4Data;
        try {
            HttpResponse<byte[]> response = BiliWbiSigner.HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            String contentType = response.headers().firstValue("content-type").orElse("unknown");
            fmp4Data = response.body();
            LOGGER.info("B站音频下载完成: status={}, contentType={}, size={} bytes",
                    status, contentType, fmp4Data.length);
            if (status < 200 || status >= 300) {
                throw new IOException("B站 CDN 返回 HTTP " + status);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载 B站音频时线程被中断", e);
        }

        // fMP4 → 标准 MP4
        byte[] mp4Data = Fmp4ToMp4Converter.convertToStandardMp4(fmp4Data);
        LOGGER.info("fMP4→标准MP4 转换完成, size={} bytes", mp4Data.length);

        AudioInputStream audioInputStream = new com.github.tartaricacid.netmusic.soundlibs.net.sourceforge.jaad.spi.javasound.AACAudioFileReader()
                .getAudioInputStream(new java.io.ByteArrayInputStream(mp4Data));

        AudioFormat format = audioInputStream.getFormat();
        LOGGER.info("B站音频解码: encoding={}, sampleRate={}Hz, channels={}, sampleSize={}bit",
                format.getEncoding(), format.getSampleRate(),
                format.getChannels(), format.getSampleSizeInBits());
        return audioInputStream;
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
