package com.chtholly.auth.verification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingCodeSenderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void localDeliveryAdapterIsAvailableOnlyInExplicitDevelopmentProfiles() {
        Profile profile = LoggingCodeSender.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder("dev", "test");
    }

    @Test
    void localDeliveryFileKeepsTheCodeButApplicationLogsDoNot() throws Exception {
        Path deliveryFile = Path.of(
                "target/test-output/auth/dev-verification.log")
                .toAbsolutePath()
                .normalize();
        Files.deleteIfExists(deliveryFile);
        LoggingCodeSender sender = new LoggingCodeSender(deliveryFile);
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                        LoggingCodeSender.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.TRACE);
        logger.addAppender(appender);
        try {
            sender.sendCode(
                    VerificationScene.LOGIN,
                    "owner@example.com",
                    "829104",
                    5);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }

        assertThat(Files.readString(deliveryFile))
                .contains("owner@example.com")
                .contains("829104");
        assertThat(appender.list)
                .hasSize(1)
                .allSatisfy(event -> assertThat(event.getFormattedMessage())
                        .doesNotContain("owner@example.com")
                        .doesNotContain("829104"));
        Files.deleteIfExists(deliveryFile);
    }

    @Test
    void localDeliveryFailureIsPropagatedForStoreCompensation() throws Exception {
        Path blockingFile = tempDirectory.resolve("not-a-directory");
        Files.writeString(blockingFile, "occupied");
        LoggingCodeSender sender = new LoggingCodeSender(
                blockingFile.resolve("verification.log"));

        assertThatThrownBy(() -> sender.sendCode(
                        VerificationScene.LOGIN,
                        "owner@example.com",
                        "829104",
                        5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Local verification delivery failed")
                .hasCauseInstanceOf(java.io.IOException.class);
    }
}
