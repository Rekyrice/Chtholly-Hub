package com.chtholly.auth.service;

import com.chtholly.auth.api.dto.PasswordResetRequest;
import com.chtholly.auth.config.AuthProperties;
import com.chtholly.auth.model.IdentifierType;
import com.chtholly.auth.token.RefreshTokenStore;
import com.chtholly.auth.verification.VerificationCheckResult;
import com.chtholly.auth.verification.VerificationCodeStatus;
import com.chtholly.auth.verification.VerificationService;
import com.chtholly.user.domain.User;
import com.chtholly.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthPasswordRecoveryServiceTest {

    @Mock private UserService userService;
    @Mock private VerificationService verificationService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenStore refreshTokenStore;

    @Test
    void resetPasswordDoesNotDependOnRedisRevocation() {
        AuthProperties properties = new AuthProperties();
        properties.getPassword().setMinLength(8);
        AuthIdentityPolicy identityPolicy =
                new AuthIdentityPolicy(userService, properties);
        AuthPasswordRecoveryService service =
                new AuthPasswordRecoveryService(
                        userService,
                        verificationService,
                        passwordEncoder,
                        identityPolicy);
        User user = User.builder()
                .id(7L)
                .email("owner@example.com")
                .build();
        when(userService.findByEmail("owner@example.com"))
                .thenReturn(Optional.of(user));
        when(verificationService.verify(
                com.chtholly.auth.verification.VerificationScene.RESET_PASSWORD,
                "owner@example.com",
                "123456"))
                .thenReturn(new VerificationCheckResult(
                        VerificationCodeStatus.SUCCESS, 0, 5));
        when(passwordEncoder.encode("NewPass123"))
                .thenReturn("encoded-password");

        service.resetPassword(new PasswordResetRequest(
                IdentifierType.EMAIL,
                "OWNER@example.com",
                "123456",
                "NewPass123"));

        verify(userService).updatePasswordAndAdvanceRefreshSessionEpoch(
                7L, "encoded-password");
        verifyNoInteractions(refreshTokenStore);
    }
}
