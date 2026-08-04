package com.chtholly.auth.service;

import com.chtholly.auth.verification.VerificationCheckResult;
import com.chtholly.auth.verification.VerificationCodeStatus;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Characterizes the public error contract for verification-code outcomes. */
class AuthVerificationPolicyTest {

    @Test
    void successReturnsNormally() {
        AuthVerificationPolicy.requireSuccess(result(
                VerificationCodeStatus.SUCCESS));
    }

    @ParameterizedTest
    @MethodSource("failureMappings")
    void failureStatusesKeepTheirStableErrorCodes(
            VerificationCodeStatus status,
            ErrorCode errorCode) {
        BusinessException failure = catchThrowableOfType(
                () -> AuthVerificationPolicy.requireSuccess(result(status)),
                BusinessException.class);

        assertThat(failure.getErrorCode()).isEqualTo(errorCode);
    }

    private static Stream<Arguments> failureMappings() {
        return Stream.of(
                Arguments.of(
                        VerificationCodeStatus.NOT_FOUND,
                        ErrorCode.VERIFICATION_NOT_FOUND),
                Arguments.of(
                        VerificationCodeStatus.EXPIRED,
                        ErrorCode.VERIFICATION_NOT_FOUND),
                Arguments.of(
                        VerificationCodeStatus.MISMATCH,
                        ErrorCode.VERIFICATION_MISMATCH),
                Arguments.of(
                        VerificationCodeStatus.TOO_MANY_ATTEMPTS,
                        ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS));
    }

    private static VerificationCheckResult result(
            VerificationCodeStatus status) {
        return new VerificationCheckResult(status, 1, 5);
    }
}
