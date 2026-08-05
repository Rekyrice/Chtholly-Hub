package com.chtholly.auth.verification;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative store for short-lived, one-time verification codes.
 *
 * <p>Implementations must make both writes and verification attempts atomic.
 * A versioned code allows a failed delivery attempt to remove only the value
 * that it wrote, without deleting a newer code produced by another request.</p>
 */
public interface VerificationCodeStore {

    /**
     * Atomically stores a new versioned code and its expiration metadata.
     *
     * @param scene verification-code purpose
     * @param identifier phone number or email destination
     * @param issuedCode generated value and unique write version
     * @param ttl usable lifetime
     * @param maxAttempts maximum failed verification attempts
     */
    void saveCode(
            String scene,
            String identifier,
            IssuedCode issuedCode,
            Duration ttl,
            int maxAttempts);

    /**
     * Atomically consumes a matching code or records one failed attempt.
     *
     * @param scene verification-code purpose
     * @param identifier phone number or email destination
     * @param code user-provided value
     * @return verification outcome and attempt state
     */
    VerificationCheckResult verify(String scene, String identifier, String code);

    /**
     * Invalidates the current code regardless of its write version.
     *
     * @param scene verification-code purpose
     * @param identifier phone number or email destination
     */
    void invalidate(String scene, String identifier);

    /**
     * Invalidates a code only when the stored version still belongs to the caller.
     *
     * @param scene verification-code purpose
     * @param identifier phone number or email destination
     * @param version unique version supplied when the code was saved
     * @return whether this call removed the current code
     */
    boolean invalidateIfCurrent(String scene, String identifier, String version);

    /** A verification-code value paired with a unique write version. */
    record IssuedCode(String value, String version) {

        public IssuedCode {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Verification code must not be blank");
            }
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("Verification code version must not be blank");
            }
        }

        /**
         * Creates a code write with a collision-resistant ownership version.
         *
         * @param value generated verification-code value
         * @return versioned code ready for persistence
         */
        public static IssuedCode issue(String value) {
            Objects.requireNonNull(value, "value");
            return new IssuedCode(value, UUID.randomUUID().toString());
        }

        /** Keeps the one-time secret out of incidental logs and assertions. */
        @Override
        public String toString() {
            return "IssuedCode[value=<redacted>, version=" + version + "]";
        }
    }
}
