package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** Versioned, dependency-free encrypted representation of Bilibili credentials. */
final class BiliCredentialCodec {
    static final int MAX_ENVELOPE_BYTES = 64 * 1024;
    static final int SALT_BYTES = 16;
    static final int NONCE_BYTES = 12;

    private static final int FORMAT_VERSION = 1;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_BYTES = 32;
    private static final int MAX_SECRET_CHARS = 32 * 1024;
    private static final String FORMAT = "ncpb-bili-credentials";
    private static final String KDF = "HKDF-HMAC-SHA256";
    private static final String CIPHER = "AES-256-GCM";
    private static final byte[] CONTEXT =
            "net_music_can_play_bili:bili-credentials:v1".getBytes(StandardCharsets.UTF_8);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private BiliCredentialCodec() {
    }

    static byte[] encrypt(BiliCredentialStore.Credentials credentials, String machineIdentity, SecureRandom random)
            throws GeneralSecurityException {
        Objects.requireNonNull(credentials, "credentials");
        requireMachineIdentity(machineIdentity);
        Objects.requireNonNull(random, "random");
        requireSecretLengths(credentials);

        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(salt);
        random.nextBytes(nonce);

        JsonObject payload = new JsonObject();
        payload.addProperty("schemaVersion", FORMAT_VERSION);
        payload.addProperty("sessdata", credentials.sessdata());
        payload.addProperty("webCookie", credentials.webCookie());
        byte[] plaintext = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        byte[] key = deriveKey(machineIdentity, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(CONTEXT);
            byte[] ciphertext = cipher.doFinal(plaintext);

            JsonObject envelope = new JsonObject();
            envelope.addProperty("format", FORMAT);
            envelope.addProperty("version", FORMAT_VERSION);
            envelope.addProperty("kdf", KDF);
            envelope.addProperty("cipher", CIPHER);
            envelope.addProperty("salt", Base64.getEncoder().withoutPadding().encodeToString(salt));
            envelope.addProperty("nonce", Base64.getEncoder().withoutPadding().encodeToString(nonce));
            envelope.addProperty("ciphertext", Base64.getEncoder().withoutPadding().encodeToString(ciphertext));
            byte[] encoded = GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_ENVELOPE_BYTES) {
                throw new CredentialFormatException("credential envelope exceeds size limit");
            }
            return encoded;
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    static BiliCredentialStore.Credentials decrypt(byte[] encoded, String machineIdentity)
            throws GeneralSecurityException {
        Objects.requireNonNull(encoded, "encoded");
        requireMachineIdentity(machineIdentity);
        if (encoded.length == 0 || encoded.length > MAX_ENVELOPE_BYTES) {
            throw new CredentialFormatException("credential envelope has invalid size");
        }

        JsonObject envelope = parseObject(encoded, "credential envelope");
        requireLiteral(envelope, "format", FORMAT);
        requireInteger(envelope, "version", FORMAT_VERSION);
        requireLiteral(envelope, "kdf", KDF);
        requireLiteral(envelope, "cipher", CIPHER);
        byte[] salt = decodeBase64(envelope, "salt", SALT_BYTES, SALT_BYTES);
        byte[] nonce = decodeBase64(envelope, "nonce", NONCE_BYTES, NONCE_BYTES);
        byte[] ciphertext = decodeBase64(envelope, "ciphertext", GCM_TAG_BITS / Byte.SIZE,
                MAX_ENVELOPE_BYTES);
        byte[] key = deriveKey(machineIdentity, salt);
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(CONTEXT);
            plaintext = cipher.doFinal(ciphertext);

            JsonObject payload = parseObject(plaintext, "credential payload");
            requireInteger(payload, "schemaVersion", FORMAT_VERSION);
            String sessdata = requireString(payload, "sessdata");
            String webCookie = requireString(payload, "webCookie");
            BiliCredentialStore.Credentials credentials =
                    new BiliCredentialStore.Credentials(sessdata, webCookie);
            requireSecretLengths(credentials);
            return credentials;
        } finally {
            Arrays.fill(key, (byte) 0);
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private static byte[] deriveKey(String machineIdentity, byte[] salt) throws GeneralSecurityException {
        byte[] inputKeyMaterial = machineIdentity.getBytes(StandardCharsets.UTF_8);
        byte[] pseudorandomKey = hmac(salt, inputKeyMaterial);
        try {
            Mac expand = Mac.getInstance("HmacSHA256");
            expand.init(new SecretKeySpec(pseudorandomKey, "HmacSHA256"));
            expand.update(CONTEXT);
            expand.update((byte) 1);
            byte[] output = expand.doFinal();
            return output.length == AES_KEY_BYTES ? output : Arrays.copyOf(output, AES_KEY_BYTES);
        } finally {
            Arrays.fill(inputKeyMaterial, (byte) 0);
            Arrays.fill(pseudorandomKey, (byte) 0);
        }
    }

    private static byte[] hmac(byte[] key, byte[] value) throws GeneralSecurityException {
        Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(key, "HmacSHA256"));
        return extract.doFinal(value);
    }

    private static JsonObject parseObject(byte[] bytes, String description) throws CredentialFormatException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new CredentialFormatException(description + " is not an object");
            }
            return parsed.getAsJsonObject();
        } catch (CredentialFormatException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new CredentialFormatException(description + " is not valid JSON", error);
        }
    }

    private static String requireString(JsonObject object, String name) throws CredentialFormatException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new CredentialFormatException("missing or invalid " + name);
        }
        return value.getAsString();
    }

    private static void requireLiteral(JsonObject object, String name, String expected)
            throws CredentialFormatException {
        if (!expected.equals(requireString(object, name))) {
            throw new CredentialFormatException("unsupported " + name);
        }
    }

    private static void requireInteger(JsonObject object, String name, int expected)
            throws CredentialFormatException {
        JsonElement value = object.get(name);
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || value.getAsBigDecimal().stripTrailingZeros().scale() > 0
                    || value.getAsInt() != expected) {
                throw new CredentialFormatException("unsupported " + name);
            }
        } catch (NumberFormatException | ArithmeticException error) {
            throw new CredentialFormatException("invalid " + name, error);
        }
    }

    private static byte[] decodeBase64(JsonObject object, String name, int minimumBytes, int maximumBytes)
            throws CredentialFormatException {
        String encoded = requireString(object, name);
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length < minimumBytes || decoded.length > maximumBytes) {
                throw new CredentialFormatException("invalid " + name + " length");
            }
            return decoded;
        } catch (IllegalArgumentException error) {
            throw new CredentialFormatException("invalid " + name + " encoding", error);
        }
    }

    private static void requireMachineIdentity(String machineIdentity) throws CredentialFormatException {
        if (machineIdentity == null || machineIdentity.isBlank()) {
            throw new CredentialFormatException("machine identity is unavailable");
        }
    }

    private static void requireSecretLengths(BiliCredentialStore.Credentials credentials)
            throws CredentialFormatException {
        if (credentials.sessdata().length() > MAX_SECRET_CHARS
                || credentials.webCookie().length() > MAX_SECRET_CHARS) {
            throw new CredentialFormatException("credential payload exceeds size limit");
        }
    }

    static final class CredentialFormatException extends GeneralSecurityException {
        CredentialFormatException(String message) {
            super(message);
        }

        CredentialFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
