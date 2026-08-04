package com.chtholly.auth.service;

import com.chtholly.admin.security.UserBanService;
import com.chtholly.auth.api.dto.AuthResponse;
import com.chtholly.auth.api.dto.LoginRequest;
import com.chtholly.auth.audit.LoginFailureReason;
import com.chtholly.auth.audit.LoginLogService;
import com.chtholly.auth.model.ClientInfo;
import com.chtholly.auth.model.IdentifierType;
import com.chtholly.auth.security.LoginFailureGuard;
import com.chtholly.auth.verification.VerificationScene;
import com.chtholly.auth.verification.VerificationService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.user.domain.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

/** Authenticates password or verification-code login attempts. */
@Service
public class AuthLoginService {

    private final VerificationService verificationService;
    private final PasswordEncoder passwordEncoder;
    private final AuthIdentityPolicy identityPolicy;
    private final AuthTokenLifecycleService tokenLifecycleService;
    private final LoginLogService loginLogService;
    private final LoginFailureGuard loginFailureGuard;
    private final UserBanService userBanService;

    /** Creates the login use case. */
    public AuthLoginService(
            VerificationService verificationService,
            PasswordEncoder passwordEncoder,
            AuthIdentityPolicy identityPolicy,
            AuthTokenLifecycleService tokenLifecycleService,
            LoginLogService loginLogService,
            LoginFailureGuard loginFailureGuard,
            UserBanService userBanService) {
        this.verificationService = verificationService;
        this.passwordEncoder = passwordEncoder;
        this.identityPolicy = identityPolicy;
        this.tokenLifecycleService = tokenLifecycleService;
        this.loginLogService = loginLogService;
        this.loginFailureGuard = loginFailureGuard;
        this.userBanService = userBanService;
    }

    /** Authenticates one login while preserving guard and audit side effects. */
    public AuthResponse login(LoginRequest request, ClientInfo clientInfo) {
        String identifier = resolveLoginIdentifier(request);
        try {
            loginFailureGuard.assertNotLocked(identifier, clientInfo.ip());
        } catch (BusinessException failure) {
            loginLogService.recordFailure(
                    null,
                    identifier,
                    "PASSWORD",
                    clientInfo.ip(),
                    clientInfo.userAgent(),
                    LoginFailureReason.ACCOUNT_LOCKED);
            throw failure;
        }

        Optional<User> userOptional = identityPolicy.findUserByIdentifier(
                request.identifierType(), identifier);
        if (userOptional.isEmpty()) {
            loginLogService.recordFailure(
                    null,
                    identifier,
                    resolveLoginChannel(request),
                    clientInfo.ip(),
                    clientInfo.userAgent(),
                    LoginFailureReason.ACCOUNT_NOT_FOUND);
            if (StringUtils.hasText(request.password())) {
                loginFailureGuard.onFailure(identifier, clientInfo.ip());
            }
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND);
        }

        User user = userOptional.get();
        String channel = authenticate(request, clientInfo, identifier, user);
        userBanService.assertNotBanned(user);
        loginFailureGuard.onSuccess(identifier);
        var token = tokenLifecycleService.issue(user);
        loginLogService.recordSuccess(
                user.getId(),
                identifier,
                channel,
                clientInfo.ip(),
                clientInfo.userAgent());
        return new AuthResponse(AuthResponseMapper.toUser(user), token);
    }

    private String authenticate(
            LoginRequest request,
            ClientInfo clientInfo,
            String identifier,
            User user) {
        if (StringUtils.hasText(request.password())) {
            if (!StringUtils.hasText(user.getPasswordHash())
                    || !passwordEncoder.matches(
                            request.password(), user.getPasswordHash())) {
                loginLogService.recordFailure(
                        user.getId(),
                        identifier,
                        "PASSWORD",
                        clientInfo.ip(),
                        clientInfo.userAgent(),
                        LoginFailureReason.WRONG_PASSWORD);
                loginFailureGuard.onFailure(identifier, clientInfo.ip());
                throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
            }
            return "PASSWORD";
        }
        if (StringUtils.hasText(request.code())) {
            try {
                AuthVerificationPolicy.requireSuccess(verificationService.verify(
                        VerificationScene.LOGIN,
                        identifier,
                        request.code()));
            } catch (BusinessException failure) {
                loginLogService.recordFailure(
                        user.getId(),
                        identifier,
                        "CODE",
                        clientInfo.ip(),
                        clientInfo.userAgent(),
                        null);
                loginFailureGuard.onFailure(identifier, clientInfo.ip());
                throw failure;
            }
            return "CODE";
        }
        throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "请提供验证码或密码");
    }

    private String resolveLoginIdentifier(LoginRequest request) {
        if (request.identifierType() == IdentifierType.HANDLE) {
            String handle = identityPolicy.normalizeHandle(
                    request.identifier());
            identityPolicy.validateHandle(handle);
            return handle;
        }
        identityPolicy.validateIdentifier(
                request.identifierType(), request.identifier());
        return identityPolicy.normalizeIdentifier(
                request.identifierType(), request.identifier());
    }

    private String resolveLoginChannel(LoginRequest request) {
        return StringUtils.hasText(request.password()) ? "PASSWORD" : "CODE";
    }
}
