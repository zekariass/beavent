package com.beavent.backend.identity.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Keyed hashing and encryption for identity data. Sub-keys are derived from {@code beavent.auth.secret-key} per
 * purpose, so a leaked OTP hash never helps with authenticator secrets and vice versa.
 */
@Component
class Crypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] otpKey;
    private final byte[] destinationKey;
    private final byte[] tokenKey;
    private final SecretKeySpec secretCipherKey;

    Crypto(IdentityProperties properties) {
        Objects.requireNonNull(properties.secretKey(), "beavent.auth.secret-key is required");
        byte[] master = Base64.getDecoder().decode(properties.secretKey());
        if (master.length < 32) {
            throw new IllegalStateException("beavent.auth.secret-key must be at least 32 bytes (base64)");
        }
        this.otpKey = derive(master, "otp-code");
        this.destinationKey = derive(master, "otp-destination");
        this.tokenKey = derive(master, "opaque-token");
        this.secretCipherKey = new SecretKeySpec(derive(master, "mfa-secret-cipher"), "AES");
    }

    private static byte[] derive(byte[] master, String purpose) {
        return hmac(master, purpose.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    String otpCodeHash(String challengeId, String code) {
        return HexFormat.of().formatHex(hmac(otpKey, (challengeId + ":" + code).getBytes(StandardCharsets.UTF_8)));
    }

    /** Hash of a phone/email used to rate-limit and correlate challenges without storing the raw value. */
    String destinationHash(String normalizedDestination) {
        return HexFormat.of().formatHex(hmac(destinationKey, normalizedDestination.getBytes(StandardCharsets.UTF_8)));
    }

    /** Hash for opaque bearer secrets (refresh tokens, invitation tokens). */
    String tokenHash(String token) {
        return HexFormat.of().formatHex(hmac(tokenKey, token.getBytes(StandardCharsets.UTF_8)));
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 256-bit random token, base64url. */
    static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String randomDigits(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(RANDOM.nextInt(10));
        }
        return code.toString();
    }

    static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** AES-256-GCM; output {@code v1:<base64(iv || ciphertext)>}. */
    String encryptSecret(byte[] plaintext) {
        try {
            byte[] iv = randomBytes(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretCipherKey, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext);
            return "v1:"
                    + Base64.getEncoder()
                            .encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                                    .put(iv)
                                    .put(encrypted)
                                    .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    byte[] decryptSecret(String stored) {
        if (stored == null || !stored.startsWith("v1:")) {
            throw new IllegalStateException("Unsupported secret format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(3));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretCipherKey, new GCMParameterSpec(128, all, 0, 12));
            return cipher.doFinal(all, 12, all.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
