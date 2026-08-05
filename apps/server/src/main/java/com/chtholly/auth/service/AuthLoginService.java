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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

/** Authenticates password or verification-code login attempts. */
@Service
public class AuthLoginService {

    private static final Logger log =
            LoggerFactory.getLogger(AuthLoginService.class);

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
        String channel = resolveLoginChannel(request);
        try {
            loginFailureGuard.assertNotLocked(identifier, clientInfo.ip());
        } catch (BusinessException failure) {
            recordFailureBestEffort(
                    null,
                    identifier,
                    channel,
                    clientInfo.ip(),
                    clientInfo.userAgent(),
                    LoginFailureReason.ACCOUNT_LOCKED);
            throw failure;
        }

        Optional<User> userOptional = identityPolicy.findUserByIdentifier(
                request.identifierType(), identifier);
        if (userOptional.isEmpty()) {
            loginFailureGuard.onFailure(identifier, clientInfo.ip());
            recordFailureBestEffort(
                    null,
                    identifier,
                    channel,
                    clientInfo.ip(),
                    clientInfo.userAgent(),
                    LoginFailureReason.ACCOUNT_NOT_FOUND);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        long userId = userOptional.get().getId();
        long issueEpoch = tokenLifecycleService.captureIssueEpoch(userId);
        User user = identityPolicy.findUserById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        channel = authenticate(request, clientInfo, identifier, user);
        try {
            userBanService.assertNotBanned(user);
        } catch (BusinessException failure) {
            recordFailureBestEffort(
                    user.getId(),
                    identifier,
                    channel,
                    clientInfo.ip(),
                    clientInfo.userAgent(),
                    LoginFailureReason.USER_BANNED);
            throw failure;
        }
        var token = tokenLifecycleService.issueAtEpoch(user, issueEpoch);
        loginFailureGuard.onSuccess(identifier);
        recordSuccessBestEffort(
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
                loginFailureGuard.onFailure(identifier, clientInfo.ip());
                recordFailureBestEffort(
                        user.getId(),
                        identifier,
                        "PASSWORD",
                        clientInfo.ip(),
                        clientInfo.userAgent(),
                        LoginFailureReason.WRONG_PASSWORD);
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
                loginFailureGuard.onFailure(identifier, clientInfo.ip());
                recordFailureBestEffort(
                        user.getId(),
                        identifier,
                        "CODE",
                        clientInfo.ip(),
                        clientInfo.userAgent(),
                        null);
                throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
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

    private void recordSuccessBestEffort(
            Long userId,
            String identifier,
            String channel,
            String ip,
            String userAgent) {
        try {
            loginLogService.recordSuccess(
                    userId, identifier, channel, ip, userAgent);
        } catch (Exception failure) {
            logAuditFailure("success", channel, failure);
        }
    }

    private void recordFailureBestEffort(
            Long userId,
            String identifier,
            String channel,
            String ip,
            String userAgent,
            LoginFailureReason reason) {
        try {
            loginLogService.recordFailure(
                    userId,
                    identifier,
                    channel,
                    ip,
                    userAgent,
                    reason);
        } catch (Exception failure) {
            logAuditFailure("failure", channel, failure);
        }
    }

    private void logAuditFailure(
            String outcome,
            String channel,
            Exception failure) {
        log.warn(
                "Login audit write failed, outcome={}, channel={}, errorType={}",
                outcome,
                channel,
                failure.getClass().getSimpleName());
    }
}
