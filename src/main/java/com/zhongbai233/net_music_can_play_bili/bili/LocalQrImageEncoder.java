package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.internal.qr.zxing.BarcodeFormat;
import com.zhongbai233.net_music_can_play_bili.internal.qr.zxing.EncodeHintType;
import com.zhongbai233.net_music_can_play_bili.internal.qr.zxing.WriterException;
import com.zhongbai233.net_music_can_play_bili.internal.qr.zxing.qrcode.QRCodeWriter;
import com.zhongbai233.net_music_can_play_bili.internal.qr.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Bounded, local-only QR PNG encoding. Never normalizes/truncates a login URL or performs I/O to a service. */
final class LocalQrImageEncoder {
    static final int MAX_UTF8_BYTES = 2048;
    static final int QUIET_ZONE_MODULES = 4;
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), NetMusicThreadFactory.daemon("qr-image"));
    private LocalQrImageEncoder() { }
    static ExecutorService executor() { return WORKER; }
    static CancellableTaskFuture<byte[]> encodeAsync(String content, ExecutorService executor) {
        return CancellableTaskFuture.submit(executor, () -> encode(content));
    }
    static byte[] encode(String content) {
        validate(content);
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        try {
            var hints = Map.<EncodeHintType, Object>of(EncodeHintType.CHARACTER_SET, "UTF-8",
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, QUIET_ZONE_MODULES);
            // Request module-native geometry, then scale by a whole integer; preserve the quiet zone.
            var modules = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
            int scale = Math.max(1, (180 + modules.getWidth() - 1) / modules.getWidth());
            int size = Math.multiplyExact(modules.getWidth(), scale);
            if (size > 512) throw new IllegalArgumentException("QR image exceeds bounded geometry");
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < size; y++) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                for (int x = 0; x < size; x++) image.setRGB(x, y, modules.get(x / scale, y / scale) ? 0xff000000 : 0xffffffff);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            var writers = ImageIO.getImageWritersByFormatName("PNG");
            if (!writers.hasNext()) throw new IllegalStateException("PNG encoder unavailable");
            var writer = writers.next();
            try (var stream = new MemoryCacheImageOutputStream(output)) {
                writer.setOutput(stream);
                writer.write(image);
            } finally {
                writer.dispose();
                image.flush();
            }
            return output.toByteArray();
        } catch (WriterException | IOException error) {
            // Do not include the login content, URI parser input or a nested sensitive message.
            throw new IllegalStateException("Local QR encoding failed");
        }
    }
    private static void validate(String content) {
        if (content == null || content.isBlank() || content.length() > MAX_UTF8_BYTES)
            throw new IllegalArgumentException("Missing or oversized login URL");
        try {
            int bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(content)).remaining();
            if (bytes > MAX_UTF8_BYTES) throw new IllegalArgumentException("Login URL exceeds UTF-8 byte limit");
            URI uri = new URI(content);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawAuthority() == null || uri.getRawAuthority().isBlank())
                throw new IllegalArgumentException("Expected an absolute HTTP(S) login URL");
        } catch (URISyntaxException | CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid login URL encoding");
        }
    }
}
