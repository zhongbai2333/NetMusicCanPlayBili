package com.zhongbai233.net_music_can_play_bili.bili;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Independent full-upstream reader consumes the privately relocated encoder's PNG. No real login data. */
class LocalQrImageEncoderTest {
    private void roundtrip(String text) throws Exception {
        byte[] bytes = LocalQrImageEncoder.encode(text);
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image);assertTrue(image.getWidth() >= 180 && image.getWidth() <= 512);
        assertEquals(image.getWidth(), image.getHeight());
        int side = image.getWidth();
        for (int i = 0; i < side; i++) {
            assertEquals(0xffffff, image.getRGB(i, 0) & 0xffffff);
            assertEquals(0xffffff, image.getRGB(0, i) & 0xffffff);
        }
        int[] pixels = image.getRGB(0, 0, side, side, null, 0, side);
        var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(side, side, pixels)));
        Result decoded = new QRCodeReader().decode(bitmap);
        assertEquals(text, decoded.getText());
        assertEquals("M", decoded.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL));
    }
    @ParameterizedTest @ValueSource(strings={
        "https://example.invalid/scan?key=fabricated-only&from=",
        "https://example.invalid/a%2Fb?q=a+b&escaped=%25%26%3D#fragment",
        "https://example.invalid/登录?内容=你好🙂&return=https%3A%2F%2Fexample.invalid%2F",
        "http://[::1]:12345/synthetic?x=1&x=2"
    }) void exactPayloadAndUtf8Roundtrip(String text) throws Exception {roundtrip(text);}
    @Test void exactMaximumAsciiUrlRoundtripsWithoutTruncation() throws Exception {
        String prefix="https://example.invalid/";
        roundtrip(prefix+"a".repeat(LocalQrImageEncoder.MAX_UTF8_BYTES-prefix.length()));
    }
    @Test void exactMaximumUtf8BytesRoundtrip() throws Exception {
        String prefix="https://example.invalid/?q=你好🙂";
        int bytes=prefix.getBytes(StandardCharsets.UTF_8).length;
        roundtrip(prefix+"x".repeat(LocalQrImageEncoder.MAX_UTF8_BYTES-bytes));
    }
    @Test void oneByteAboveLimitFailsExplicitlyWithoutTruncating() {
        String prefix="https://example.invalid/";
        assertThrows(IllegalArgumentException.class,()->LocalQrImageEncoder.encode(prefix+"a".repeat(LocalQrImageEncoder.MAX_UTF8_BYTES-prefix.length()+1)));
    }
    @ParameterizedTest @ValueSource(strings={"", " ", "/relative", "javascript:alert(1)", "https://", "https://example.invalid/a b"})
    void invalidInputRejected(String text) {assertThrows(IllegalArgumentException.class,()->LocalQrImageEncoder.encode(text));}
    @Test void nullAndMalformedSurrogateRejected() {
        assertThrows(IllegalArgumentException.class,()->LocalQrImageEncoder.encode(null));
        assertThrows(IllegalArgumentException.class,()->LocalQrImageEncoder.encode("https://example.invalid/"+(char)0xd800));
    }
}
