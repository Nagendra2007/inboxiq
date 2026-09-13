package com.inboxiq.security;

import com.inboxiq.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts/decrypts OAuth tokens with AES-256-GCM before they are persisted,
 * using a key that lives only in the {@code TOKEN_ENCRYPTION_KEY} environment
 * variable — never in the database and never in source control.
 *
 * This is the ONLY place in the codebase that should touch a plaintext OAuth
 * token outside of the moment it's used to call Google's APIs. Callers must
 * treat the ciphertext this returns as opaque and never attempt to store or
 * transmit a decrypted token anywhere but directly into a Google API client.
 *
 * Format: base64( 12-byte random IV || GCM ciphertext+tag ). A fresh random
 * IV is generated per encryption call, which is required for GCM's security
 * guarantees when reusing a key across many messages.
 */
@Service
public class TokenEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final AppProperties appProperties;
    private SecretKey key;

    public TokenEncryptionService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostConstruct
    void init() {
        String configured = appProperties.getSecurity().getTokenEncryptionKey();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY is not set. Generate one with `openssl rand -base64 32` "
                    + "and set it in your .env before starting the backend — OAuth tokens are "
                    + "encrypted at rest and cannot be stored without this key.");
        }
        byte[] keyBytes = Base64.getDecoder().decode(configured);
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY must decode to 16, 24, or 32 bytes (AES-128/192/256). "
                    + "Generate a valid one with `openssl rand -base64 32`.");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // Never include the plaintext or any part of it in the exception message/logs.
            throw new IllegalStateException("Failed to encrypt token", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt token — key may have changed or data is corrupt", e);
        }
    }
}
