package io.seekflux.recommendation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SignedRecommendationCursorCodec {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    public SignedRecommendationCursorCodec(String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("recommendation cursor secret must contain at least 16 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String encode(int offset, String requestFingerprint, Instant expiresAt) {
        if (offset < 0) {
            throw new IllegalArgumentException("cursor offset must not be negative");
        }
        String payload = "v1|" + offset + "|" + expiresAt.getEpochSecond() + "|" + requestFingerprint;
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes)
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payloadBytes));
    }

    public int decode(String cursor, String requestFingerprint, Instant now) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2) {
                throw invalidCursor();
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
            byte[] suppliedSignature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(sign(payloadBytes), suppliedSignature)) {
                throw invalidCursor();
            }
            String[] fields = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 4 || !"v1".equals(fields[0]) || !requestFingerprint.equals(fields[3])) {
                throw invalidCursor();
            }
            int offset = Integer.parseInt(fields[1]);
            long expiresAt = Long.parseLong(fields[2]);
            if (offset < 0 || now.getEpochSecond() > expiresAt) {
                throw invalidCursor();
            }
            return offset;
        } catch (IllegalArgumentException exception) {
            if ("invalid or expired recommendation cursor".equals(exception.getMessage())) {
                throw exception;
            }
            throw invalidCursor();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot sign recommendation cursor", exception);
        }
    }

    private static IllegalArgumentException invalidCursor() {
        return new IllegalArgumentException("invalid or expired recommendation cursor");
    }
}
