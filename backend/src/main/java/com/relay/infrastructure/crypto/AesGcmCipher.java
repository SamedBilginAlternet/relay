package com.relay.infrastructure.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for {@code Connection.config}. The key comes from {@code APP_ENCRYPTION_KEY}
 * (any string or base64 blob — it is hashed to 256 bits). Output is base64(iv‖ciphertext‖tag).
 */
public class AesGcmCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY is required to store connections");
        }
        this.key = new SecretKeySpec(derive(secret), "AES");
    }

    private static byte[] derive(String secret) {
        try {
            byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
            try {
                byte[] decoded = Base64.getDecoder().decode(secret.trim());
                if (decoded.length == 32) {
                    return decoded;
                }
            } catch (IllegalArgumentException ignored) {
                // not base64 — hash the raw string instead
            }
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (Exception e) {
            throw new IllegalStateException("cannot derive encryption key", e);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decryption failed — wrong APP_ENCRYPTION_KEY?", e);
        }
    }
}
