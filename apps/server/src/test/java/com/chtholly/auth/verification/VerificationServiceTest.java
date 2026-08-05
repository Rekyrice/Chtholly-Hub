package com.chtholly.auth.verification;

import com.chtholly.auth.config.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {

    @Mock
    private VerificationCodeStore codeStore;
    @Mock
    private CodeSender codeSender;
    @Mock
    private VerificationSendGuard sendGuard;

    private VerificationService service;
    private VerificationSendGuard.Reservation reservation;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.getVerification().setSendInterval(Duration.ZERO);
        properties.getVerification().setDailyLimit(0);
        reservation = new VerificationSendGuard.Reservation(
                List.of("interval", "daily"), "nonce", false, false);
        when(sendGuard.reserve(
                org.mockito.ArgumentMatchers.any(),
                anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(reservation);
        service = new VerificationService(
                codeStore, codeSender, sendGuard, properties);
    }

    @Test
    void deliveryFailureInvalidatesTheUndeliveredCode() {
        IllegalStateException deliveryFailure =
                new IllegalStateException("delivery unavailable");
        doThrow(deliveryFailure).when(codeSender).sendCode(
                eq(VerificationScene.RESET_PASSWORD),
                eq("owner@example.com"),
                anyString(),
                eq(5));

        assertThatThrownBy(() -> service.sendCode(
                        VerificationScene.RESET_PASSWORD,
                        "owner@example.com"))
                .isSameAs(deliveryFailure);

        ArgumentCaptor<VerificationCodeStore.IssuedCode> issuedCode =
                ArgumentCaptor.forClass(VerificationCodeStore.IssuedCode.class);
        verify(codeStore).saveCode(
                eq(VerificationScene.RESET_PASSWORD.name()),
                eq("owner@example.com"),
                issuedCode.capture(),
                eq(Duration.ofMinutes(5)),
                eq(5));
        verify(codeStore).invalidateIfCurrent(
                VerificationScene.RESET_PASSWORD.name(),
                "owner@example.com",
                issuedCode.getValue().version());
        verify(sendGuard).compensate(reservation);
    }

    @Test
    void compensationFailureDoesNotHideTheDeliveryFailure() {
        IllegalStateException deliveryFailure =
                new IllegalStateException("delivery unavailable");
        IllegalStateException compensationFailure =
                new IllegalStateException("store unavailable");
        doThrow(deliveryFailure).when(codeSender).sendCode(
                eq(VerificationScene.LOGIN),
                eq("owner@example.com"),
                anyString(),
                eq(5));
        doThrow(compensationFailure).when(codeStore).invalidateIfCurrent(
                eq(VerificationScene.LOGIN.name()),
                eq("owner@example.com"),
                org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.sendCode(
                        VerificationScene.LOGIN,
                        "owner@example.com"))
                .isSameAs(deliveryFailure)
                .satisfies(exception ->
                        org.assertj.core.api.Assertions.assertThat(
                                        exception.getSuppressed())
                                .containsExactly(compensationFailure));
        verify(sendGuard).compensate(reservation);
    }

    @Test
    void storageFailureConditionallyCleansAnUnknownWriteAndReleasesQuota() {
        IllegalStateException storageFailure =
                new IllegalStateException("store unavailable");
        doThrow(storageFailure).when(codeStore).saveCode(
                eq(VerificationScene.REGISTER.name()),
                eq("owner@example.com"),
                org.mockito.ArgumentMatchers.any(
                        VerificationCodeStore.IssuedCode.class),
                eq(Duration.ofMinutes(5)),
                eq(5));

        assertThatThrownBy(() -> service.sendCode(
                        VerificationScene.REGISTER,
                        "owner@example.com"))
                .isSameAs(storageFailure);

        verify(sendGuard).compensate(reservation);
        verify(codeStore).invalidateIfCurrent(
                eq(VerificationScene.REGISTER.name()),
                eq("owner@example.com"),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void quotaCompensationFailureDoesNotHideTheStorageFailure() {
        IllegalStateException storageFailure =
                new IllegalStateException("store unavailable");
        IllegalStateException quotaFailure =
                new IllegalStateException("quota unavailable");
        doThrow(storageFailure).when(codeStore).saveCode(
                eq(VerificationScene.REGISTER.name()),
                eq("owner@example.com"),
                org.mockito.ArgumentMatchers.any(
                        VerificationCodeStore.IssuedCode.class),
                eq(Duration.ofMinutes(5)),
                eq(5));
        doThrow(quotaFailure).when(sendGuard).compensate(reservation);

        assertThatThrownBy(() -> service.sendCode(
                        VerificationScene.REGISTER,
                        "owner@example.com"))
                .isSameAs(storageFailure)
                .satisfies(exception ->
                        org.assertj.core.api.Assertions.assertThat(
                                        exception.getSuppressed())
                                .containsExactly(quotaFailure));
    }

    @Test
    void successfulDeliveryKeepsConsumedQuota() {
        service.sendCode(VerificationScene.LOGIN, "owner@example.com");

        verify(sendGuard, never()).compensate(reservation);
    }
}
