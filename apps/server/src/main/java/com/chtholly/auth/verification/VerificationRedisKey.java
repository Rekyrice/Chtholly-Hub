package com.chtholly.auth.verification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Builds privacy-preserving Redis hash tags for verification state. */
final class VerificationRedisKey {

    private VerificationRedisKey() {
    }

    static String digest(VerificationScene scene, String identifier) {
        Objects.requireNonNull(scene, "scene");
        return digest(scene.name(), identifier);
    }

    static String digest(String scene, String identifier) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(identifier, "identifier");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(
                    (scene + '\0' + identifier).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
