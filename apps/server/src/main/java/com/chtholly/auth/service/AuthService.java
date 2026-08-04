package com.chtholly.auth.service;

import com.chtholly.auth.api.dto.AuthResponse;
import com.chtholly.auth.api.dto.AuthUserResponse;
import com.chtholly.auth.api.dto.LoginRequest;
import com.chtholly.auth.api.dto.PasswordResetRequest;
import com.chtholly.auth.api.dto.RegisterRequest;
import com.chtholly.auth.api.dto.SendCodeRequest;
import com.chtholly.auth.api.dto.SendCodeResponse;
import com.chtholly.auth.api.dto.TokenRefreshRequest;
import com.chtholly.auth.api.dto.TokenResponse;
import com.chtholly.auth.model.ClientInfo;
import org.springframework.stereotype.Service;

/**
 * Compatibility facade for authentication HTTP adapters.
 *
 * <p>Each public operation delegates to one cohesive application use case so
 * controllers retain their existing contract without coupling to security,
 * token, audit, or persistence details.</p>
 */
@Service
public class AuthService {

    private final AuthVerificationCodeService verificationCodeService;
    private final AuthRegistrationService registrationService;
    private final AuthLoginService loginService;
    private final AuthTokenLifecycleService tokenLifecycleService;
    private final AuthPasswordRecoveryService passwordRecoveryService;
    private final AuthCurrentUserService currentUserService;

    /** Creates the compatibility facade from authentication use cases. */
    public AuthService(
            AuthVerificationCodeService verificationCodeService,
            AuthRegistrationService registrationService,
            AuthLoginService loginService,
            AuthTokenLifecycleService tokenLifecycleService,
            AuthPasswordRecoveryService passwordRecoveryService,
            AuthCurrentUserService currentUserService) {
        this.verificationCodeService = verificationCodeService;
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.tokenLifecycleService = tokenLifecycleService;
        this.passwordRecoveryService = passwordRecoveryService;
        this.currentUserService = currentUserService;
    }

    /** Sends a verification code after scene-specific eligibility checks. */
    public SendCodeResponse sendCode(SendCodeRequest request) {
        return verificationCodeService.sendCode(request);
    }

    /** Registers a user and returns the existing authentication response. */
    public AuthResponse register(
            RegisterRequest request,
            ClientInfo clientInfo) {
        return registrationService.register(request, clientInfo);
    }

    /** Authenticates one password or verification-code login. */
    public AuthResponse login(LoginRequest request, ClientInfo clientInfo) {
        return loginService.login(request, clientInfo);
    }

    /** Rotates a valid refresh token. */
    public TokenResponse refresh(TokenRefreshRequest request) {
        return tokenLifecycleService.refresh(request);
    }

    /** Revokes one valid refresh token while tolerating malformed input. */
    public void logout(String refreshToken) {
        tokenLifecycleService.logout(refreshToken);
    }

    /** Resets a verified password and invalidates existing refresh sessions. */
    public void resetPassword(PasswordResetRequest request) {
        passwordRecoveryService.resetPassword(request);
    }

    /** Returns the current authenticated user's summary. */
    public AuthUserResponse me(long userId) {
        return currentUserService.me(userId);
    }
}
