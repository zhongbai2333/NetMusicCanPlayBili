package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BiliCredentialCodecTest {
    private static final String MACHINE_A = "linux:0123456789abcdef0123456789abcdef";
    private static final String MACHINE_B = "linux:fedcba9876543210fedcba9876543210";
    private static final BiliCredentialStore.Credentials CREDENTIALS = new BiliCredentialStore.Credentials(
            "secret-sessdata", "SESSDATA=secret-sessdata; bili_jct=secret-csrf; buvid3=device-cookie");

    @Test
    void roundTripsWithoutLeavingPlaintextInEnvelope() throws Exception {
        byte[] encoded = BiliCredentialCodec.encrypt(CREDENTIALS, MACHINE_A, new FixedSecureRandom());

        assertEquals(CREDENTIALS, BiliCredentialCodec.decrypt(encoded, MACHINE_A));
        String envelope = new String(encoded, StandardCharsets.UTF_8);
        assertFalse(envelope.contains(CREDENTIALS.sessdata()));
        assertFalse(envelope.contains("secret-csrf"));
    }

    @Test
    void eachSaveUsesFreshSaltAndNonce() throws Exception {
        SecureRandom random = new FixedSecureRandom();
        byte[] first = BiliCredentialCodec.encrypt(CREDENTIALS, MACHINE_A, random);
        byte[] second = BiliCredentialCodec.encrypt(CREDENTIALS, MACHINE_A, random);

        assertFalse(Arrays.equals(first, second));
        JsonObject firstEnvelope = parse(first);
        JsonObject secondEnvelope = parse(second);
        assertFalse(firstEnvelope.get("salt").getAsString().equals(secondEnvelope.get("salt").getAsString()));
        assertFalse(firstEnvelope.get("nonce").getAsString().equals(secondEnvelope.get("nonce").getAsString()));
    }

    @Test
    void anotherMachineCannotDecryptCredentials() throws Exception {
        byte[] encoded = BiliCredentialCodec.encrypt(CREDENTIALS, MACHINE_A, new FixedSecureRandom());

        assertThrows(GeneralSecurityException.class, () -> BiliCredentialCodec.decrypt(encoded, MACHINE_B));
    }

    @Test
    void authenticatedCiphertextTamperingFailsClosed() throws Exception {
        byte[] encoded = BiliCredentialCodec.encrypt(CREDENTIALS, MACHINE_A, new FixedSecureRandom());
        JsonObject envelope = parse(encoded);
        byte[] ciphertext = Base64.getDecoder().decode(envelope.get("ciphertext").getAsString());
        ciphertext[0] ^= 0x01;
        envelope.addProperty("ciphertext", Base64.getEncoder().withoutPadding().encodeToString(ciphertext));

        assertThrows(GeneralSecurityException.class,
                () -> BiliCredentialCodec.decrypt(envelope.toString().getBytes(StandardCharsets.UTF_8), MACHINE_A));
    }

    @Test
    void unsupportedMetadataAndMalformedDataAreRejected() throws Exception {
        byte[] encoded = BiliCredentialCodec.encrypt(CREDENTIALS, MACHINE_A, new FixedSecureRandom());
        JsonObject unsupportedVersionEnvelope = parse(encoded);
        unsupportedVersionEnvelope.addProperty("version", 2);
        assertThrows(GeneralSecurityException.class,
                () -> BiliCredentialCodec.decrypt(
                        unsupportedVersionEnvelope.toString().getBytes(StandardCharsets.UTF_8), MACHINE_A));

        JsonObject invalidBase64Envelope = parse(encoded);
        invalidBase64Envelope.addProperty("salt", "not base64!");
        assertThrows(GeneralSecurityException.class, () -> BiliCredentialCodec.decrypt(
                invalidBase64Envelope.toString().getBytes(StandardCharsets.UTF_8), MACHINE_A));
    }

    @Test
    void blankMachineIdentityAndOversizedSecretsAreRejected() {
        assertThrows(GeneralSecurityException.class,
                () -> BiliCredentialCodec.encrypt(CREDENTIALS, " ", new FixedSecureRandom()));
        BiliCredentialStore.Credentials oversized = new BiliCredentialStore.Credentials(
                "x".repeat(32 * 1024 + 1), "");
        assertThrows(GeneralSecurityException.class,
                () -> BiliCredentialCodec.encrypt(oversized, MACHINE_A, new FixedSecureRandom()));
        assertThrows(GeneralSecurityException.class,
                () -> BiliCredentialCodec.decrypt(new byte[BiliCredentialCodec.MAX_ENVELOPE_BYTES + 1], MACHINE_A));
    }

    private static JsonObject parse(byte[] encoded) {
        return JsonParser.parseString(new String(encoded, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static final class FixedSecureRandom extends SecureRandom {
        private int next = 1;

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) next++;
            }
        }
    }
}
