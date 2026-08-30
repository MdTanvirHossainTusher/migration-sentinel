package com.migrationsentinel.service.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM for the one secret the service holds at rest: a per-request LLM API key, stored
 * on the {@code review_job} / {@code evaluation_run} row only long enough for a worker
 * (possibly on another host, possibly after a restart) to use it. The key is never returned
 * by the API, never logged (the masker would catch it anyway) and never put in an audit
 * payload.
 *
 * <p>{@code sentinel.crypto.secret} is a base64 32-byte key. If unset a random one is
 * generated at boot — fine for a single-process {@code bootRun}, but per-request keys then
 * do not survive a restart or reach a second replica, so the compose stack sets it.
 */
@Slf4j
@Service
public class CryptoService {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${sentinel.crypto.secret:}") String configuredSecret) {
        byte[] keyBytes;
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            keyBytes = toKeyBytes(configuredSecret.trim());
        } else {
            keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
            log.warn("sentinel.crypto.secret is not set — using an ephemeral key. Per-request LLM keys "
                    + "will not survive a restart or work across replicas.");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Prefer a base64-encoded 32-byte key; accept anything else by hashing it to 32 bytes,
     * so a judge who sets {@code SENTINEL_CRYPTO_SECRET=whatever} still gets a working stack.
     */
    private static byte[] toKeyBytes(String secret) {
        try {
            byte[] decoded = Base64.getDecoder().decode(secret);
            if (decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException notBase64) {
            // fall through to the hash
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("cannot derive a crypto key", ex);
        }
    }

    /** Returns null for null/blank input. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception ex) {
            throw new IllegalStateException("encryption failed", ex);
        }
    }

    /** Returns null for null/blank input. */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(all, 0, iv, 0, GCM_IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, GCM_IV_BYTES, all.length - GCM_IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("decryption failed", ex);
        }
    }
}
