package com.chtholly.auth.verification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Delivers verification codes to a repository-local file in development and tests.
 *
 * <p>This adapter is intentionally unavailable in other profiles. Application
 * logs contain only delivery metadata and never include the destination or
 * code.</p>
 */
@Slf4j
@Component
@Profile({"dev", "test"})
public class LoggingCodeSender implements CodeSender {

    private final Path deliveryFile;

    /** Creates the local delivery adapter using the existing repository log. */
    public LoggingCodeSender() {
        this(devLogFile());
    }

    LoggingCodeSender(Path deliveryFile) {
        this.deliveryFile = deliveryFile.toAbsolutePath().normalize();
    }

    /** Resolves the repository-level development delivery file. */
    private static Path devLogFile() {
        return Path.of("../../logs/dev-verification.log").toAbsolutePath().normalize();
    }

    @Override
    public void sendCode(VerificationScene scene, String identifier, String code, int expireMinutes) {
        try {
            Files.createDirectories(deliveryFile.getParent());
            String line = String.format(
                    "%s | scene=%s | %s | code=%s | 有效 %d 分钟%n",
                    Instant.now(), scene, identifier, code, expireMinutes
            );
            Files.writeString(
                    deliveryFile,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            log.debug(
                    "Verification code written to local delivery file, scene={}, expireMinutes={}",
                    scene,
                    expireMinutes);
        } catch (IOException e) {
            log.warn(
                    "Unable to write local verification delivery file, path={}, errorType={}",
                    deliveryFile,
                    e.getClass().getSimpleName());
            throw new IllegalStateException(
                    "Local verification delivery failed", e);
        }
    }
}
