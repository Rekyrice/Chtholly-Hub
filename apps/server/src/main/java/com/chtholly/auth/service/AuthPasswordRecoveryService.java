package com.chtholly.auth.service;

import com.chtholly.auth.api.dto.PasswordResetRequest;
import com.chtholly.auth.token.RefreshTokenStore;
import com.chtholly.auth.verification.VerificationScene;
import com.chtholly.auth.verification.VerificationService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.user.domain.User;
import com.chtholly.user.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Resets verified credentials and revokes all existing refresh sessions. */
@Service
public class AuthPasswordRecoveryService {

    private final UserService userService;
    private final VerificationService verificationService;
    private final PasswordEncoder passwordEncoder;
    private final AuthIdentityPolicy identityPolicy;
    private final RefreshTokenStore refreshTokenStore;

    /** Creates the password recovery use case. */
    public AuthPasswordRecoveryService(
            UserService userService,
            VerificationService verificationService,
            PasswordEncoder passwordEncoder,
            AuthIdentityPolicy identityPolicy,
            RefreshTokenStore refreshTokenStore) {
        this.userService = userService;
        this.verificationService = verificationService;
        this.passwordEncoder = passwordEncoder;
        this.identityPolicy = identityPolicy;
        this.refreshTokenStore = refreshTokenStore;
    }

    /** Verifies ownership, updates the password, and invalidates all sessions. */
    public void resetPassword(PasswordResetRequest request) {
        identityPolicy.validateIdentifier(
                request.identifierType(), request.identifier());
        identityPolicy.validatePassword(request.newPassword());
        String identifier = identityPolicy.normalizeIdentifier(
                request.identifierType(), request.identifier());
        User user = identityPolicy.findUserByIdentifier(
                        request.identifierType(), identifier)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND));
        AuthVerificationPolicy.requireSuccess(verificationService.verify(
                VerificationScene.RESET_PASSWORD,
                identifier,
                request.code()));
        user.setPasswordHash(passwordEncoder.encode(
                request.newPassword().trim()));
        userService.updatePassword(user);
        refreshTokenStore.revokeAll(user.getId());
    }
}
