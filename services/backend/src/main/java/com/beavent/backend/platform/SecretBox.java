package com.beavent.backend.platform;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Seals small secrets (a payout account number) so the database never holds them in the clear: AES-256-GCM with a key
 * from {@code beavent.secrets.key} (base64, 32 bytes; a secret manager in production). The sealed text,
 * {@code box:v1:<base64>}, is what columns named {@code *_secret_ref} hold until a vault replaces it. Only the code that
 * needs the real value opens it; screens show a masked form.
 */
@Component
public class SecretBox {

    private static final String PREFIX = "box:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    SecretBox(@Value("${beavent.secrets.key}") String keyBase64) {
        byte[] bytes = Base64.getDecoder().decode(keyBase64.strip());
        if (bytes.length != 32) {
            throw new IllegalStateException(
                    "beavent.secrets.key must be 32 bytes (base64), e.g. `openssl rand -base64 32`");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public String seal(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + Base64.getEncoder()
                            .encodeToString(ByteBuffer.allocate(iv.length + sealed.length)
                                    .put(iv)
                                    .put(sealed)
                                    .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not seal a secret", e);
        }
    }

    public String open(String sealed) {
        if (!sealed.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Not a sealed secret");
        }
        try {
            byte[] all = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not open a sealed secret (wrong key?)", e);
        }
    }

    /** {@code ••••6789}: what screens and messages show of an account number. */
    public static String mask(String accountNumber) {
        String digits = accountNumber.strip();
        return "••••" + digits.substring(Math.max(0, digits.length() - 4));
    }
}
